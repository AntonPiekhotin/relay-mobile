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

```kotlin
class RelayFirebaseService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        // Register with the backend; store locally so it can be re-sent after login
        tokenRegistrar.register(token, kind = "fcm")
    }

    override fun onMessageReceived(message: RemoteMessage) {
        when (message.data["kind"]) {
            "message" -> { syncEngine.wakeAndCatchUp(); showNotification(message) }
            "call"    -> callHandler.onIncomingCall(message.data)
            else      -> Unit   // unknown kinds must be ignored, not crash
        }
    }
}
```

**Payload should carry minimal data.** Send identifiers, not content — then catch up over REST. This avoids notification payload size limits, keeps message text out of Google's infrastructure, and guarantees the local DB is the source of truth.

**Use `priority: high`** for message and call pushes. Normal priority is deferred in Doze and may arrive hours late.

**Token refresh:** FCM rotates tokens. `onNewToken` can fire at any time, including before login. Persist locally and re-register after authentication.

---

## 4. Notification handling

Do not post a notification for a message that is already on screen. Check whether the app is foregrounded and whether that dialog is open:

```kotlin
if (appState.isForeground && appState.openDialogId == message.dialogId) return
```

This mirrors the server's socket-XOR-push rule at the client level, and catches the race where a push arrives just as the user opens the chat.

Channels: separate channels for messages (default importance) and calls (high importance, with a custom ringtone), so users can configure them independently.

---

## 5. Incoming calls

*Phase 6.* Two options, in order of preference:

**`ConnectionService`** — integrates with the system dialer, handles interaction with real phone calls, appears in call history. More setup; the correct choice for a real product.

**Full-screen intent** — simpler, shows a custom call screen over the lock screen.

```kotlin
val notification = NotificationCompat.Builder(context, CALL_CHANNEL)
    .setFullScreenIntent(pendingIntent, true)
    .setCategory(NotificationCompat.CATEGORY_CALL)
    .setOngoing(true)
    .build()
```

On Android 14+ this requires the `USE_FULL_SCREEN_INTENT` permission, which is granted by default only for calling and alarm apps — verify at runtime and degrade to a heads-up notification if unavailable.

Unlike iOS, Android imposes no obligation to report the call within a deadline. The constraint is only that a high-priority FCM push must arrive.

---

## 6. Permissions

| Permission | When | Notes |
|---|---|---|
| `INTERNET` | Always | Normal, no runtime prompt |
| `POST_NOTIFICATIONS` | Android 13+ | Runtime prompt; request after showing value, not on first launch |
| `RECORD_AUDIO` | Calls | Runtime |
| `CAMERA` | Video calls | Runtime |
| `USE_FULL_SCREEN_INTENT` | Calls, Android 14+ | Special |

Request at the point of use. Requesting notification permission on first launch produces a high denial rate; ask when the user sends their first message instead.

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
