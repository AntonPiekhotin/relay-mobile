# iOS Platform Guide

Everything iOS-specific. The constraints here shape the whole client design — read §1 before writing any connection-related code.

---

## 1. The background constraint

**You cannot keep a WebSocket alive when the app is backgrounded.** iOS suspends the process within seconds of leaving the foreground. There is no entitlement, no background mode, and no trick that changes this for a chat app.

Consequences that must be designed for, not worked around:

1. **Push notifications are the primary delivery mechanism whenever the app is not foregrounded.** They are not an enhancement.
2. **Every foreground transition is a full reconnect + catch-up.** This will happen dozens of times a day. It must be fast and cheap.
3. **The UI must never depend on connection state.** It reads the local DB, which is always available. See `docs/ARCHITECTURE.md` §1.

```kotlin
// WRONG — will show an error banner constantly on iOS
if (!socket.isConnected) showBanner("No connection")
```

Show connection state only as a subtle, delayed indicator — if at all. A socket that is down for two seconds during a foreground transition is normal operation, not a problem worth telling the user about.

### Current client limitation

**The backend push path is built** — notification-service fans out to FCM (`docs/PROTOCOL.md` §5.5)
— and so is the shared client half (`push/`). **iOS is the part still missing.** Until it ships, an
iOS user who backgrounds the app receives nothing until they reopen it and catch up.

The catch: **the backend addresses devices by FCM token, not by raw APNs token.** `FcmPushSender`
sends through Firebase with an `ApnsConfig`, so an iOS device must register a *Firebase* registration
token — which means adding the Firebase iOS SDK to `iosApp` and a `GoogleService-Info.plist`, plus an
APNs auth key uploaded to the Firebase console. Registering the bare `deviceToken` from
`didRegisterForRemoteNotificationsWithDeviceToken` will not work; nothing will be delivered.

Everything above that line is already shared: `DeviceTokenRegistrar` (platform `"ios"` is already
bound in `Modules.ios.kt`), `parsePushEvent`, and `PushCoordinator`. The iOS work is the SDK, the
`AppDelegate`, and a bridge that calls `registrar.onFcmToken` / `onVoipToken`.

---

## 2. Lifecycle handling

Observe `UIApplication` notifications and drive the sync engine from them.

```kotlin
// iosMain
class IosLifecycleObserver(private val sync: SyncEngine) {
    fun start() {
        NSNotificationCenter.defaultCenter.addObserverForName(
            UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue
        ) { _ -> sync.onForeground() }

        NSNotificationCenter.defaultCenter.addObserverForName(
            UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue
        ) { _ -> sync.onBackground() }
    }
}
```

**`onBackground` must close the socket cleanly**, with a normal close frame. Do not just let it die. A clean close lets the server remove the session registry entry immediately instead of waiting for the 90-second TTL — during which it would route messages to a dead socket instead of a push.

**`onForeground` must:** reconnect → catch up → flush outbox, in that order.

Use `beginBackgroundTask` to buy a few seconds for the clean close and any pending DB writes. Do not attempt to use it to keep the socket alive; it grants seconds, not minutes.

---

## 3. Push notifications

Two distinct systems, do not conflate them:

| | Standard push (APNs) | VoIP push (PushKit) |
|---|---|---|
| Used for | New messages | Incoming calls |
| Token | `deviceToken` from `UIApplicationDelegate` | Separate token from `PKPushRegistry` |
| Wakes app | Only if user taps, or with content-available | Immediately, reliably |
| Obligation | None | **Must report to CallKit immediately** |

**The two tokens are different values from different APIs.** The backend `device_tokens` table has separate `fcm_token` and `voip_token` columns for exactly this reason. Register both, send both.

### Standard push

Register in Swift, pass the token into shared code:

```swift
func application(_ app: UIApplication,
                 didRegisterForRemoteNotificationsWithDeviceToken token: Data) {
    let hex = token.map { String(format: "%02x", $0) }.joined()
    SharedBridge.shared.registerPushToken(token: hex, kind: .apns)
}
```

Use `content-available: 1` for silent pushes that trigger a background fetch — but **do not rely on them**. iOS throttles silent pushes aggressively and may deliver them minutes late or not at all. They are an optimization, never a delivery guarantee.

---

## 4. CallKit — the strict part

*Relevant from phase 6 onward. Read before designing the call flow.*

**When a PushKit VoIP push arrives, you must report an incoming call to CallKit essentially immediately — within the same callback.** If you fail to, iOS terminates the app, and repeated failures cause the system to stop delivering VoIP pushes to your app entirely.

