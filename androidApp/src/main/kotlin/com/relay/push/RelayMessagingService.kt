package com.relay.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class RelayMessagingService : FirebaseMessagingService() {

    private val registrar: DeviceTokenRegistrar by inject()
    private val coordinator: PushCoordinator by inject()
    private val scope: CoroutineScope by inject()
    private val notifier by lazy { MessageNotifier(applicationContext) }

    override fun onNewToken(token: String) {
        scope.launch { registrar.onFcmToken(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val event = parsePushEvent(message.data)
        if (event is PushEvent.Unknown) return
        val fallbackBody = message.notification?.body
        scope.launch {
            when (val display = coordinator.handle(event, fallbackBody)) {
                is PushDisplay.Message -> notifier.show(display)
                is PushDisplay.Suppress -> Unit
            }
        }
    }
}
