# Client Architecture

How the mobile client is structured, how data flows, and where each kind of code belongs.

---

## 1. The governing idea

**The client mirrors the server's core principle.** On the server, Postgres is the source of truth and the WebSocket is a fast path. On the client, **SQLDelight is the source of truth and the network is a fast path.**

Everything follows from this:

- The UI observes the database and nothing else
- The network layer writes to the database and nothing else
- Offline behaviour is not a feature — it is the default, because the UI never knew about the network to begin with
- Socket state cannot break the UI, which matters enormously on iOS where the socket dies constantly

```
┌──────────────────────────────────────────┐
│  UI (Compose)                            │  observes StateFlow
└───────────────┬──────────────────────────┘
                │
┌───────────────▼──────────────────────────┐
│  ViewModel                               │  maps DB Flow → UI state
└───────────────┬──────────────────────────┘
                │
┌───────────────▼──────────────────────────┐
│  Repository                              │  the ONLY thing UI may call
└───────┬──────────────────────────┬───────┘
        │ reads/writes             │ enqueues intent
┌───────▼──────────┐    ┌──────────▼───────┐
│  Local DB        │◄───│  Sync Engine     │  see docs/SYNC.md
│  (SQLDelight)    │    │  socket + REST   │
└──────────────────┘    └──────────────────┘
                                   │
                            ┌──────▼──────┐
                            │  Backend    │
                            └─────────────┘
```

**Data flows one way into the UI: DB → ViewModel → Composable.** Nothing else may reach the UI.

---

## 2. Module layout

Start as one module. Split when it hurts, not before.

```
relay-mobile/
├── composeApp/
│   ├── src/
│   │   ├── commonMain/kotlin/dev/relay/
│   │   │   ├── protocol/      # envelope, frames, serialization
│   │   │   ├── network/       # Ktor HTTP + WebSocket clients
│   │   │   ├── db/            # SQLDelight schema (.sq) + DAOs
│   │   │   ├── sync/          # sync engine, outbox, catch-up
│   │   │   ├── repository/    # the UI-facing API
│   │   │   ├── auth/          # token storage, refresh
│   │   │   ├── ui/            # composables, theme, navigation
│   │   │   └── di/            # Koin modules
│   │   ├── androidMain/       # FCM, foreground service, platform impls
│   │   └── iosMain/           # PushKit bridge, lifecycle, platform impls
│   └── build.gradle.kts
├── iosApp/                    # Xcode project — Swift entry, CallKit, PushKit
├── docs/
└── gradle/libs.versions.toml
```

**When you do split**, remember iOS supports only one KMP framework per app. You need an umbrella module that re-exports the others. Plan for it rather than discovering it after splitting into ten modules.

---

## 3. Layer rules

| Layer | May depend on | Must never |
|---|---|---|
| `ui` | `repository`, `di` | Touch `network`, `db`, or `sync` directly |
| `repository` | `db`, `sync` | Expose Ktor or SQLDelight types outward |
| `sync` | `network`, `db`, `protocol` | Know anything about the UI |
| `network` | `protocol` | Write to the DB directly |
| `db` | — | Depend on anything above it |
| `protocol` | — | Depend on anything |

**Repository is the boundary.** It returns domain models and `Flow`s. It never leaks `HttpResponse`, `Envelope`, or generated SQLDelight row types.

```kotlin
// RIGHT
interface MessageRepository {
    fun observeMessages(dialogId: String): Flow<List<Message>>
    suspend fun send(dialogId: String, text: String)   // returns Unit — result arrives via the Flow
    suspend fun loadOlder(dialogId: String)
}
```

Note that `send` returns `Unit`. The caller does not await a result — it writes to the outbox and the UI updates through the observed Flow. This is what makes optimistic rendering fall out naturally.

---

## 4. Concurrency

- **`Dispatchers.Default`** for parsing, mapping, business logic
- **`Dispatchers.IO`** for DB and network (on iOS this maps to a background queue)
- **`Dispatchers.Main`** for UI only
- SQLDelight queries: use `.asFlow().mapToList(Dispatchers.IO)`