This is a hard platform rule with no workaround. Structure the code so reporting happens first, unconditionally, before any network work:

```swift
func pushRegistry(_ registry: PKPushRegistry,
                  didReceiveIncomingPushWith payload: PKPushPayload,
                  for type: PKPushType,
                  completion: @escaping () -> Void) {
    // FIRST — always, before anything else
    let update = CXCallUpdate()
    update.remoteHandle = CXHandle(type: .generic, value: payload.dictionaryPayload["callerName"] as? String ?? "Unknown")
    update.hasVideo = payload.dictionaryPayload["type"] as? String == "video"

    provider.reportNewIncomingCall(with: callUUID, update: update) { error in
        // ONLY NOW connect the socket and fetch call details
        SharedBridge.shared.onIncomingCallPush(payload: ...)
        completion()
    }
}
```

**Never** do network I/O, token refresh, or DB access before `reportNewIncomingCall`. If the call turns out to be stale or already cancelled, report it and then immediately end it — that is legal. Failing to report at all is not.

CallKit also owns: the system call UI, audio session activation, interaction with real phone calls, and the call history entry. Do not build a custom incoming-call screen on iOS.

---

## 5. Audio session

For calls, CallKit activates the audio session — do not activate it yourself. Configure the category ahead of time:

```swift
try AVAudioSession.sharedInstance().setCategory(
    .playAndRecord, mode: .voiceChat,
    options: [.allowBluetooth, .defaultToSpeaker])
```

Activation happens in `provider(_:didActivate:)`. Starting WebRTC audio before that callback produces silent calls that are painful to debug.

---

## 6. Keychain for tokens

JWTs go in the Keychain, never `NSUserDefaults`.

```kotlin
// iosMain — actual implementation of the TokenStore interface
class IosTokenStore : TokenStore {
    override suspend fun save(token: String) { /* SecItemAdd / SecItemUpdate */ }
    override suspend fun load(): String? { /* SecItemCopyMatching */ }
    override suspend fun clear() { /* SecItemDelete */ }
}
```

Use `kSecAttrAccessibleAfterFirstUnlock` so background pushes can read the token when the device is locked. `WhenUnlocked` will fail during a locked-device push and break call handling.

---

## 7. Xcode integration

```
iosApp/
├── iosApp.xcodeproj
├── iosApp/
│   ├── iOSApp.swift          # entry point, hosts the Compose view
│   ├── AppDelegate.swift     # push registration, PushKit, CallKit
│   ├── CallManager.swift     # CXProvider delegate
│   └── Info.plist
└── Podfile                   # only if using webrtc-kmp
```

Gradle builds the shared framework as an Xcode build phase. `./gradlew :composeApp:embedAndSignAppleFrameworkForXcode` runs automatically — do not invoke it manually.

**Required `Info.plist` entries:**

| Key | Reason |
|---|---|
| `NSMicrophoneUsageDescription` | Calls |
| `NSCameraUsageDescription` | Video calls |
| `UIBackgroundModes` → `voip`, `remote-notification`, `audio` | PushKit and call audio |

**Capabilities:** Push Notifications, Background Modes.

**Only one KMP framework per app is supported.** If the project is split into multiple shared modules, an umbrella module must re-export them.

---

## 8. Swift interop

With Compose Multiplatform the UI is Kotlin, so interop surface is small — mostly push tokens, call events, and lifecycle. Keep it that way. Expose one narrow bridge object rather than letting Swift reach into shared internals:

```kotlin
// iosMain
object SharedBridge {
    fun registerPushToken(token: String, kind: String) { ... }
    fun onIncomingCallPush(callId: String, callerId: String) { ... }
    fun onCallAnswered(callId: String) { ... }
    fun onCallEnded(callId: String) { ... }
}
```

Gotchas when Swift consumes Kotlin: generics flatten through the Objective-C header, sealed classes become awkward, and `suspend` functions become completion handlers. If the Swift surface ever grows beyond a handful of functions, add **SKIE** — it maps sealed classes to Swift enums and `Flow` to `AsyncSequence`.

---

## 9. iOS test checklist

- [ ] Background → foreground: socket closes cleanly, reconnects, catches up
- [ ] Force-quit with pending sends → flushed on next launch
- [ ] Airplane mode → send queues, renders `PENDING`, flushes on restore
- [ ] Locked device + push → token readable from Keychain
- [ ] VoIP push → CallKit reported before any network work
- [ ] Incoming call while a phone call is active → CallKit handles correctly
- [ ] Rapid background/foreground cycling → no duplicate connections, no leaked observers
- [ ] Low memory termination → state recovered from DB
