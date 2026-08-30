# Android Platform Guide

Android is more permissive than iOS about background work, but not as permissive as it looks. Assume every long-lived connection will eventually be killed.

---

## 1. Background execution

Unlike iOS, Android *can* hold a socket in the background — but only with a foreground service, and even then vendor battery managers interfere.

| Mechanism | Survives | Notes |
|---|---|---|
| Plain background coroutine | Seconds to minutes | Killed by Doze and the OS at will |
| Foreground service | Hours | Requires a persistent notification |
| FCM high-priority push | Always | Wakes the app even in Doze |

**Design rule: treat Android like iOS by default.** The socket may die at any moment; FCM is the reliable delivery path. Foreground service is an optimization for when the user is actively chatting, not a substitute for push.

This keeps the two platforms behaviourally aligned, which means one sync engine rather than two subtly different ones.

### Doze and vendor killers

Doze batches network access when the device is idle. High-priority FCM messages bypass it; nothing else reliably does.

Some manufacturers (Xiaomi, Huawei, Oppo, OnePlus, Samsung to a lesser degree) kill background processes far more aggressively than stock Android, sometimes ignoring foreground services. **Do not attempt to defeat this.** Depend on FCM and make reconnect-plus-catch-up fast.

---

## 2. Foreground service

Run one only while the user is actively in a conversation, and stop it when they leave.

```kotlin
class SocketService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        syncEngine.connect()
        return START_STICKY
    }
}
```

Manifest:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.INTERNET" />

<service android:name=".SocketService"
         android:foregroundServiceType="dataSync"
         android:exported="false" />
```

**On Android 14+, `foregroundServiceType` is mandatory** and the declared type must match actual usage. `dataSync` for the socket; `microphone|camera` for calls.

Use a low-importance notification channel so the persistent notification is silent and collapsed.

---

## 3. FCM

**Built.** `androidApp/src/main/kotlin/com/relay/push/` holds the Android half; the decisions live in
`shared` so iOS reuses them. The wire contract is `docs/PROTOCOL.md` §5.5.

| Piece | Where | Does |
|---|---|---|
| `RelayMessagingService` | androidApp | receives the push, hands it to the shared coordinator |
| `MessageNotifier` | androidApp | builds the notification, tap → `MainActivity` with `dialogId` |
| `NotificationChannels` | androidApp | `messages` (default) and `calls` (high) |
| `parsePushEvent` | shared `push/` | payload map → typed `PushEvent`, unknown kinds → `Unknown` |
| `PushCoordinator` | shared `push/` | catch up over REST, then decide notify-or-suppress |
| `DeviceTokenRegistrar` | shared `push/` | registers the token, retries, unregisters on logout |
| `PushStore` | shared `db/` | the device id and tokens, in the `push_device` table |

**Firebase config is required to build.** `androidApp/google-services.json` from the Firebase console
(project `relay-a7798`, package `com.relay`). Without it `processDebugGoogleServices` fails.

**`onNewToken` is not enough.** It fires only when the token *rotates*, so `RelayApplication` also
asks `FirebaseMessaging.getToken()` at startup and feeds the registrar. A device that never rotated
would otherwise never register.

**A token can arrive before login.** `PushStore` writes it regardless and `DeviceTokenRegistrar`
sends it when `AuthState` becomes `LoggedIn` — the registration is keyed on
`(user, fcmToken, voipToken)`, so an unchanged registration costs no request and a rotated token
re-registers.

**Payload should carry minimal data.** Send identifiers, not content — then catch up over REST. This avoids notification payload size limits, keeps message text out of Google's infrastructure, and guarantees the local DB is the source of truth.

**Use `priority: high`** for message and call pushes. Normal priority is deferred in Doze and may arrive hours late.

**Token refresh:** FCM rotates tokens. `onNewToken` can fire at any time, including before login. Persist locally and re-register after authentication.

---

## 4. Notification handling

Do not post a notification for a message that is already on screen. `AppPresence` tracks both facts —
`MainActivity.onStart`/`onStop` set foreground, `ChatViewModel` sets the open dialog in `init` and
clears it in `onCleared` — and `PushCoordinator` checks them:

```kotlin
if (presence.isShowing(event.dialogId)) return PushDisplay.Suppress
```

This mirrors the server's socket-XOR-push rule at the client level, and catches the race where a push arrives just as the user opens the chat.

**Catch up first, decide second.** `PushCoordinator` runs the REST catch-up *before* the suppression
check, so an open chat still gets the message even though no notification is posted.

Channels: separate channels for messages (default importance) and calls (high importance), so users
can configure them independently. **The call channel is deliberately silent** — `IncomingCallRinger`
plays the ringtone itself, because a channel sound plays once and cannot loop.

**A channel's sound and importance are frozen at creation.** Changing them in code does nothing on a
device that already has the channel, and deleting and recreating the same id restores the user's old
settings. A behaviour change needs a *new* id; `calls` became `calls_v2` for exactly this reason, and
`NotificationChannels.ensure` deletes the stale id so it does not linger in system settings.

---

## 5. Incoming calls

**Built, with the full-screen intent, not `ConnectionService`.** The call logic lives in
`docs/CALLS.md` §4; this section is the Android-specific part of it.

`ConnectionService` remains the better answer for a real product — system dialer integration,
interaction with cellular calls, an entry in the system call log — and is the upgrade path. It was
not taken here because it is substantially more setup for behaviour a full-screen intent already
covers on a device that is awake.

```kotlin
val notification = NotificationCompat.Builder(context, CALL_CHANNEL_ID)
    .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, declineIntent, answerIntent))
    .setFullScreenIntent(pendingIntent, true)
    .setCategory(NotificationCompat.CATEGORY_CALL)
    .setOngoing(true)
    .build()
