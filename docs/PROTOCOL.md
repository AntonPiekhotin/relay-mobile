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

#### `message.read` (C→S)

```json
{
  "v": 1, "type": "message.read",
  "id": "<any UUID, for error correlation>",
  "ts": 1730000000000,
  "payload": { "dialog_id": "...", "up_to_message_id": "..." }
}
```

**A position, not a message.** Everything in the dialog up to and including `up_to_message_id`
becomes read. Opening a chat with fifty unread messages is **one** frame naming the newest of them,
never fifty frames.

- Both payload fields are mandatory and must be non-blank, or the frame yields `BAD_FRAME`. The
  envelope `id` is required too, but only so a rejection can name the offending frame — a read
  carries no idempotency key, because the position *is* the idempotency.
- **Never send a reader id.** The reader comes from the authenticated socket.
- **Safe to repeat and safe to send out of order.** The cursor only ever moves forward, so a retry
  changes nothing and a frame naming an older position is discarded server-side. Resending after a
  reconnect is the intended way to flush reads taken while offline.
- `up_to_message_id` must name a message **in that dialog**. One from another conversation is
  discarded.
- **Nothing is returned to the sending device** — no ack, and no error even if the frame could not be
  queued. Treat the local unread count as cleared optimistically.

#### `message.read` (S→C)

```json
{
  "v": 1, "type": "message.read", "ts": 1730000000000,
  "payload": {
    "dialog_id": "...", "user_id": "...",
    "up_to_message_id": "...", "read_at": "2026-07-26T10:00:00Z"
  }
}
```

Same type as the inbound frame; the direction decides the shape. Outbound adds `user_id`, because it
has to say *whose* cursor moved.

**Two things to do with it, and clients usually forget the second.** When `user_id` is somebody else,
draw read ticks on your own messages up to that position. When `user_id` is **you**, this is another
of your devices reporting a read — clear the unread badge for that dialog. The device that sent the
read is excluded from this fan-out and must not wait for it.

`read_at` is the `created_at` of the message at the cursor, not the time the read happened. It is a
position on the same axis as `created_at`, which is what makes "is my message read" a comparison
rather than a lookup.

Delivered on the same ordering domain as `message.new` for that dialog, so a receipt can never arrive
before the message it acknowledges.

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

> ### History, the dialog list, and catch-up now exist
>
> **This section used to open with a warning that none of it was built. That is no longer true.**
> `GET /api/v1/message/dialogs`, `GET /api/v1/message/dialogs/{id}`, and
> `GET /api/v1/message/dialogs/{id}/messages` with both `before` and `after` cursors are implemented
> and routed, alongside the `POST /api/v1/message/dialogs` that opens a conversation (§5.4).
>
> That closes the gap this section was written around. The architecture permits delivery to be lossy
> *because* the client can fetch what it missed over REST (§7) — the `after` cursor is that fetch, so
> step 3 of the reconnect sequence is now implementable, and a client that reinstalls or switches
> device is no longer looking at the only surviving copy of its own history.
>
> **One thing here is still a target: `POST /api/v1/message/messages`, the REST fallback send (§5.2).**
> `POST /internal/api/v1/messages` remains `/internal`-only, so the socket is still the only way a
> client sends. A backgrounded or mid-reconnect client has to queue locally and flush on reconnect.
>
> **Read state is implemented but lives in §4.1, not here** — it is a pair of `message.read` frames
> plus `unreadCount` on the dialog list, with no REST endpoint of its own.

All routed through the api-gateway as `/api/v1/{service}/**` — the same convention every existing
service already follows — and all requiring `Authorization: Bearer`. The message endpoints therefore
live under `/api/v1/message/**`.

