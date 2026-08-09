# Relay Wire Protocol

**This document is the contract between the backend and all clients.** It is the single source of truth for the wire format.

> **Keep an identical copy in the backend repository.** If the two diverge, the protocol is broken. Any change here requires a matching backend change in the same PR cycle.

**Protocol version: 1**

---

## 1. Transports

| Transport | Used for | Base |
|---|---|---|
| HTTPS | History, dialog list, profile, REST fallback send | `https://<host>/api` |
| WSS | Real-time send, delivery, (later) presence and call signaling | `wss://<host>/ws` |

**Rule: pull over HTTP, push over WebSocket.** History and pagination are HTTP-only — never request history over the socket. Large payloads on the socket cause head-of-line blocking that delays time-critical frames.

---

## 2. Authentication

Keycloak-issued JWT. Validation is stateless on the server.

**HTTP:** `Authorization: Bearer <jwt>`

**WebSocket:** the token is presented **at the handshake**, not after connecting, and it travels
in the **subprotocol list** — not in a header:

```
Sec-WebSocket-Protocol: access_token, <jwt>
```

Two entries, in that order: the literal marker `access_token`, then the raw JWT. The server reads
the value immediately following the marker, and confirms `access_token` (never the token itself)
as the negotiated subprotocol in its response.

```js
new WebSocket("wss://<host>/ws", ["access_token", jwt])
```

- **This is the only accepted mechanism, for native clients too.** An `Authorization` header on
  the handshake is *not* read — the server replaces the default bearer-token resolver with the
  subprotocol one, so a handshake carrying only a header authenticates as nobody and is rejected.
- The reason is browsers: the `WebSocket` constructor exposes no way to set request headers, and
  the subprotocol list is the only client-controlled field available at handshake time.
- **Never put the token in a query string.** It leaks into access logs and proxy logs.
- The header may arrive comma-joined or as repeated headers; both are accepted.

A rejected handshake fails with a 401 **before the upgrade** — the socket is never opened, so
there is no unauthenticated connected state to reason about.

**On 401 / handshake rejection:** refresh the token, then reconnect. Do not retry with the same token.

---

## 3. Frame envelope

Every WebSocket frame, both directions:

```json
{
  "v": 1,
  "type": "message.send",
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "ts": 1730000000000,
  "payload": { }
}
```

| Field | Type | Notes |
|---|---|---|
| `v` | int | Protocol version. Currently `1`. |
| `type` | string | Dot-namespaced discriminator. Drives routing. |
| `id` | string (UUID) | Client-generated on C→S frames. Used for correlation and idempotency. |
| `ts` | int64 | Epoch millis. **Advisory only** — the server assigns authoritative timestamps. Never display client `ts` after an ack. |
| `payload` | object | Type-specific. **Keys are `snake_case`** — see below. |

**Envelope keys are flat lowercase; payload keys are `snake_case`.** The envelope is `v`, `type`,
`id`, `ts`; everything inside `payload` uses underscores — `client_msg_id`, `message_id`,
`created_at`, `dialog_id`, `sender_id`, `ref_id`. This split is deliberate: the wire mapper is a
separate instance from the camelCase one used for internal Kafka events, so the client-facing
contract cannot be broken by an internal refactor.

**Server-to-client frames carry no `id`.** The envelope the server emits is `{v, type, ts, payload}`.
Correlation runs through payload fields — `client_msg_id` on an ack, `ref_id` on an error.

**Unknown frame types must be ignored silently by clients, not treated as errors.** This is what
allows the server to add frame types without breaking older clients. Note the asymmetry: the
*server* does not extend the same courtesy to clients — an unrecognised inbound `type` is answered
with a `BAD_FRAME` error frame, not ignored.

---

## 4. Frame catalogue

### 4.1 Messaging — implemented

#### `session.connected` (S→C)

```json
{
  "v": 1, "type": "session.connected", "ts": 1730000000000,
  "payload": { "user_id": "...", "session_id": "..." }
}
```

The **first frame on every accepted socket**, sent unprompted. It confirms which identity the
server resolved from the handshake token, and names this connection. A client that never receives
it did not complete an authenticated handshake.

`session_id` identifies *this connection*, not this user — the same account on phone and web has
two. The server uses it to route an ack back to the exact device that sent, rather than to all of
them.

#### `message.send` (C→S)

```json
{
  "v": 1, "type": "message.send",
  "id": "<client_msg_id, UUID v4>",
  "ts": 1730000000000,
  "payload": { "dialog_id": "...", "text": "..." }
}
```

