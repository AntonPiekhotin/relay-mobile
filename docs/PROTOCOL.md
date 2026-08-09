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

**WebSocket:** token is presented **at the handshake**, not after connecting.

- Native clients: `Authorization: Bearer <jwt>` header on the handshake request.
- **Never put the token in a query string.** It leaks into access logs and proxy logs.

A rejected handshake closes the connection immediately — there is no unauthenticated connected state.

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
| `payload` | object | Type-specific. |

**Unknown frame types must be ignored silently, not treated as errors.** This is what allows the server to add frame types without breaking older clients.

---

## 4. Frame catalogue

### 4.1 Messaging — implemented

#### `message.send` (C→S)

```json
{
  "v": 1, "type": "message.send",
  "id": "<clientMsgId, UUID v4>",
  "ts": 1730000000000,
  "payload": { "dialogId": "...", "text": "...", "replyTo": null }
}
```

The frame `id` **is** the `clientMsgId`. Generate it once, store it locally, and reuse it for every retry of this message.

#### `ack` (S→C)

```json
{
  "v": 1, "type": "ack", "ts": 1730000000123,
  "payload": {
    "clientMsgId": "550e8400-...",
    "messageId": "<server-assigned UUID>",
    "createdAt": 1730000000100
  }
}
```

Correlate on `clientMsgId`. Replace the local optimistic values with `messageId` and `createdAt` — **the server timestamp is authoritative and may differ from the local one**, which can reorder the message in the list.

#### `message.new` (S→C)

```json
{
  "v": 1, "type": "message.new", "ts": 1730000000200,
  "payload": {
    "messageId": "...", "dialogId": "...", "senderId": "...",
    "text": "...", "createdAt": 1730000000100, "replyTo": null
  }
}
```

Delivered for messages from others **and for the user's own messages sent from another device**. Deduplicate on `messageId` — receiving one you already have is normal, not an error.

#### `error` (S→C)

```json
{
  "v": 1, "type": "error", "ts": 1730000000000,
  "payload": { "code": "DIALOG_NOT_FOUND", "message": "...", "refId": "550e8400-..." }
}
```

`refId` echoes the `id` of the offending frame. Use it to fail one specific pending message rather than showing a generic error.

#### `ping` / `pong`

Client sends `ping` every 30s with an empty payload; server replies `pong`. Missing two consecutive pongs → treat the connection as dead and reconnect.

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

### 4.3 Notifications — NOT YET IMPLEMENTED

| Type | Dir | Payload |
|---|---|---|
| `notification.new` | S→C | `notificationId`, `kind`, `body`, `data` |

In-app notifications only. Distinct from `message.new`. The server sends either a socket notification **or** a push, never both.

### 4.4 Calls — NOT YET IMPLEMENTED

| Type | Dir | Payload |
|---|---|---|
| `call.offer` | both | `callId`, `calleeId`/`callerId`, `type` (`audio`\|`video`), `sdp` |
| `call.answer` | both | `callId`, `sdp` |
| `call.ice` | both | `callId`, `candidate` |
| `call.reject` | both | `callId`, `reason?` |
| `call.end` | both | `callId`, `reason?` |
| `call.state` | S→C | `callId`, `status` |

**Buffer `call.ice` frames for an unknown `callId`.** Candidates can arrive before their offer; discarding them breaks call setup. Hold them for ~5s and apply once the offer lands.

---

## 5. REST endpoints

All under `/api`, all requiring `Authorization: Bearer`.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/dialogs` | List the caller's dialogs |
| `GET` | `/dialogs/{id}` | Dialog metadata and participants |
| `GET` | `/dialogs/{id}/messages?before=<cursor>&limit=50` | History, newest-first |
| `GET` | `/dialogs/{id}/messages?after=<cursor>&limit=100` | Catch-up after reconnect |
| `POST` | `/dialogs` | Create a dialog |
| `POST` | `/messages` | REST fallback send |

### 5.1 Pagination is cursor-based, never offset

```
GET /dialogs/{id}/messages?limit=50                 → newest 50
GET /dialogs/{id}/messages?before=<oldestId>&limit=50  → next page back
```

**Never use offset pagination.** New messages constantly insert at the head, so offsets silently skip rows.

### 5.2 REST fallback send

```
POST /api/messages
{ "clientMsgId": "550e8400-...", "dialogId": "...", "text": "...", "replyTo": null }

200 → { "messageId": "...", "clientMsgId": "...", "createdAt": 1730000000100 }
```

Same semantics as the socket path — same `clientMsgId`, same idempotency. Use when the socket is not connected (cold start, mid-reconnect, backgrounded).

---

## 6. Idempotency and the send contract

**This is the most important section in this document.**

1. Client generates `clientMsgId` (UUID v4) and persists the message locally as `PENDING`.
2. Client sends via socket (or REST if disconnected).
3. Server deduplicates on `(senderId, clientMsgId)` via a database unique constraint.
4. Server persists, **then** acks.
5. Client correlates on `clientMsgId`, stores `messageId`, marks `SENT`.
6. **No ack within timeout → resend the exact same `clientMsgId`.** The server returns the original ack; exactly one message exists.

**The ack means "durably persisted."** It does not mean the recipient received it.

Suggested client timeout: 5s over socket, 10s over REST, then exponential backoff capped at ~60s.

---

## 7. Reconnect and catch-up

The socket **will** drop — tunnels, backgrounding, network switches. Recovery is always the same:

1. Reconnect and authenticate at handshake.
2. For each dialog with local state, call `GET /dialogs/{id}/messages?after=<lastKnownServerId>`.
3. Merge results, deduplicating on `messageId`.
4. Flush the outbox — resend anything still `PENDING`.

**The server buffers nothing for offline clients.** There is no replay, no server-side outbox, no "missed messages" frame. Catch-up over REST is the only mechanism, and it is sufficient because the database is authoritative.

---

## 8. Error codes

| Code | Meaning | Client action |
|---|---|---|
| `UNAUTHORIZED` | Token invalid or expired | Refresh, reconnect |
| `DIALOG_NOT_FOUND` | Dialog missing, or user not a participant | Fail the message, surface to user |
| `RATE_LIMITED` | Too many frames | Back off, retry with same `clientMsgId` |
| `PAYLOAD_TOO_LARGE` | Frame exceeds 64 KB | Fail permanently, do not retry |
| `INVALID_ENVELOPE` | Malformed frame | Bug — log, do not retry |
| `INTERNAL` | Server-side failure | Retry with backoff |

**Retryable:** `RATE_LIMITED`, `INTERNAL`, and timeouts.
**Not retryable:** `PAYLOAD_TOO_LARGE`, `INVALID_ENVELOPE`, `DIALOG_NOT_FOUND`.

---

## 9. Limits

| Limit | Value |
|---|---|
| Max frame size | 64 KB |
| Rate limit | ~30 frames/sec sustained, burst allowed |
| Heartbeat interval | 30s |
| Server idle timeout | 90s |
| History page size | 50 (max 100) |

Exceeding the rate limit yields `RATE_LIMITED`, not a disconnect.

---

## 10. Shared DTO module (recommended)

Both backend and client are Kotlin. Rather than hand-writing these models twice, publish a `relay-protocol` module containing the envelope and payload data classes with `kotlinx.serialization`, consumed by both.

This turns field-name drift from a runtime bug into a compile error. Strongly preferred over duplicated definitions.

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