| Method | Path | Purpose | Status |
|---|---|---|---|
| `GET` | `/api/v1/message/dialogs` | List the caller's dialogs | **Implemented** |
| `GET` | `/api/v1/message/dialogs/{id}` | Dialog metadata and participants | **Implemented** |
| `GET` | `/api/v1/message/dialogs/{id}/messages?before=<cursor>&limit=50` | History, newest-first | **Implemented** |
| `GET` | `/api/v1/message/dialogs/{id}/messages?after=<cursor>&limit=100` | Catch-up after reconnect | **Implemented** |
| `POST` | `/api/v1/message/dialogs` | Open the direct dialog with one other user | **Implemented** |
| `POST` | `/api/v1/message/messages` | REST fallback send | Internal only today |
| `GET` | `/api/v1/call/ice-servers` | STUN/TURN servers with short-lived credentials | **Implemented** |
| `GET` | `/api/v1/call/calls?before=<callId>&limit=50` | Call log, newest-first | **Implemented** |
| `GET` | `/api/v1/user/me` | The caller's own profile | **Implemented** |
| `PUT` | `/api/v1/user/me` | Replace the caller's first and last name | **Implemented** |
| `GET` | `/api/v1/user/search?query=<term>&page=0&size=20` | Find people, with a contact flag per hit | **Implemented** |
| `GET` | `/api/v1/user/{id}` | Another user's public subset | **Implemented** |
| `POST` | `/api/v1/user/me/avatar` | Upload a profile picture (multipart) | **Implemented** |
| `DELETE` | `/api/v1/user/me/avatar` | Clear it | **Implemented** |
| `GET` | `/api/v1/user/{id}/avatar` | Fetch the stored bytes | **Implemented** |
| `GET` | `/api/v1/user/me/contacts?page=0&size=20` | The caller's contacts, sorted by name | **Implemented** |
| `POST` | `/api/v1/user/me/contacts` | Add a contact | **Implemented** |
| `DELETE` | `/api/v1/user/me/contacts/{userId}` | Remove one | **Implemented** |
| `PUT` | `/api/v1/notification/device-tokens` | Register this device for push | **Implemented** |
| `DELETE` | `/api/v1/notification/device-tokens/{deviceId}` | Unregister it | **Implemented** |

> **REST bodies are camelCase, frames are snake_case.** The snake_case rule in §3 is about the
> WebSocket payload, which has its own mapper for exactly that reason. **Every** REST endpoint in this
> table — including the message endpoints — uses the default camelCase mapper. Earlier drafts of §5.1
> showed the message endpoints in snake_case; that was never built and the shapes below are the real
> ones. So `dialogId` over HTTP and `dialog_id` on the socket, for the same field.

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

### 5.1 The dialog list and history

```
GET /api/v1/message/dialogs/{id}/messages?limit=50                    → newest 50
GET /api/v1/message/dialogs/{id}/messages?before=<oldestId>&limit=50  → next page back
GET /api/v1/message/dialogs/{id}/messages?after=<newestId>&limit=100  → the gap since <newestId>
```

**Never use offset pagination.** New messages constantly insert at the head, so offsets silently skip rows.

```
GET /api/v1/message/dialogs

200 → { "dialogs": [
  { "dialogId": "...", "type": "direct",
    "participantIds": ["<caller>", "<peer>"],
    "lastMessageAt": "2026-07-26T10:00:00Z",
    "unreadCount": 3,
    "createdAt": "2026-07-26T09:00:00Z" }
] }

GET /api/v1/message/dialogs/{id}          → one element of the same shape, not wrapped

GET /api/v1/message/dialogs/{id}/messages

200 → { "messages": [
  { "messageId": "...", "dialogId": "...", "senderId": "...",
    "text": "...", "createdAt": "2026-07-26T10:00:00Z", "clientMsgId": "550e8400-..." }
  ],
  "nextCursor": "<messageId>" }
```

**The dialog list**

- **Ordered by `lastMessageAt`, most recent first**, and **unpaginated** — the whole list comes back
  in one response. Safe today because a direct dialog only exists where somebody opened a
  conversation; it will need a cursor when group dialogs land.
- `lastMessageAt` is **null for a dialog nobody has written in yet**, and those sort last. Never is
  not the same as long ago.
- **There is no `title` and no last-message preview.** A direct dialog is named by its members:
  subtract yourself from `participantIds` and resolve the peer through `GET /api/v1/user/{id}`.
  message-service holds no names, and an earlier draft of this section listing `title` was wrong.
- `unreadCount` is **relative to the caller** — messages from other people past your own read cursor
  (§4.1). The same dialog has a different count for each participant, and your own messages never
  count.
- Note the id key is **`dialogId`** here, while `POST /dialogs` (§5.4) answers with **`id`**. That
  endpoint shipped first and its shape is already a contract, so the two were not unified. Same
  value, two names, depending on which endpoint you called.

**History**

- `before` pages are **newest-first** (descending); `after` pages are **oldest-first** (ascending).
  Both cursors are exclusive of the message they name; a client takes the last element of each page
  as the next cursor either way.
- **Both cursors are message ids** you already hold, not opaque tokens. `nextCursor` is one too, and
  is `null` on the last page. A cursor naming a message in a *different* dialog is a `400`, not an
  empty page.
- **Passing both `before` and `after` is a `400`.** They are opposite directions from a position.
- `limit` defaults to 50 and is **clamped to 100**, not rejected.
- `createdAt`, not `sentAt` — matching `ack` and `message.new`, so a history row and a frame for the
  same message carry the same field name.
