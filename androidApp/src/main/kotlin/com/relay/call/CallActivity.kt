package com.relay.call

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.relay.repository.CallRepository
import com.relay.repository.GroupCallRepository
import com.relay.ui.call.CallHost
import com.relay.ui.theme.RelayTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class CallActivity : ComponentActivity() {

    private val calls: CallRepository by inject()
    private val groupCalls: GroupCallRepository by inject()
    private val mic: MicPermission by inject()
    private val notifier by lazy { CallNotifier(applicationContext) }
    private lateinit var micRequests: MicPermissionBinder

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        micRequests = MicPermissionBinder(this, mic)
        notifier.dismissIncoming()

        lifecycleScope.launch {
            combine(calls.session, groupCalls.session) { direct, group -> direct == null && group == null }
                .collect { idle -> if (idle) finish() }
        }

        setContent {
            RelayTheme {
                CallHost()
            }
        }
        answerIfAsked(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notifier.dismissIncoming()
        answerIfAsked(intent)
    }

    private fun answerIfAsked(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_ANSWER_CALL, false) != true) return
        intent.removeExtra(EXTRA_ANSWER_CALL)
        lifecycleScope.launch { answerIncoming() }
    }

    private suspend fun answerIncoming() {
        val direct = calls.session.value?.stage == CallStage.INCOMING
        val group = groupCalls.session.value?.stage == GroupCallStage.INCOMING
        if (!direct && !group) return
        val granted = mic.ensureGranted()
        when {
            direct && granted -> calls.accept()
            direct -> calls.reject()
            granted -> groupCalls.accept()
            else -> groupCalls.decline()
        }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }
}
