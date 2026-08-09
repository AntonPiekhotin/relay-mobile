# Sync Engine, Outbox, and Local Database

**This is the hardest part of the client and where nearly all subtle bugs live.** Read this entire file before touching anything in `sync/` or `db/`.

---

## 1. Responsibilities

The sync engine owns everything between the network and the database:

1. Maintain the WebSocket connection with reconnect and backoff
2. Flush the outbox — send `PENDING` messages, retry on timeout
3. Apply acks — correlate, promote `PENDING` → `SENT`
4. Apply inbound `message.new` — deduplicate and insert
5. Run catch-up after every reconnect
6. Track sync cursors per dialog

It never touches the UI. It only writes to the DB; the UI notices because it observes the DB.

---

## 2. Database schema

SQLDelight. `local_id` is the stable local identity; `server_id` and `client_msg_id` are both nullable because they exist at different points in a message's life.

```sql
-- dialog.sq
CREATE TABLE dialog (
    id               TEXT    NOT NULL PRIMARY KEY,
    type             TEXT    NOT NULL,          -- 'direct' | 'group'
    title            TEXT,
    last_message_at  INTEGER,
    unread_count     INTEGER NOT NULL DEFAULT 0
);

-- message.sq
CREATE TABLE message (
    local_id       INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    server_id      TEXT    UNIQUE,              -- null until acked / for own pending sends
    client_msg_id  TEXT    UNIQUE,              -- null for messages received from others
    dialog_id      TEXT    NOT NULL,
    sender_id      TEXT    NOT NULL,
    text           TEXT    NOT NULL,
    reply_to       TEXT,
    created_at     INTEGER NOT NULL,            -- local clock until acked, then server clock
    state          TEXT    NOT NULL,            -- 'PENDING' | 'SENT' | 'FAILED'
    fail_reason    TEXT,
    attempt_count  INTEGER NOT NULL DEFAULT 0,
    next_retry_at  INTEGER
);

CREATE INDEX idx_message_dialog ON message(dialog_id, created_at DESC, local_id DESC);
CREATE INDEX idx_message_outbox ON message(state, next_retry_at) WHERE state = 'PENDING';

-- sync_state.sq
CREATE TABLE sync_state (
    dialog_id            TEXT NOT NULL PRIMARY KEY,
    newest_synced_id     TEXT,                  -- cursor for catch-up (?after=)
    oldest_loaded_id     TEXT,                  -- cursor for pagination (?before=)
    has_more_history     INTEGER NOT NULL DEFAULT 1
);
```

**Why both `server_id` and `client_msg_id` are nullable:**

| Message origin | `client_msg_id` | `server_id` |
|---|---|---|
| Own send, not yet acked | set | null |
| Own send, acked | set | set |
| Received from another user | null | set |
| Own send from another device | null | set |

**The two UNIQUE constraints are the deduplication mechanism.** Do not rely on application-level checks — a concurrent ack and `message.new` for the same message will both pass an application check and both insert. Let the constraint reject the second.

**Ordering:** `ORDER BY created_at DESC, local_id DESC`. The `local_id` tiebreaker keeps ordering stable when timestamps collide, which happens with rapid sends.

---

## 3. Message state machine

```
        create
          │
          ▼
      ┌────────┐   ack received     ┌──────┐
      │PENDING │──────────────────► │ SENT │
      └───┬────┘                    └──────┘
          │
          │ permanent error, or retries exhausted
          ▼
      ┌────────┐   user taps retry
      │ FAILED │──────────────────► PENDING
      └────────┘
```

`PENDING` renders with a clock icon, `SENT` with a checkmark, `FAILED` with a retry affordance. The UI derives all of this from the `state` column — no separate in-memory tracking.

---

## 4. Sending