The frame `id` **is** the `client_msg_id`. Generate it once, store it locally, and reuse it for
every retry of this message. Both `id` and `payload.dialog_id`/`payload.text` are mandatory and
must be non-blank; omitting any of them yields `BAD_FRAME`.

> **`reply_to` is not implemented.** The server parses only `dialog_id` and `text` out of the
> payload and silently discards anything else, so a threaded reply would be stored as an ordinary
> message. Do not build against it until it appears here.

#### `ack` (S→C)

```json
{
  "v": 1, "type": "ack", "ts": 1730000000123,
  "payload": {
    "client_msg_id": "550e8400-...",
    "message_id": "<server-assigned UUID>",
    "created_at": "2026-07-26T10:00:00Z"
  }
}
```

Correlate on `client_msg_id`. Replace the local optimistic values with `message_id` and
`created_at` — **the server timestamp is authoritative and may differ from the local one**, which
can reorder the message in the list.

**Note the two timestamp encodings.** The envelope `ts` is epoch millis as a number; `created_at`
and every other payload timestamp is an **ISO-8601 string**. They are not interchangeable.

#### `message.new` (S→C)

```json
{
  "v": 1, "type": "message.new", "ts": 1730000000200,
  "payload": {
    "message_id": "...", "dialog_id": "...", "sender_id": "...",
    "text": "...", "created_at": "2026-07-26T10:00:00Z"
  }
}
```

Delivered for messages from others **and for the user's own messages sent from another device** —
the sending connection gets an `ack` instead, and is excluded from this fan-out by its
`session_id`. Deduplicate on `message_id`; receiving one you already have is normal, not an error.

#### `error` (S→C)

```json
{
  "v": 1, "type": "error", "ts": 1730000000000,
  "payload": { "code": "DIALOG_NOT_FOUND", "message": "...", "ref_id": "550e8400-..." }
}
```

`ref_id` echoes the `id` of the offending frame. Use it to fail one specific pending message rather
than showing a generic error. It is **nullable**: an error raised before the envelope `id` could be
read (unparseable JSON) arrives with `ref_id: null` and cannot be attributed to a pending send.

#### `ping` / `pong`

Client sends `ping` every 30s with an empty payload; server replies `pong` with `ref_id` echoing
the ping's `id`. Missing two consecutive pongs → treat the connection as dead and reconnect.

Heartbeating is **client-driven only**: the server never initiates a ping and currently enforces
no idle timeout, so a half-open socket is detected by the client or not at all.

### 4.2 Presence and typing — NOT YET IMPLEMENTED

Do not build against these until the backend ships them.

| Type | Dir | Payload |
|---|---|---|
| `presence.subscribe` | C→S | `dialogId` |
| `presence.unsubscribe` | C→S | `dialogId` |
| `presence.update` | S→C | `userId`, `status`, `lastSeen?` |
| `typing.start` | both | `dialogId`, `userId` (S→C only) |

Presence is **subscribe-on-demand**: subscribe when a conversation opens, unsubscribe when it closes. Never expect presence for conversations not on screen.

Typing: throttle to at most one emission per 3 seconds while typing, never per keystroke. Expire the indicator client-side after ~5s of silence — do not rely on a stop frame arriving.

### 4.3 Notifications — WIRED, BUT NEVER SENT

| Type | Dir | Payload |
|---|---|---|
| `notification.new` | S→C | `notification_id`, `kind`, `data`, `created_at` |

In-app notifications only. Distinct from `message.new`. The intent is that the server sends either
a socket notification **or** a push, never both.

**The gateway can emit this frame but nothing makes it.** The gateway consumes the
`notifications.delivery` topic and would encode anything on it as a `notification.new` frame — but
no service publishes to that topic. notification-service handles only the *push* half of the
socket-XOR-push split and never sends the in-app half back. Treat this as unimplemented until a
producer exists; note that the payload has no `body` field, unlike an earlier draft of this doc.

### 4.4 Calls — implemented (direct calls, two participants)

Group calls are not implemented. Everything below is 1:1.

#### Inbound: one frame type per verb (C→S)

| Type | Payload |
|---|---|
| `call.invite` | `call_id`, `callee_id`, `media` (`audio`\|`video`), `sdp`, `dialog_id?` |
| `call.accept` | `call_id`, `sdp` |
| `call.reject` | `call_id`, `reason?` |
| `call.ice` | `call_id`, `candidate` (opaque `RTCIceCandidateInit`) |
| `call.hangup` | `call_id`, `reason?` |

All five require an envelope `id`; it is echoed as `ref_id` on any resulting `error`. Every listed
payload field except the `?` ones is mandatory and must be non-blank, or the frame yields
`BAD_FRAME`.

