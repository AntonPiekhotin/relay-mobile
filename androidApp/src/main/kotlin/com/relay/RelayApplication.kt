package com.relay

import android.app.Application
import com.google.firebase.messaging.FirebaseMessaging
import com.relay.call.CallForegroundService
import com.relay.call.CallNotifier
import com.relay.call.CallSession
import com.relay.call.CallStage
import com.relay.call.GroupCallSession
import com.relay.call.GroupCallStage
import com.relay.call.IncomingCallRinger
import com.relay.call.OutgoingRingbackTone
import com.relay.di.initKoin
import com.relay.push.AppPresence
import com.relay.push.DeviceTokenRegistrar
import com.relay.push.NotificationChannels
import com.relay.repository.CallRepository
import com.relay.repository.GroupCallRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext

class RelayApplication : Application() {

    private val registrar: DeviceTokenRegistrar by inject()
    private val calls: CallRepository by inject()
    private val groupCalls: GroupCallRepository by inject()
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
            combine(calls.session, groupCalls.session) { direct, group -> direct to group }
                .collect { (direct, group) -> onCallSessions(direct, group) }
        }
    }

    private suspend fun onCallSessions(direct: CallSession?, group: GroupCallSession?) {
        val holdsMicrophone = direct?.stage == CallStage.CONNECTING ||
            direct?.stage == CallStage.ACTIVE ||
            group?.stage == GroupCallStage.CONNECTING ||
            group?.stage == GroupCallStage.ACTIVE
        if (holdsMicrophone != callServiceRunning) {
            callServiceRunning = holdsMicrophone
            if (holdsMicrophone) {
                CallForegroundService.start(this, activeCallTitle(direct, group))
            } else {
                CallForegroundService.stop(this)
            }
        }
        ringBack(direct)
        ringIfUnseen(direct, group)
    }

    private fun ringBack(direct: CallSession?) {
        val waitingForAnswer = direct != null && direct.isOutgoing &&
            (direct.stage == CallStage.DIALING || direct.stage == CallStage.RINGING)
        if (waitingForAnswer) OutgoingRingbackTone.start() else OutgoingRingbackTone.stop()
    }

    private suspend fun ringIfUnseen(direct: CallSession?, group: GroupCallSession?) {
        val ringingDirect = direct?.takeIf { it.stage == CallStage.INCOMING }
        val ringingGroup = group?.takeIf { it.stage == GroupCallStage.INCOMING }
        if (ringingDirect == null && ringingGroup == null) {
            stopRinging()
            return
        }
        IncomingCallRinger.start(this)
        if (presence.foreground.value) {
            dismissRingingNotification()
            return
        }
        when {
            ringingDirect != null -> showRinging(ringingDirect.callId, peerNameOf(ringingDirect), isGroup = false)
            ringingGroup != null ->
                showRinging(ringingGroup.callId, initiatorNameOf(ringingGroup), isGroup = true)
        }
    }

    private fun showRinging(callId: String, callerName: String, isGroup: Boolean) {
        if (ringingCallId == callId) return
        ringingCallId = callId
        callNotifier.showIncoming(callId, callerName, isGroup)
    }

    private fun stopRinging() {
        IncomingCallRinger.stop()
        dismissRingingNotification()
    }

    private fun dismissRingingNotification() {
        if (ringingCallId == null) return
        ringingCallId = null
        callNotifier.dismissIncoming()
    }

    private suspend fun activeCallTitle(direct: CallSession?, group: GroupCallSession?): String =
        when {
            direct != null -> peerNameOf(direct)
            group != null -> initiatorNameOf(group)
            else -> ""
        }

    private suspend fun peerNameOf(session: CallSession?): String =
        session?.peerId?.let { calls.peerName(it) }.orEmpty()

    private suspend fun initiatorNameOf(session: GroupCallSession): String =
        groupCalls.nameOf(session.initiatorId).orEmpty()

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