```kotlin
suspend fun send(dialogId: String, text: String) {
    val clientMsgId = uuid4().toString()
    db.insertMessage(
        clientMsgId = clientMsgId,
        serverId    = null,
        dialogId    = dialogId,
        senderId    = currentUserId,
        text        = text,
        createdAt   = Clock.System.now().toEpochMilliseconds(),
        state       = "PENDING"
    )
    outbox.wake()   // returns immediately; UI already updated via the DB Flow
}
```

**Order matters absolutely: DB write first, network second.** If the app is killed between the two, the message is still in the outbox and goes out on next launch. Reverse the order and it is lost.

`send` does not await a result. The result arrives as a database change.

---

## 5. The outbox loop

```kotlin
while (isActive) {
    val batch = db.pendingMessagesDue(now())
    for (msg in batch) {
        val sent = if (socket.isConnected) socket.trySend(msg) else rest.trySend(msg)
        if (sent) db.markAttempt(msg.localId, nextRetryAt = now() + backoff(msg.attemptCount))
        else      db.markAttempt(msg.localId, nextRetryAt = now() + backoff(msg.attemptCount))
    }
    awaitNextWake(timeout = 5.seconds)
}
```

Rules:

- **Always resend the same `clientMsgId`.** Generating a new UUID on retry creates a duplicate message. This is the single most important rule in this file.
- **Backoff:** 1s, 2s, 4s, 8s, 16s, 32s, capped at 60s.
- **Give up** after ~10 attempts or 24 hours → `FAILED`, surfaced to the user.
- **Only transmitted sends consume attempts.** An unreachable network (or a REST `404` while the
  fallback endpoint is unshipped) schedules a re-check without touching the attempt budget, so a
  message composed offline stays `PENDING` until connectivity returns. The 24h window runs from the
  first actual send attempt, and a manual retry resets both budgets.
- **A failed socket write is not an attempt** — fall through to REST in the same flush.
- **Permanent errors are not retried:** `PAYLOAD_TOO_LARGE`, `INVALID_ENVELOPE`, `DIALOG_NOT_FOUND` → straight to `FAILED`.
- **Fall back to REST when the socket is down.** Do not queue waiting for the socket — a user pressing send during a reconnect window should still succeed.
- Send in `created_at` order per dialog to preserve intent.

---

## 6. Applying an ack

```kotlin
fun onAck(ack: AckPayload) {
    db.transaction {
        val row = db.findByClientMsgId(ack.clientMsgId) ?: return@transaction  // unknown → ignore
        if (row.state == "SENT") return@transaction                            // duplicate ack → ignore
        db.promote(
            localId   = row.localId,
            serverId  = ack.messageId,
            createdAt = ack.createdAt,      // server time is authoritative
            state     = "SENT"
        )
    }
}
```

**Two things people get wrong here:**

1. **A duplicate ack is normal, not an error.** It happens whenever a retry raced a slow ack. Ignore it silently.
2. **The server's `createdAt` may differ from the local one**, which can move the message in the list. That is correct behaviour — server time is authoritative. Do not preserve the local timestamp to avoid the jump.

---

## 7. Applying inbound `message.new`

```kotlin
fun onMessageNew(m: MessageNewPayload) {
    db.transaction {
        if (db.existsByServerId(m.messageId)) return@transaction   // already have it

        // Own message echoed back from another device, or our own ack raced this frame
        val pending = m.clientMsgId?.let { db.findByClientMsgId(it) }
        if (pending != null) {
            db.promote(pending.localId, m.messageId, m.createdAt, "SENT")
            return@transaction
        }
        db.insertRemote(m)
    }
}
```

The middle branch matters: the same message can arrive as both an `ack` and a `message.new`. Without the `clientMsgId` check you get the message twice — once as the user's own pending row promoted, once as a fresh insert.

---

## 8. Connection lifecycle

