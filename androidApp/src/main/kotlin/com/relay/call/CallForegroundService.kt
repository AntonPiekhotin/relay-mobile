package com.relay.call

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.relay.repository.CallRepository
import com.relay.repository.GroupCallRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

const val EXTRA_PEER_NAME = "relay.peerName"

private const val TAG = "RelayCallService"

class CallForegroundService : Service() {

    private val calls: CallRepository by inject()
    private val groupCalls: GroupCallRepository by inject()
    private val scope: CoroutineScope by inject()
    private val notifier by lazy { CallNotifier(applicationContext) }
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val callId = calls.session.value?.callId ?: groupCalls.session.value?.callId
        val peerName = intent?.getStringExtra(EXTRA_PEER_NAME).orEmpty()
        if (!startForegroundWithMicrophone(notifier.ongoing(peerName, callId))) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (watcher == null) {
            watcher = scope.launch {
                combine(calls.session, groupCalls.session) { direct, group -> direct == null && group == null }
                    .collect { idle -> if (idle) stopSelf() }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watcher?.cancel()
        watcher = null
        super.onDestroy()
    }

    private fun startForegroundWithMicrophone(notification: Notification): Boolean {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        return try {
            ServiceCompat.startForeground(this, ONGOING_CALL_NOTIFICATION_ID, notification, type)
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "the platform refused a microphone foreground service", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "no microphone permission for a foreground service", e)
            false
        }
    }

    companion object {
        fun start(context: Context, peerName: String) {
            val intent = Intent(context, CallForegroundService::class.java)
                .putExtra(EXTRA_PEER_NAME, peerName)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "cannot start the call service from the background", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallForegroundService::class.java))
        }
    }
}