- `clientMsgId` is set only on the caller's own messages and is **absent from other people's**. It
  lets a client merge a history row with a send that is still `PENDING` locally instead of inserting
  a duplicate — the same merge rule as `message.new`.
- A dialog the caller is not in is a **`404`, not a `403`**, on both history and the single-dialog
  lookup. A `403` would confirm that a guessed dialog id names a real conversation.

### 5.2 REST fallback send — NOT IMPLEMENTED

**The only part of §5 that is still a target.** There is no client-facing send over HTTP; the socket
is the only way a client sends today. `POST /internal/api/v1/messages` exists but is `/internal`, so
it is not routed by the api-gateway.

Until it ships, a client with no socket queues locally and flushes on reconnect (§7 step 5).

The intended shape, camelCase like every other implemented endpoint:

```
POST /api/v1/message/messages
{ "clientMsgId": "550e8400-...", "dialogId": "...", "text": "..." }

200 → { "messageId": "...", "clientMsgId": "...", "createdAt": "2026-07-26T10:00:00Z" }
```

Same semantics as the socket path — same `clientMsgId`, same idempotency, same convergence on
the one persistence path.

### 5.3 Profile and contact endpoints

**These are implemented and routed today.** They are the only client-facing surface for finding
another person. Opening a conversation with whoever you found is **not** here — that is
`POST /api/v1/message/dialogs` (§5.4). Earlier drafts of this paragraph said no client-facing dialog
creation existed; it does, and a "message this person" affordance calls §5.4 with the id found here.

Every endpoint below requires `Authorization: Bearer`. **Bodies are camelCase**, like the other
implemented REST surfaces and unlike the snake_case frames of §3–§4. Every "my" endpoint resolves
the subject from the JWT, never from the path, so there is no endpoint that reads or edits somebody
else's profile or address book.

#### Paging

`page` and `size` are query parameters on both `search` and `contacts`, defaulting to `0` and `20`.
**Out-of-range values are clamped, not rejected** — `size=10000` yields the 100-row maximum and
`page=-1` yields page 0, so a client never gets a `400` for asking too much. Every paged response
shares one envelope:

```
{ "items": [ … ], "page": 0, "size": 20,
  "totalElements": 42, "totalPages": 3, "hasNext": true }
```

Page through with `hasNext`, not by comparing `page` against `totalPages`.

> This is **offset paging, and it is the exception to §5.1's rule.** Users are not inserted at the
> head the way messages are, so the drift that makes offsets unusable for history does not apply.
> Do not copy this shape into the message endpoints.

#### `GET /api/v1/user/me` · `PUT /api/v1/user/me`

```
200 → {
  "id": "...", "email": "ada@relay.dev",
  "firstName": "Ada", "lastName": "Lovelace",
  "avatarUrl": "/api/v1/user/<id>/avatar?v=1785600000000",
  "createdAt": "2026-07-26T10:00:00Z", "updatedAt": "2026-07-26T10:00:00Z"
}
```

`PUT` takes `{ "firstName": "...", "lastName": "..." }` and returns the same shape. It is a genuine
`PUT`: **both fields are required and replace the pair wholesale**, so a client sending back a stale
value overwrites a change another of its devices made. Names are trimmed server-side; blank ones are
rejected with `400`.

The body is deliberately narrower than what `GET` returns. `email` is the Keycloak username and
cannot be changed here; the password lives in Keycloak and is changed via
`POST /api/v1/auth/password`; `avatarUrl` is set by uploading a picture, never by naming a URL.

#### `GET /api/v1/user/search?query=<term>&page=0&size=20`

```
200 → { "items": [
  { "user": { "id": "...", "email": "ada@relay.dev",
              "firstName": "Ada", "lastName": "Lovelace", "avatarUrl": null },
    "contact": true }
], "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "hasNext": false }
```

- **A term shorter than 2 characters is a `400`**, not an empty page. Do not issue the request;
  debounce and gate on length client-side.
- **Names match by prefix; email matches only exactly.** A prefix match on email would turn this
  into an address harvester, so searching `"a"` will never enumerate accounts.
- **The caller is excluded from their own results** — you cannot add yourself.
- `contact` says whether this person is already in the caller's contacts, so a list can render
  "Add" or "Remove" without a round trip per row. Note the JSON key is `contact`, not `isContact`.
- Results are ordered by first name, last name, then `id`. The `id` tiebreaker is what keeps paging
  deterministic when names collide.

#### `GET /api/v1/user/{id}`

Returns the `user` object above — the public subset only, so a lookup by id cannot mine `createdAt`
or anything else private. `404` if no such user.

#### Contacts

