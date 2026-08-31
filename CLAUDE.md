# Relay Mobile — Agent Guide

Kotlin Multiplatform + Compose Multiplatform client for the Relay messenger (Android + iOS).
Backend is a Kotlin/Spring Web microservice system; the client talks to it over REST (history, profile) and WebSocket (real-time send/receive).

**This file is always in context. Keep it under 150 lines.** Detail lives in `docs/`.

---

## Invariants — never violate, regardless of the task

1. **The local database is the source of truth for the UI.** Composables and ViewModels read from SQLDelight, never from network responses directly. Network writes to the DB; the UI observes the DB.
2. **Every outgoing message gets a client-generated UUID (`clientMsgId`) before anything else happens.** Never let the server generate it. It is the idempotency key for retries.
3. **Writes go to the local outbox before any network call.** The message must render instantly and survive app death.
4. **Retries reuse the same `clientMsgId`.** Never generate a new one for a retry — that creates duplicate messages.
5. **The socket is a fast path, never a guarantee.** Any code that assumes a message arrived over the socket is wrong. Catch-up via REST is the correctness mechanism.
6. **iOS suspends the socket when backgrounded.** Never write logic that assumes a persistent connection across app lifecycle. See `docs/IOS.md`.
7. **The wire protocol is versioned and shared with the backend.** Never change an existing frame's shape. Add a new `type`, or bump `v`. See `docs/PROTOCOL.md`.
8. **No blocking calls in coroutines.** No `runBlocking` outside tests, no blocking I/O on `Dispatchers.Main`.
9. **No platform code in `commonMain`.** Use `expect`/`actual` only when genuinely needed — prefer passing a platform implementation in via DI.
10. **Never log tokens, message text, or user identifiers** at INFO or above.

## Anti-patterns — reject these on sight

```kotlin
// WRONG — UI hitting network directly
@Composable fun ChatScreen() { val msgs = api.getMessages(dialogId) }

// WRONG — server-assigned ID for a new send
val id = api.send(text).messageId

// WRONG — new UUID on retry
retry { send(UUID.randomUUID(), text) }

// WRONG — assumes socket is alive
if (socket.isConnected) showMessages() else showError()
```

---

## Routing — read these before working on the matching area

| Task | Read |
|---|---|
| Wire format, frames, REST endpoints, error codes | `docs/PROTOCOL.md` |
| Module layout, layering, DI, testing | `docs/ARCHITECTURE.md` |
| Outbox, ack handling, catch-up, DB schema, offline | `docs/SYNC.md` |
| Call signaling, WebRTC, the call state machine | `docs/CALLS.md` |
| iOS lifecycle, PushKit, CallKit, Xcode | `docs/IOS.md` |
| Android service, FCM, Doze, permissions | `docs/ANDROID.md` |
| Composables, navigation, theming, state | `docs/UI.md` |
| Toolchain, project setup, build config | `docs/SETUP.md` |

When a task spans areas, read all matching files. When unsure whether behaviour is client or server responsibility, read `docs/PROTOCOL.md` first, then ask rather than guessing.

---

## Commands

```bash
./gradlew :androidApp:assembleDebug          # Android build
./gradlew :shared:iosSimulatorArm64Test      # iOS unit tests
./gradlew allTests                           # all common + platform tests
./gradlew :shared:check                      # tests + schema-migration verification
./gradlew :shared:generateCommonMainRelayDbInterface   # after .sq changes
./gradlew :shared:generateCommonMainRelayDbSchema      # after a migration, refresh the snapshot
```

iOS app is built from Xcode: open `iosApp/iosApp.xcodeproj`. Gradle builds the shared framework as a build phase.

## Changing the database schema

**Editing a `.sq` table definition is never enough.** The schema version is derived from the number
of `.sqm` files, so a changed table with no new migration keeps the old version: existing installs
never upgrade, `create()` is never re-run, and the app crashes on the first query touching the new
column or table. Two shipped crashes came from exactly this.

Every schema change needs: the `.sq` edit, a new `<n>.sqm` migrating the previous version, and a
regenerated snapshot in `src/commonMain/sqldelight/databases/`. `verifyMigrations` is on, so
`./gradlew :shared:check` fails when they disagree — **`allTests` does not run that check.**