**The client generates `call_id`** (UUID v4). Trickle ICE starts before any round trip could hand an
id back, so candidates need one that exists immediately — and re-sending an invite with the same
`call_id` is a retry of that call, not a second call. While the call is still ringing a retried
invite is re-relayed to the callee, on the assumption that the client retried because it saw
nothing happen.

**Never send a caller id.** The server takes the caller and the device from the authenticated
socket; a `caller_id` in the payload is discarded, not honoured.

#### Outbound: one opaque frame (S→C)

```json
{
  "v": 1, "type": "call.signal", "ts": 1730000000000,
  "payload": {
    "call_id": "...", "from_user_id": "...",
    "signal": { "verb": "invite", "media": "audio", "sdp": "v=0..." }
  }
}
```

One frame type, with the verb inside `signal`. A client that does not recognise a verb ignores one
signal instead of failing to route a frame, which is what lets verbs be added later.

| `signal.verb` | Meaning | Other `signal` keys |
|---|---|---|
| `invite` | You are being called | `media`, `sdp`, `dialog_id`, `started_at`, `ring_expires_at` |
| `accept` | The callee answered | `sdp` |
| `reject` | The callee declined | `reason` |
| `ice` | A candidate from the other party | `candidate` |
| `hangup` | The other party ended it | `reason`, `duration_s` |
| `cancel` | Stop showing this call — another of *your* devices settled it | `reason` |
| `missed` | It rang out. Server-decided | `reason` |
| `state` | Progress; currently only your own invite going to `ringing` | `status` |

`cancel` is the multi-device rule: when a user answers or declines on one device, their other
devices get `cancel` and the device that acted does not. Without handling it, a phone keeps ringing
after the call was taken on a tablet.

#### Behaviour a client must expect

- **A `busy` verb does not exist.** Calling somebody already in a call fails the `call.invite` frame
  synchronously with `USER_BUSY`, correlated by `ref_id`.
- **A candidate for an unknown `call_id` is buffered ~5s**, then dropped. Sending candidates before
  your own invite is accepted is safe.
- **The server rings for 40s** (up to ~45s, given the sweep interval) and then declares `missed`.
  `ring_expires_at` on the invite is the honest deadline — do not run your own shorter one.
- **A missed or rejected call frees both parties immediately**; a new call can be placed at once.
- **`duration_s` is talk time, not ring time.** It is absent for a call that was never answered.
- **ICE is exempt from rate limiting** when the limiter lands (§9). It arrives as a burst by design.

#### Media

Signaling is all the server does. Audio and video flow peer-to-peer, relayed by TURN only when a
direct path cannot be found, and never through any service, queue, or database. Fetch
`GET /api/v1/call/ice-servers` (§5) before building an `RTCPeerConnection`.

---

## 5. REST endpoints

> ### ⚠ None of this exists yet
>
> **There is no client-facing message or dialog REST API.** message-service exposes only
> `POST /internal/api/v1/messages` and `POST /internal/api/v1/dialogs`, both under `/internal`,
> which the api-gateway deliberately does not route — they are reachable service-to-service and
> nowhere else. No history endpoint, no dialog list, no cursor pagination, no REST fallback send
> is implemented on any service.
>
> **This is the largest gap between this document and the running system, and it is load-bearing.**
> The architecture permits delivery to be lossy *because* the client can fetch the gap over REST
> (§7). With no history endpoint, a frame missed while offline is currently unrecoverable by the
> client. Building this is the next milestone.
>
> The rest of §5 is therefore the **target contract**, not a description of today. It is specified
> here so clients can be written against it and so the eventual implementation has one shape to
> hit. Everything below is normative-when-built.
>
> **The call endpoints are the exception — those exist.**

All under `/api`, all requiring `Authorization: Bearer`.

| Method | Path | Purpose | Status |
|---|---|---|---|
| `GET` | `/dialogs` | List the caller's dialogs | Planned |
| `GET` | `/dialogs/{id}` | Dialog metadata and participants | Planned |
| `GET` | `/dialogs/{id}/messages?before=<cursor>&limit=50` | History, newest-first | Planned |
| `GET` | `/dialogs/{id}/messages?after=<cursor>&limit=100` | Catch-up after reconnect | Planned |
| `POST` | `/dialogs` | Create a dialog | Internal only today |
| `POST` | `/messages` | REST fallback send | Internal only today |
| `GET` | `/api/v1/call/ice-servers` | STUN/TURN servers with short-lived credentials | **Implemented** |
| `GET` | `/api/v1/call/calls?before=<callId>&limit=50` | Call log, newest-first | **Implemented** |
| `PUT` | `/api/v1/notification/device-tokens` | Register this device for push | **Implemented** |
| `DELETE` | `/api/v1/notification/device-tokens/{deviceId}` | Unregister it | **Implemented** |