```
        ┌─────────────┐
        │DISCONNECTED │◄──────────────┐
        └──────┬──────┘               │
               │ app foregrounded     │ socket closed / error
               ▼                      │
        ┌─────────────┐               │
        │ CONNECTING  │───────────────┤
        └──────┬──────┘  failure      │
               │ handshake ok         │
               ▼                      │
        ┌─────────────┐               │
        │  CATCHING_UP│───────────────┤
        └──────┬──────┘               │
               │ gaps fetched         │
               ▼                      │
        ┌─────────────┐               │
        │   LIVE      │───────────────┘
        └─────────────┘
```

**Reconnect backoff:** 1s, 2s, 4s, 8s, 16s, 30s cap. **Add jitter** (±20%) — without it, every client reconnects simultaneously after a server restart and stampedes the gateway.

**Reset the backoff on a successful connection**, not on a successful handshake attempt.

**Frames received during `CATCHING_UP` must be buffered, not dropped**, then applied after the catch-up merge. Otherwise a message arriving mid-catch-up is lost.

---

## 9. Catch-up

Runs after every successful connection, before going `LIVE`.

```kotlin
suspend fun catchUp() {
    for (dialog in db.dialogsWithLocalState()) {
        val cursor = db.syncState(dialog.id)?.newestSyncedId
        var after = cursor
        do {
            val page = api.messages(dialog.id, after = after, limit = 100)
            db.transaction { page.forEach { insertIfAbsent(it) } }
            after = page.lastOrNull()?.messageId
        } while (page.size == 100)          // keep paging while full pages come back
        db.updateSyncState(dialog.id, newestSyncedId = after ?: cursor)
    }
}
```

- **Page until a partial page returns.** A single request will not cover a user who was away for a week.
- **Deduplicate on `messageId`** — overlap with already-held messages is expected.
- If `newestSyncedId` is null (fresh install), load the most recent page instead of the whole history.
- Catch-up must be **idempotent and interruptible**. It runs on every reconnect, which on iOS is every foreground.

---

## 10. History pagination

Separate from catch-up. Catch-up fetches *newer*; pagination fetches *older*.

```kotlin
suspend fun loadOlder(dialogId: String) {
    val state = db.syncState(dialogId) ?: return
    if (!state.hasMoreHistory) return
    val page = api.messages(dialogId, before = state.oldestLoadedId, limit = 50)
    db.transaction {
        page.forEach { insertIfAbsent(it) }
        db.updateSyncState(dialogId,
            oldestLoadedId = page.lastOrNull()?.messageId ?: state.oldestLoadedId,
            hasMoreHistory = page.size == 50)
    }
}
```

Trigger when the user scrolls near the top. Guard against concurrent invocations — a fast scroll fires the trigger repeatedly and will otherwise issue duplicate requests.

---

## 11. Offline behaviour

None of this needs special handling, because the DB is the source of truth:

| Situation | Behaviour |
|---|---|
| Send while offline | Row inserted `PENDING`, renders immediately, flushed on reconnect |
| Read while offline | Local history renders normally |
| App killed with pending sends | Outbox survives; flushed on next launch |
| Offline for days | Catch-up pages through everything on reconnect |

**Do not add an "offline mode."** There is no mode. There is a database and a background process that occasionally succeeds at talking to a server.

---

## 12. Test checklist

Every one of these has a corresponding bug that ships without it:

- [ ] Retry after timeout, then late ack → exactly one message
- [ ] Same `messageId` delivered twice → one row
- [ ] `message.new` for a locally `PENDING` message → promoted, not duplicated
- [ ] App killed between DB write and send → message sent on next launch
- [ ] Reconnect with stale cursor → correct gap fetched, no duplicates
- [ ] Reconnect with null cursor → recent page loaded, not entire history
- [ ] Frames arriving during catch-up → buffered and applied, not lost
- [ ] Permanent error → `FAILED`, no retry loop
- [ ] 50 rapid sends → all delivered, order preserved
- [ ] Server `createdAt` differing from local → row reorders correctly
- [ ] Backoff has jitter
- [ ] Concurrent `loadOlder` calls → single request