**A `.sqm` file only sees tables created by earlier `.sqm` files, never the `.sq` ones.** That is why
`0.sqm` exists: it restates the original phase-2 tables so later migrations can `ALTER` them. It runs
only for a version-0 database, which cannot exist — fresh installs go through `create()`. Keep it in
sync with the real v1 shape, and never renumber it: the schema version is `max(<n>.sqm) + 1`.

---

## Current phase

Build order — do not skip ahead, each phase depends on the previous:

- [x] **1. Shared core** — protocol models, Ktor WebSocket client, auth, connect to gateway from both platforms
- [x] **2. Local DB + sync engine** — outbox, ack handling, catch-up. *The hard part. Get it right before any UI.*
- [x] **3. Compose UI** — dialog list, chat screen, composer. Also: theme, Navigation Compose, people search/contacts.
- [ ] **4. Push notifications** — FCM + APNs, native both sides.
      **Android done and verified end to end.** iOS scaffolding done — bridge, lifecycle,
      notification presenter, `AppDelegate` — but **blocked on a paid Apple Developer account**:
      without the `aps-environment` entitlement no remote push is delivered, not even via
      `simctl push`. See `docs/IOS.md` §1 before touching it.
- [x] **5. Presence / typing** — subscribe-on-demand per open chat (`PresenceEngine`, ephemeral,
      no DB). Header subtitle shows online / last seen / typing; typing sends are throttled to
      1 per 3s and the indicator expires client-side after 5s. See `docs/PROTOCOL.md` §4.2.
- [x] **6. Calls** — 1:1 audio, signaling + WebRTC media on both platforms. **Foreground only:**
      no CallKit and no ConnectionService, so iOS cannot ring a backgrounded app (same
      `aps-environment` blocker as phase 4) and Android rings via a full-screen intent. Read
      `docs/CALLS.md` before touching any of it.
- [x] **7. Group calls** — audio, REST-driven control (`GroupCallEngine` beside `CallEngine`),
      media through the LiveKit SFU via the `SfuClient` platform port (livekit-android /
      client-sdk-swift). Entry: the Groups tab in the dock. Same foreground-only caveats as
      phase 6. See `docs/CALLS.md` §8 and `docs/PROTOCOL.md` §4.5.

## Backend reality check

The backend is ahead of the client in some areas and behind in others. Current backend state:

- **Implemented:** WebSocket send/ack over Kafka, real-time delivery to connected clients, auth (login/register/refresh), call signaling, group calls (REST `/api/v1/call/group-calls` + LiveKit SFU + webhooks — `docs/PROTOCOL.md` §4.5), presence/typing (`presence.subscribe`/`unsubscribe`, `presence.update`, `typing.start` — see `docs/PROTOCOL.md` §4.2), call-log / ICE-server / device-token REST endpoints.
- **Implemented (client-facing REST):** profile/search/contacts (`/api/v1/user/**`), and `POST /api/v1/message/dialogs` — opening the direct dialog with a peer, the only way a client obtains a dialog id.
- **Implemented (push):** notification-service consumes the `notifications` topic and fans out to FCM
  (`FcmPushSender`, behind `relay.push.fcm.enabled`). Device tokens register through
  `PUT /api/v1/notification/device-tokens`. Payloads are camelCase `data` keys with a `kind` of
  `MESSAGE_NEW`, `INCOMING_CALL`, or `MISSED_CALL` — see `docs/PROTOCOL.md` §5.5.
- **NOT implemented:** REST fallback send (still `/internal`-only).

**Consequence:** the client's REST fallback send is built against the target contract in
`docs/PROTOCOL.md` §5.2 and stays inert until the backend ships it — do not report its `404`s as
client bugs. A dialog opened via `POST /api/v1/message/dialogs` is stored locally on creation,
because that local row is the record the client has that the conversation exists before catch-up
runs.

**Push is the server's job to send and the client's job to survive without.** A push is a hint that
something changed, never the message itself: the payload carries ids, and the client catches up over
REST (`PushCoordinator`). A device that receives no push still converges on next foreground. Never
make delivery depend on a push arriving.