**Scopes:** the sync engine owns a long-lived `CoroutineScope(SupervisorJob() + Dispatchers.Default)`. ViewModels use `viewModelScope`. Never launch into `GlobalScope`.

**Kotlin/Native memory:** the modern memory manager is the only supported model — the old freezing rules are gone. But watch for retain cycles in singletons held from iOS, since ARC cannot break Kotlin-side cycles.

```kotlin
// WRONG — blocks a thread, deadlocks on iOS main queue
fun getMessages() = runBlocking { repo.load() }

// WRONG — leaks, never cancelled
GlobalScope.launch { syncForever() }
```

---

## 5. Dependency injection

Koin, with a `commonMain` module plus platform modules for `expect`/`actual` implementations.

```kotlin
val commonModule = module {
    single { HttpClientFactory.create(get()) }
    single<MessageRepository> { MessageRepositoryImpl(get(), get()) }
    single { SyncEngine(get(), get(), get()) }
    factory { ChatViewModel(get(), get()) }
}

// androidMain
actual val platformModule = module {
    single<SqlDriver> { AndroidSqliteDriver(RelayDb.Schema, get(), "relay.db") }
    single<TokenStore> { AndroidTokenStore(get()) }   // EncryptedSharedPreferences
}

// iosMain
actual val platformModule = module {
    single<SqlDriver> { NativeSqliteDriver(RelayDb.Schema, "relay.db") }
    single<TokenStore> { IosTokenStore() }            // Keychain
}
```

**Prefer DI over `expect`/`actual`.** An interface implemented per platform and injected is easier to test and read than an `expect` declaration. Reserve `expect`/`actual` for cases where you need a platform *type*, not just platform *behaviour*.

---

## 6. Error handling

Errors are values in the domain layer, not exceptions crossing layers.

```kotlin
sealed interface SendFailure {
    data object Offline : SendFailure
    data object RateLimited : SendFailure
    data class Rejected(val code: String) : SendFailure
    data object Permanent : SendFailure
}
```

The repository never throws for expected conditions. Network unavailability is not an error — it is the normal state of a mobile app, and the outbox handles it. Reserve exceptions for programmer errors.

**Message state is visible in the DB**, so the UI shows "sending / sent / failed" by observing rows, not by catching exceptions.

---

## 7. Testing

| What | Where | How |
|---|---|---|
| Protocol serialization | `commonTest` | Round-trip every frame type |
| Sync engine | `commonTest` | Fake socket + in-memory SQLDelight driver |
| Outbox and retry | `commonTest` | Virtual time via `runTest` |
| Repository | `commonTest` | In-memory DB |
| ViewModels | `commonTest` | Turbine on the Flows |
| UI | `androidTest` | Compose test rule |
| Platform glue | `androidTest` / `iosTest` | Real device concerns |

**The sync engine is the highest-value test target.** It is where the subtle bugs live: duplicate messages, lost acks, out-of-order merges, retry storms. Test these explicitly:

- Ack arrives after timeout and a retry was already sent → exactly one message
- Same `messageId` delivered twice → one row
- `message.new` arrives for a message still `PENDING` locally → merged, not duplicated
- Reconnect with a stale cursor → correct gap fetched
- Socket drops mid-send → message stays `PENDING`, resent on reconnect

Use an in-memory SQLDelight driver so DB tests run in `commonTest` on all platforms.

```kotlin
@Test
fun `retry after timeout does not duplicate`() = runTest {
    val id = engine.send(dialogId, "hi")
    advanceTimeBy(6.seconds)          // timeout fires, retry sent
    socket.emitAck(clientMsgId = id, messageId = "srv-1")
    assertEquals(1, db.messageCount())
}
```

---

## 8. Conventions

- Explicit visibility on public API (`internal` by default inside modules)
- No `!!`. Use `requireNotNull` with a message, or handle the null
- Data classes for models; sealed interfaces for state
- `Flow` for streams, `suspend` for one-shots — never callbacks in `commonMain`
- Timestamps are `Long` epoch millis everywhere, converted only at the display edge
- IDs are `String` (UUIDs) — do not parse them into typed UUID objects at the boundary
