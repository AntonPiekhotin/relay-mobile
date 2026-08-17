package com.relay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.relay.call.MicPermission
import com.relay.call.MicPermissionBinder
import com.relay.push.AppPresence
import com.relay.push.EXTRA_DIALOG_ID
import com.relay.push.FCM_DIALOG_ID
import com.relay.push.MessageNotifier
import com.relay.push.NotificationChannels
import com.relay.push.PushNavigator
import com.relay.push.PushPermissionRequests
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val presence: AppPresence by inject()
    private val pushNavigator: PushNavigator by inject()
    private val permissionRequests: PushPermissionRequests by inject()
    private val mic: MicPermission by inject()
    private val notifier by lazy { MessageNotifier(applicationContext) }
    private lateinit var micRequests: MicPermissionBinder

    private var permissionAsked = false

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        NotificationChannels.ensure(this)
        micRequests = MicPermissionBinder(this, mic)
        routeFromPush(intent)

        lifecycleScope.launch {
            permissionRequests.requests.collect { askForNotificationPermission() }
        }

        setContent {
            App()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeFromPush(intent)
    }

    override fun onStart() {
        super.onStart()
        presence.onForeground()
    }

    override fun onStop() {
        presence.onBackground()
        super.onStop()
    }

    private fun routeFromPush(intent: Intent?) {
        if (intent == null) return
        val dialogId = intent.getStringExtra(EXTRA_DIALOG_ID)
            ?: intent.getStringExtra(FCM_DIALOG_ID)
            ?: return
        intent.removeExtra(EXTRA_DIALOG_ID)
        intent.removeExtra(FCM_DIALOG_ID)
        notifier.dismiss(dialogId)
        pushNavigator.openDialog(dialogId)
    }

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (permissionAsked) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        permissionAsked = true
        requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