```

`CallStyle` is what makes the system render this as a call rather than a message: Answer / Decline
buttons, caller `Person`, call ranking in the shade. It matters most when the full-screen intent does
*not* fire, because the heads-up is then the only affordance the user gets. Answer opens
`CallActivity` with `EXTRA_ANSWER_CALL` — accepting needs `RECORD_AUDIO`, and the prompt needs an
activity. Decline goes to `CallActionReceiver`, which needs none.

On Android 14+ the full-screen intent requires `USE_FULL_SCREEN_INTENT`, granted at install only to
calling and alarm apps. Sideloaded builds do not get it, and the ring silently degrades to a
heads-up. `FullScreenIntentAccess` reports whether it is held; `MainActivity` sends the user to
`ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` once per install when it is not, and `CallNotifier` logs
the degrade so it is visible in logcat rather than mysterious.

Unlike iOS, Android imposes no obligation to report the call within a deadline. The constraint is only that a high-priority FCM push must arrive.

**A push is not the only trigger.** The gateway pushes only when the callee is unreachable, so a
backgrounded app that still holds its socket receives the invite as a frame and no push.
`RelayApplication` watches the call session and posts the same notification whenever a call reaches
`INCOMING` while `AppPresence` reports the app is not foregrounded.

**The ring is the app's own, not the channel's.** `IncomingCallRinger` loops the user's default
ringtone through a `MediaPlayer` with `USAGE_NOTIFICATION_RINGTONE`, vibrates on a repeating pattern,
and takes transient audio focus. It follows `AudioManager.ringerMode` — silent rings nothing, vibrate
skips the tone — and stops itself after 60s in case a session never settles. It is driven purely by
call-session state in `RelayApplication`, so it also rings while the app is foregrounded, where the
in-app overlay shows and no notification is posted.

**Outgoing calls get a ringback.** `OutgoingRingbackTone` plays `TONE_SUP_RINGTONE` on
`STREAM_VOICE_CALL` for the `DIALING` and `RINGING` stages of a direct call, so the caller hears the
standard 2s-on / 4s-off tone through whatever the call is routed to. See `docs/CALLS.md` §4.

**An answered call needs a `microphone` foreground service.** Android cuts microphone access to a
backgrounded process without one; `CallForegroundService` runs for the life of the call.

---

## 6. Permissions

| Permission | When | Notes |
|---|---|---|
| `INTERNET` | Always | Normal, no runtime prompt |
| `POST_NOTIFICATIONS` | Android 13+ | Runtime prompt; request after showing value, not on first launch |
| `RECORD_AUDIO` | Calls | Runtime |
| `CAMERA` | Video calls | Runtime |
| `USE_FULL_SCREEN_INTENT` | Calls, Android 14+ | Special |
| `FOREGROUND_SERVICE_MICROPHONE` | Calls | Normal; the service type must match |

Request at the point of use. Requesting notification permission on first launch produces a high denial rate; ask when the user sends their first message instead.

`RECORD_AUDIO` is wired the same way as notifications: shared `MicPermission` exposes a prompt flow,
`MicPermissionBinder` drives the launcher from `MainActivity` and `CallActivity`, and the caller
awaits the answer before placing or answering the call.

**How that is wired without leaking an Activity:** `ChatViewModel` calls
`PushPermissionRequests.request()` after a successful send; `MainActivity` collects that flow and
launches its `ActivityResultLauncher`. Shared code never sees an `Activity`, and the ask happens at
most once per process (`MessageNotifier` silently drops notifications while the permission is
denied).

---

## 7. Storage

Tokens go in `EncryptedSharedPreferences`, never plain preferences:

```kotlin
class AndroidTokenStore(context: Context) : TokenStore {
    private val prefs = EncryptedSharedPreferences.create(
        context, "relay_secure",
        MasterKey.Builder(context).setKeyScheme(AES256_GCM).build(),
        PrefKeyEncryptionScheme.AES256_SIV,
        PrefValueEncryptionScheme.AES256_GCM
    )
}
```

SQLDelight uses `AndroidSqliteDriver`. The message database is not encrypted by default — if that becomes a requirement, SQLCipher integrates with SQLDelight.

---

## 8. Process death

Android kills backgrounded apps freely. Because the DB is the source of truth this is mostly handled, but verify:

- Pending sends survive (they are rows, not memory)
- ViewModels restore from the DB, not from `SavedStateHandle` for anything substantial
- The sync engine reconnects cleanly on recreation without duplicating connections

**Test with "Don't keep activities"** in developer options — it surfaces state-restoration bugs immediately.

---

## 9. Android test checklist

- [ ] Doze mode → high-priority FCM still wakes the app
- [ ] Process death with pending sends → flushed on relaunch
- [ ] "Don't keep activities" → state restores from DB
- [ ] Airplane mode → send queues and flushes on restore
- [ ] Notification suppressed when the relevant chat is open
- [ ] FCM token rotation mid-session → re-registered
- [ ] Foreground service starts and stops with conversation lifecycle
- [ ] Android 14+ `foregroundServiceType` declared correctly
- [ ] Rapid rotation → no duplicate connections
