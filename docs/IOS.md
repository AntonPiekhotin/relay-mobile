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

### Push on iOS: what exists, and the one thing that blocks it

**Everything except APNs itself is built.** The scaffolding compiles, launches, and is wired:

| Piece | Where |
|---|---|
| `AppDelegate` — UNUserNotificationCenter delegate, remote-notification callbacks | `iosApp/iosApp/AppDelegate.swift` |
| `SharedBridge` — the whole Swift-facing surface (§8) | `iosMain/push/SharedBridge.kt` |
| `IosAppLifecycle` — foreground/background → `AppPresence` + socket | `iosMain/push/IosAppLifecycle.kt` |
| `IosNotificationPresenter` — local notification, tap carries `dialogId` | `iosMain/push/IosNotificationPresenter.kt` |
| `parsePushEvent`, `PushCoordinator`, `DeviceTokenRegistrar` | shared, platform `"ios"` bound in `Modules.ios.kt` |

**The blocker is the `aps-environment` entitlement, and it is not worth fighting.** Verified on
Xcode 26.2 against an iPhone 17 Pro simulator: with no entitlement,
`registerForRemoteNotifications` fails with *"no valid aps-environment entitlement string found"*
and **`didReceiveRemoteNotification` never fires — not even for `xcrun simctl push`.** Simulated
pushes are not a way around the paid Apple Developer Program. Injecting the entitlement by hand does
not work either: a command-line `CODE_SIGN_ENTITLEMENTS` is ignored for simulator builds, and
re-signing the built `.app` invalidates the embedded `Shared.framework` so the app refuses to launch
(`SBMainWorkspace` denies it). Both were tried; both are dead ends.

**And a second requirement stacks on top:** the backend addresses devices by **FCM token, not raw
APNs token** (`FcmPushSender` sends through Firebase with an `ApnsConfig`). So iOS also needs the
Firebase iOS SDK and a `GoogleService-Info.plist`, plus an APNs auth key uploaded to the Firebase
console. `Messaging.token` refuses to issue a registration token until an APNs token exists, so
there is nothing to send to `PUT /device-tokens` until the entitlement is real. Registering the bare
`deviceToken` from `didRegisterForRemoteNotificationsWithDeviceToken` will never deliver anything.

**What remains when the account is in place:** add FirebaseMessaging via SPM, enable the Push
Notifications capability, set `Messaging.messaging().apnsToken`, and swap `SharedBridge.registerApnsToken`
to forward the FCM token instead. The shared half needs no change.

Until then an iOS user who backgrounds the app receives nothing until they reopen it and catch up —
which is correct behaviour, not a bug, because delivery never depends on a push arriving.

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

**Not used yet, and phase 6 shipped without it.** Calls work while the app is foregrounded: the
socket is alive, the invite arrives as a frame, and the shared overlay rings. Everything below
applies the moment a backgrounded app must ring — which needs PushKit, which needs the same
`aps-environment` entitlement §1 is blocked on. Media, permissions and the audio session are already
built (`docs/CALLS.md` §5); only the wake-up path is missing.

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
│   ├── AppDelegate.swift     # push registration, RTC factory registration
│   ├── RelayRtc.swift        # WebRTC peer connection, conforms to the shared RtcClient
│   └── Info.plist
```

**WebRTC is a Swift package, not a pod.** `github.com/stasel/WebRTC` is referenced from
`iosApp.xcodeproj`; there is no `Podfile` and CocoaPods is not used. Xcode resolves it on first
build.

Gradle builds the shared framework as an Xcode build phase. `./gradlew :composeApp:embedAndSignAppleFrameworkForXcode` runs automatically — do not invoke it manually.

**Required `Info.plist` entries:**

| Key | Reason | Status |
|---|---|---|
| `UIBackgroundModes` → `remote-notification` | Background data pushes | **set** |
| `UIBackgroundModes` → `audio` | Call audio | **set** |
| `UIBackgroundModes` → `voip` | PushKit | blocked with CallKit (§4) |
| `NSMicrophoneUsageDescription` | Calls | **set** |
| `NSCameraUsageDescription` | Video calls | not until video ships |

`UIBackgroundModes` is a plist key and needs no paid account — unlike the **Push Notifications
capability**, which writes the `aps-environment` entitlement and does. Adding the background mode
without the entitlement is legal and useless on its own; see §1.

**Capabilities:** Push Notifications, Background Modes.

**Only one KMP framework per app is supported.** If the project is split into multiple shared modules, an umbrella module must re-export them.

---

## 8. Swift interop

With Compose Multiplatform the UI is Kotlin, so interop surface is small — mostly push tokens, call events, and lifecycle. Keep it that way. Expose one narrow bridge object rather than letting Swift reach into shared internals:

```kotlin
// iosMain
object SharedBridge {
    fun registerApnsToken(token: String) { ... }
    fun registerVoipToken(token: String) { ... }
    fun registerRtcFactory(factory: RtcClientFactory) { ... }
    fun onIncomingCallPush(callId: String, callerId: String, media: String, ringExpiresAt: String?)
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
