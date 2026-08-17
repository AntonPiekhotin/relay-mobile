package com.relay

import android.app.Application
import com.google.firebase.messaging.FirebaseMessaging
import com.relay.call.CallForegroundService
import com.relay.call.CallNotifier
import com.relay.call.CallSession
import com.relay.call.CallStage
import com.relay.di.initKoin
import com.relay.push.AppPresence
import com.relay.push.DeviceTokenRegistrar
import com.relay.push.NotificationChannels
import com.relay.repository.CallRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext

class RelayApplication : Application() {

    private val registrar: DeviceTokenRegistrar by inject()
    private val calls: CallRepository by inject()
    private val presence: AppPresence by inject()
    private val scope: CoroutineScope by inject()
    private val callNotifier by lazy { CallNotifier(this) }
    private var callServiceRunning = false
    private var ringingCallId: String? = null

    override fun onCreate() {
        super.onCreate()
        initKoin {
            androidContext(this@RelayApplication)
        }
        NotificationChannels.ensure(this)
        adoptCurrentPushToken()
        observeCalls()
    }

    private fun observeCalls() {
        scope.launch {
            calls.session.collect { session -> onCallSession(session) }
        }
    }

    private suspend fun onCallSession(session: CallSession?) {
        val holdsMicrophone = session?.stage == CallStage.CONNECTING || session?.stage == CallStage.ACTIVE
        if (holdsMicrophone != callServiceRunning) {
            callServiceRunning = holdsMicrophone
            if (holdsMicrophone) {
                CallForegroundService.start(this, peerNameOf(session))
            } else {
                CallForegroundService.stop(this)
            }
        }
        ringIfUnseen(session)
    }

    private suspend fun ringIfUnseen(session: CallSession?) {
        val ringing = session
            ?.takeIf { it.stage == CallStage.INCOMING && !presence.foreground.value }
        if (ringing == null) {
            if (ringingCallId != null) {
                ringingCallId = null
                callNotifier.dismissIncoming()
            }
            return
        }
        if (ringingCallId == ringing.callId) return
        ringingCallId = ringing.callId
        callNotifier.showIncoming(ringing.callId, peerNameOf(ringing))
    }

    private suspend fun peerNameOf(session: CallSession?): String =
        session?.peerId?.let { calls.peerName(it) }.orEmpty()

    private fun adoptCurrentPushToken() {
        val messaging = runCatching { FirebaseMessaging.getInstance() }.getOrNull() ?: return
        messaging.token.addOnCompleteListener { task ->
            val token = task.result
            if (task.isSuccessful && !token.isNullOrBlank()) {
                scope.launch { registrar.onFcmToken(token) }
            }
        }
    }
}
