package com.relay.call

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.relay.repository.CallRepository
import com.relay.ui.call.CallHost
import com.relay.ui.theme.RelayTheme
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class CallActivity : ComponentActivity() {

    private val calls: CallRepository by inject()
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
            calls.session.collect { session -> if (session == null) finish() }
        }

        setContent {
            RelayTheme {
                CallHost()
            }
        }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }
}