> **Implemented REST bodies are camelCase, unlike frames.** The snake_case rule in §3 is about the
> WebSocket payload, which has its own mapper for exactly that reason. The device-token and call
> endpoints use the default camelCase mapper, and the snake_case examples further down this section
> describe endpoints that do not exist yet. Match the style of the endpoint you are calling.

### 5.0 Call endpoints

```
GET /api/v1/call/ice-servers

200 → {
  "iceServers": [
    { "urls": ["stun:localhost:3478"] },
    { "urls": ["turn:localhost:3478?transport=udp"],
      "username": "1785600000:<userId>", "credential": "<base64 hmac>" }
  ],
  "ttlSeconds": 43200
}
```

Hand `iceServers` to `RTCPeerConnection` unchanged. TURN credentials are minted per request and
expire — refetch before `ttlSeconds` elapses rather than caching them indefinitely.

```
GET /api/v1/call/calls?before=<callId>&limit=50

200 → {
  "calls": [
    { "id": "...", "dialogId": null, "direction": "outgoing", "peerId": "...",
      "media": "audio", "status": "ended", "startedAt": "...", "answeredAt": "...",
      "endedAt": "...", "durationSeconds": 42, "endReason": "hangup" }
  ],
  "nextCursor": "<callId>"
}
```

`direction` and `peerId` are relative to the caller of this endpoint. `nextCursor` is the `before`
value for the following page and is `null` on the last one. `status` is one of `ringing`, `answered`,
`rejected`, `missed`, `ended`.

### 5.1 Pagination is cursor-based, never offset

```
GET /dialogs/{id}/messages?limit=50                    → newest 50
GET /dialogs/{id}/messages?before=<oldestId>&limit=50  → next page back
```

**Never use offset pagination.** New messages constantly insert at the head, so offsets silently skip rows.

### 5.2 REST fallback send

```
POST /api/messages
{ "client_msg_id": "550e8400-...", "dialog_id": "...", "text": "..." }

200 → { "message_id": "...", "client_msg_id": "...", "created_at": "2026-07-26T10:00:00Z" }
```

Same semantics as the socket path — same `client_msg_id`, same idempotency, same convergence on
the one persistence path. Use when the socket is not connected (cold start, mid-reconnect,
backgrounded).

---

## 6. Idempotency and the send contract

**This is the most important section in this document.**

1. Client generates `clientMsgId` (UUID v4) and persists the message locally as `PENDING`.
2. Client sends via socket (or REST if disconnected).
3. Server deduplicates on `(sender_id, client_message_id)` via a database unique constraint.
   Note the column is `client_message_id` even though the wire field is `client_msg_id`.
4. Server persists, **then** acks.
5. Client correlates on `clientMsgId`, stores `messageId`, marks `SENT`.
6. **No ack within timeout → resend the exact same `clientMsgId`.** The server returns the original ack; exactly one message exists.

**The ack means "durably persisted."** It does not mean the recipient received it.

Suggested client timeout: 5s over socket, 10s over REST, then exponential backoff capped at ~60s.

---

## 7. Reconnect and catch-up

The socket **will** drop — tunnels, backgrounding, network switches. Recovery is always the same:

1. Reconnect and authenticate at handshake.
2. Wait for `session.connected` — it confirms the handshake resolved to the expected identity.
3. For each dialog with local state, call `GET /dialogs/{id}/messages?after=<lastKnownServerId>`.
4. Merge results, deduplicating on `message_id`.
5. Flush the outbox — resend anything still `PENDING`.

**The server buffers nothing for offline clients.** There is no replay, no server-side outbox, no "missed messages" frame. Catch-up over REST is the only mechanism, and it is sufficient because the database is authoritative.

⚠ **Step 3 is not implementable today** — the history endpoint does not exist (§5). Steps 1, 2 and
5 work now; a message that arrived while the socket was down stays unseen until it does.

---

## 8. Error codes

`code` is a plain string, not an enum on the wire: the gateway forwards codes raised by downstream
services without enumerating them, so **clients must tolerate an unknown code** and fall back to
generic handling rather than failing to parse.

Raised by the gateway, on the frame it could not process:

| Code | Meaning | Client action |
|---|---|---|
| `BAD_FRAME` | Not valid JSON, missing `v`/`type`/`id`, or an unknown `type` | Bug — log, do not retry |
| `UNSUPPORTED_VERSION` | Envelope `v` is a version this server does not speak | Fail permanently; the client is too new or too old |
| `SEND_FAILED` | The send could not be handed to the broker | Retry the same `id` over REST |
| `CALL_SIGNAL_FAILED` | call-service was unreachable or failed with no better code | Tear down the peer connection; signal ordering is already lost |

Raised by call-service and relayed as an `error` frame on the offending signal:

| Code | Meaning | Client action |
|---|---|---|
| `USER_BUSY` | You or the callee is already in a call | Surface to user; do not retry |
| `CALL_NOT_FOUND` | No such `call_id` | Drop the local call; do not retry |
| `INVALID_CALL_STATE` | The call is not in a state that accepts this verb — including losing a race to another device | Drop the local call; do not retry |
| `NOT_A_PARTICIPANT` | You are not in this call | Bug — log, do not retry |
| `INVALID_REQUEST` | Rejected on validation | Bug — log, do not retry |

Raised by message-service and relayed as an `error` frame:

| Code | Meaning | Client action |
|---|---|---|
| `DIALOG_NOT_FOUND` | Dialog does not exist | Fail the message, surface to user |
| `NOT_A_PARTICIPANT` | Caller is not in the dialog | Fail the message, surface to user |
| `INVALID_REQUEST` | Rejected on validation | Bug — log, do not retry |
| `INTERNAL` | Server-side failure | Retry with backoff |

**Retryable:** `SEND_FAILED`, `INTERNAL`, and timeouts.
**Not retryable:** `BAD_FRAME`, `UNSUPPORTED_VERSION`, `INVALID_REQUEST`, `DIALOG_NOT_FOUND`,
`NOT_A_PARTICIPANT`, `USER_BUSY`, `CALL_NOT_FOUND`, `INVALID_CALL_STATE`, `CALL_SIGNAL_FAILED`.

**Nothing about a call is retryable.** Call setup is ordered and time-boxed; by the time a signal
has failed, replaying it produces a peer connection the other side is no longer negotiating. Start a
new call instead.

**There is no `UNAUTHORIZED` error frame.** Authentication is settled at the handshake, so a bad
token is an HTTP 401 on the upgrade request and no socket is ever opened — a client sees a failed
connection, not a frame. `RATE_LIMITED` and `PAYLOAD_TOO_LARGE` do not exist either; see §9.

---

## 9. Limits

| Limit | Value | Enforced? |
|---|---|---|
| Outbound buffer per connection | 256 frames | **Yes** — overflow closes the socket with 1013 |
| Heartbeat interval | 30s | Client-driven; server never initiates |
| History page size | 50 (max 100) | Yes, by the REST API |
| Max frame size | 64 KB (intended) | **No** |
| Rate limit | ~30 frames/sec sustained (intended) | **No** |
| Server idle timeout | 90s (intended) | **No** |

**The bottom three are not implemented.** No rate limiter, frame-size check, or idle timeout
exists in the gateway today, which is why §8 has no `RATE_LIMITED` or `PAYLOAD_TOO_LARGE` code.
Clients should still respect the intended values — they are what the server will enforce when the
limiter lands, and a client already living within them needs no change on that day.

**The one limit that is enforced is the outbound buffer**, and it is enforced by disconnection.
Each connection has a bounded 256-frame queue drained by a single writer thread. A client that
stops reading fills it, and the server closes the socket with **1013 (Service Overload)** rather
than buffering without bound. This is deliberate: one dropped client is better than an exhausted
heap. Treat 1013 as transient — reconnect and re-sync over REST, exactly as after any other drop.

---

## 10. Shared DTO module (recommended)

Both backend and client are Kotlin. Rather than hand-writing these models twice, publish a
`relay-protocol` artifact containing the envelope and payload data classes, consumed by both.

This turns field-name drift from a runtime bug into a compile error. Strongly preferred over
duplicated definitions.

**Not the same thing as `common`.** `common` holds internal *event* DTOs — the Kafka contract
between services, camelCase, nobody outside the backend sees it. The wire protocol is snake_case
and is a contract with mobile clients. They are two contracts with different audiences and
different rates of change; publishing `common` to clients would leak the former and couple client
releases to internal refactors. This is also why the gateway keeps two separate Jackson mappers.

```kotlin
@Serializable
data class Envelope(
    val v: Int = 1,
    val type: String,
    val id: String? = null,
    val ts: Long,
    val payload: JsonElement
)
```