```
GET  /api/v1/user/me/contacts?page=0&size=20
200 → { "items": [ { "user": { … }, "addedAt": "2026-07-26T10:00:00Z" } ], … }

POST /api/v1/user/me/contacts     { "userId": "..." }
201 → { "user": { … }, "addedAt": "..." }      first time
200 → { "user": { … }, "addedAt": "..." }      already a contact

DELETE /api/v1/user/me/contacts/{userId}
204
```

- **Contacts are one-sided.** Adding somebody does not add you to theirs, and there is no request or
  approval step.
- **Both writes are idempotent.** Re-adding returns the same contact with `200` instead of `201`;
  removing somebody you never had is still `204`. A retry is not an error — treat the status code as
  information, not as a failure to handle.
- Adding yourself is `400`; adding a nonexistent user is `404`.
- The list is sorted **by name, like an address book — not by when each was added**.

#### Avatars

`POST /api/v1/user/me/avatar` is `multipart/form-data` with a `file` part, at most **1 MB**, and
returns `{ "avatarUrl", "contentType", "sizeBytes", "updatedAt" }`.

**The content type is detected from the file's own bytes, never from the part's `Content-Type`.**
Only `image/png`, `image/jpeg`, `image/webp`, and `image/gif` are stored; anything else is `415`,
and an oversized upload is `413`.

`avatarUrl` is **relative on purpose** — the host depends on which edge the client came through, so
resolve it against your configured API base URL. The `v` stamp changes whenever the picture does,
which is what makes the aggressive cache headers on `GET /{id}/avatar` safe. `DELETE` clears the
picture and is idempotent. A user with no picture has `avatarUrl: null` and `GET /{id}/avatar`
returns `404`.

#### Error shape

Failures on these endpoints do **not** use the `code`/`message` shape of §8 — that is the frame
vocabulary. REST errors come back as:

```
{ "time": "2026-07-26 10:00:00", "statusCode": 400,
  "errorMessage": ["Search query must be at least 2 characters"] }
```

`errorMessage` is always an array; validation failures put one entry per rejected field, formatted
`field: reason`. Match on the HTTP status, not on the message text.

Some responses also carry a `stackTrace` array. **Ignore it and do not show it** — and make sure
your deserializer tolerates unknown fields, because a strict parser will choke on it.

### 5.4 Opening a dialog

**Implemented.** The only way a client obtains a dialog id, and therefore the entry point to every
conversation: `message.send` (§4.1) requires a `dialog_id` the caller participates in, and a client
has no other way to create one.

```
POST /api/v1/message/dialogs
{ "peerId": "<other user's id>" }

201 → { "id": "...", "type": "direct",
        "participantIds": ["<caller>", "<peer>"],
        "createdAt": "2026-07-26T10:00:00Z" }
```

camelCase, like every other implemented endpoint.

- **`201` when this call opened the dialog, `200` when it already existed.** Treat both as success;
  a repeat is not an error, and the body is identical either way.
- **Idempotent by the pair, not by the request.** The dialog is keyed on the two participant ids, so
  a retry, a second device, and both people tapping at the same moment all converge on one dialog.
  There is no need to check whether a dialog exists before calling this — that *is* the call.
- **The caller comes from the token.** Only `peerId` is sent; a client cannot open a conversation on
  somebody else's behalf. `/internal` may name every participant because only services reach it.
- **`400` for opening a dialog with yourself** (a notes-to-self dialog is not implemented) and for a
  `peerId` containing the reserved key separator. `404` if no such user.
- `type` is lowercase (`direct`), matching §5.1.

Persist the returned `id` locally on success: it is the dialog the UI opens, and until the dialog
list endpoint exists it is the **only** record a client has that the conversation exists.

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
3. Call `GET /api/v1/message/dialogs` to pick up conversations opened while you were away, and their
   unread counts.
4. For each dialog with local state, call
   `GET /api/v1/message/dialogs/{id}/messages?after=<lastKnownServerId>`, paging until a page comes
   back short. Merge results, deduplicating on `messageId`.
5. Flush the outbox — resend anything still `PENDING`, and resend `message.read` for any read taken
   while offline.

**The server buffers nothing for offline clients.** There is no replay, no server-side outbox, no "missed messages" frame. Catch-up over REST is the only mechanism, and it is sufficient because the database is authoritative.

**All five steps work now.** Step 3 is what makes a conversation somebody else started visible at
all — without it, a dialog id only ever existed on the device that opened it. Step 4 is the
correctness mechanism the whole delivery design leans on: a frame missed while the socket was down is
recovered here, which is why the gateway is allowed to drop it.

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
| History page size | 50 (max 100) | **Yes** — clamped by the REST API, never rejected |
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
