# UI Guide — Compose Multiplatform

Conventions for the shared UI layer. Compose Multiplatform runs the same composables on Android and iOS.

---

## 1. Rules

1. **Composables never touch the network, the DB, or the sync engine.** They observe a `StateFlow` from a ViewModel. Nothing else.
2. **State flows down, events flow up.** Composables take state and lambdas; they do not own business logic.
3. **No platform checks in shared UI.** No `if (isAndroid)`. If behaviour genuinely differs, inject a platform implementation.
4. **Every list is keyed.** Unkeyed `LazyColumn` items produce wrong animations and lost scroll position.
5. **Preview-able components.** Composables should render from plain parameters, so they work without a running app.

```kotlin
// WRONG
@Composable fun ChatScreen(dialogId: String) {
    val messages = remember { mutableStateOf(emptyList<Message>()) }
    LaunchedEffect(Unit) { messages.value = api.getMessages(dialogId) }  // NO
}

// RIGHT
@Composable
fun ChatScreen(vm: ChatViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    ChatContent(state = state, onSend = vm::send, onRetry = vm::retry)
}
```

---

## 2. State modelling

One immutable data class per screen. Not multiple loose flows the composable has to combine.

```kotlin
data class ChatState(
    val messages: List<MessageUi> = emptyList(),
    val isLoadingOlder: Boolean = false,
    val hasMoreHistory: Boolean = true,
    val draft: String = "",
    val connection: ConnectionUi = ConnectionUi.Unknown,
    val error: String? = null
)

data class MessageUi(
    val localId: Long,
    val text: String,
    val isMine: Boolean,
    val timestamp: String,
    val status: MessageStatus     // PENDING | SENT | FAILED
)
```

`MessageStatus` comes straight from the DB `state` column — do not track sending state separately in memory. A message killed mid-send must still render as `PENDING` after relaunch, which only works if the DB is the source.

ViewModel maps DB rows to UI models. **Do the mapping in the ViewModel, not the composable** — formatting timestamps inside a `LazyColumn` item runs on every recomposition.

---

## 3. Connection indicator

Per `docs/IOS.md` §1, the socket drops constantly on iOS. Do not surface every disconnect.

```kotlin
sealed interface ConnectionUi {
    data object Unknown : ConnectionUi     // show nothing
    data object Live : ConnectionUi        // show nothing
    data object Reconnecting : ConnectionUi // show only after a delay
}
```

Show a "connecting…" strip only after the disconnect has lasted **more than ~3 seconds**. Anything faster is normal operation and a flashing banner makes the app feel broken.

Never block the UI on connection state. Messages send while offline; that is the design.

---

## 4. Chat list specifics

The message list is the hardest UI component. Get these right:

```kotlin
LazyColumn(
    reverseLayout = true,                       // newest at bottom, natural chat scrolling
    state = listState
) {
    items(
        items = state.messages,
        key = { it.localId }                    // stable local id, NOT server id
    ) { msg -> MessageBubble(msg) }

    if (state.hasMoreHistory) {
        item { LaunchedEffect(Unit) { onLoadOlder() }; LoadingRow() }
    }
}
```

**Key on `localId`, not `serverId`.** The server ID is null until the ack arrives, so keying on it makes every pending message change identity when acked — causing a visible flicker and animation glitch.

**`reverseLayout = true`** gives correct chat behaviour: new messages appear at the bottom, and the list stays anchored there while loading older content at the top.

**Auto-scroll only when already at the bottom.** Scrolling a user away from history they are reading is a common and infuriating bug:

```kotlin
val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
LaunchedEffect(state.messages.firstOrNull()?.localId) {
    if (atBottom) listState.animateScrollToItem(0)
}
```

**Pagination trigger must be debounced.** A fast scroll fires the `LaunchedEffect` repeatedly; guard in the ViewModel with an `isLoadingOlder` flag.

---

## 5. Navigation

Use a Compose Multiplatform–compatible navigation library (Navigation Compose with KMP support, or Voyager/Decompose). Define routes as a sealed hierarchy, never raw strings:

```kotlin
sealed interface Route {
    data object DialogList : Route
    data class Chat(val dialogId: String) : Route
    data object People : Route
    data object Profile : Route
    data class Call(val callId: String) : Route
}
```

The dialog list is the only top-level screen. Its app bar carries two icon actions — a search glyph
opening `People` (which lands on the search tab; that screen is a finder first and a contact list
second) and an account glyph opening `Profile`. **Log out lives on the profile screen, nowhere else.**
Profile is read-only for now: name, email, member-since, and the logout button. Editing is not built.

Deep links matter: tapping a notification must open the right conversation. Route resolution has to work from a cold start, where the DB may not yet be populated — handle the "dialog not found locally yet" case by fetching it.

---

## 6. Theming

One `Theme.kt` in `commonMain`. Material 3.

```kotlin
@Composable
fun RelayTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = RelayTypography,
        content = content
    )
}
```

Use the Compose Multiplatform resources API for images, strings, and fonts — assets no longer need manual copying into Xcode.

**Platform-idiomatic differences worth honouring:** iOS users expect edge-swipe back navigation and different default transitions. Handle these in navigation configuration rather than scattering platform checks through composables.

---

## 7. Performance

- **Hoist `derivedStateOf`** for anything computed from scroll position, or you recompose on every pixel
- **`key` every list item** — non-negotiable in a chat
- **Avoid lambda allocation in item scope** — hoist callbacks out of `items { }`
- **No `Modifier` construction inside items** — build once, reuse
- Keep `MessageBubble` cheap; it renders hundreds of times
- Format timestamps in the ViewModel, not the composable

Compose on iOS renders via Skia onto a canvas rather than mapping to UIKit views. Performance is close to native, but heavy recomposition costs more than on Android — profile the message list specifically.

---

## 8. Component inventory

Build these as standalone, previewable composables:

| Component | Notes |
|---|---|
| `MessageBubble` | Own vs. other, status icon, timestamp, reply preview |
| `MessageList` | `LazyColumn`, reverse layout, pagination trigger |
| `Composer` | Text field, send button, draft persistence |
| `DialogRow` | Avatar, title, last message, unread badge, timestamp |
| `DialogList` | `LazyColumn` of `DialogRow` |
| `Avatar` | Initials in a circle; `size` and `textStyle` are parameters |
| `SearchGlyph` / `AccountGlyph` | App-bar icons, drawn on a `Canvas` — Material icon artifacts are not on the classpath |
| `ConnectionStrip` | Delayed reconnect indicator |
| `EmptyState` | No dialogs / no messages |
| `RetryChip` | Attached to `FAILED` messages |

Each takes plain data and lambdas — no ViewModel, no DI, no side effects. That makes them previewable and testable in isolation.

---

## 9. UI test checklist

- [ ] Sending renders instantly as `PENDING`, before any network response
- [ ] Ack flips to `SENT` without the row jumping or flickering
- [ ] `FAILED` message shows retry, and retry works
- [ ] Scrolling up loads older messages exactly once per trigger
- [ ] New message while scrolled up does **not** yank the view to the bottom
- [ ] New message while at the bottom scrolls smoothly
- [ ] Rotation / resize preserves scroll position
- [ ] Dark mode renders correctly on both platforms
- [ ] Empty states render
- [ ] Very long messages and unbroken strings wrap without breaking layout
