package com.relay.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.relay.repository.CallRepository
import com.relay.repository.GroupCallRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

const val ACTION_DECLINE_CALL = "com.relay.action.DECLINE_CALL"

class CallActionReceiver : BroadcastReceiver(), KoinComponent {

    private val calls: CallRepository by inject()
    private val groupCalls: GroupCallRepository by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DECLINE_CALL) return
        IncomingCallRinger.stop()
        CallNotifier(context.applicationContext).dismissIncoming()
        when {
            calls.session.value?.stage == CallStage.INCOMING -> calls.reject()
            groupCalls.session.value?.stage == GroupCallStage.INCOMING -> groupCalls.decline()
        }
    }
}
