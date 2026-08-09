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
| iOS lifecycle, PushKit, CallKit, Xcode | `docs/IOS.md` |
| Android service, FCM, Doze, permissions | `docs/ANDROID.md` |
| Composables, navigation, theming, state | `docs/UI.md` |
| Toolchain, project setup, build config | `docs/SETUP.md` |

When a task spans areas, read all matching files. When unsure whether behaviour is client or server responsibility, read `docs/PROTOCOL.md` first, then ask rather than guessing.

---

## Commands

```bash
./gradlew :composeApp:assembleDebug          # Android build
./gradlew :composeApp:iosSimulatorArm64Test  # iOS unit tests
./gradlew allTests                           # all common + platform tests
./gradlew ktlintCheck detekt                 # lint
./gradlew :composeApp:generateSqlDelightInterface   # after .sq changes
```

iOS app is built from Xcode: open `iosApp/iosApp.xcodeproj`. Gradle builds the shared framework as a build phase.

---

## Current phase

Build order — do not skip ahead, each phase depends on the previous:

- [x] **1. Shared core** — protocol models, Ktor WebSocket client, auth, connect to gateway from both platforms
- [x] **2. Local DB + sync engine** — outbox, ack handling, catch-up. *The hard part. Get it right before any UI.*
- [ ] **3. Compose UI** — dialog list, chat screen, composer
- [ ] **4. Push notifications** — FCM + APNs, native both sides
- [ ] **5. Presence / typing**
- [ ] **6. Calls** — shared signaling, native CallKit/ConnectionService

## Backend reality check

The backend is ahead of the client in some areas and behind in others. Current backend state:

- **Implemented:** WebSocket send/ack over Kafka, real-time delivery to connected clients, auth (login/register/refresh), call signaling, call-log / ICE-server / device-token REST endpoints.
- **NOT implemented:** client-facing REST history, dialog list, and fallback send (message-service exposes `/internal`-only endpoints the gateway does not route), notification delivery, FCM push, presence, typing, online/offline split.

**Consequence:** the client's REST catch-up, history pagination, and fallback send are built against the target contract in `docs/PROTOCOL.md` §5 (`/api/v1/message/**`) and stay inert until the backend ships those endpoints — do not report their `404`s as client bugs. Until the backend notification service exists, a user who is offline receives nothing until they reopen the app and catch up. Do not build client logic that expects push delivery before phase 4. Do not report the absence of push as a client bug.
