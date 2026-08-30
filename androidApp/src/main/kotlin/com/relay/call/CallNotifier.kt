package com.relay.call

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.relay.R
import com.relay.push.CALL_CHANNEL_ID
import com.relay.push.MESSAGE_CHANNEL_ID
import com.relay.push.NotificationChannels
import com.relay.push.PushDisplay

const val EXTRA_CALL_ID = "relay.callId"
const val INCOMING_CALL_NOTIFICATION_ID = 4001
const val ONGOING_CALL_NOTIFICATION_ID = 4002

private const val MISSED_CALL_ID_OFFSET = 5000

class CallNotifier(private val context: Context) {

    fun showIncoming(display: PushDisplay.IncomingCall) {
        showIncoming(callId = display.callId, callerName = display.callerName, isGroup = display.isGroup)
    }

    fun showIncoming(callId: String, callerName: String, isGroup: Boolean = false) {
        if (!canPost()) return
        NotificationChannels.ensure(context)
        val fullScreen = callScreenIntent(callId)
        val notification = NotificationCompat.Builder(context, CALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(callerName)
            .setContentText(context.getString(if (isGroup) R.string.call_incoming_group else R.string.call_incoming))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(true)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .build()
        NotificationManagerCompat.from(context).notify(INCOMING_CALL_NOTIFICATION_ID, notification)
    }

    fun showMissed(display: PushDisplay.MissedCall) {
        if (!canPost()) return
        NotificationChannels.ensure(context)
        val notification = NotificationCompat.Builder(context, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(display.callerName)
            .setContentText(context.getString(if (display.isGroup) R.string.call_missed_group else R.string.call_missed))
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context)
            .notify(MISSED_CALL_ID_OFFSET + display.callId.hashCode(), notification)
    }

    fun dismissIncoming() {
        NotificationManagerCompat.from(context).cancel(INCOMING_CALL_NOTIFICATION_ID)
    }

    fun ongoing(peerName: String, callId: String?): Notification {
        NotificationChannels.ensure(context)
        return NotificationCompat.Builder(context, CALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(peerName)
            .setContentText(context.getString(R.string.call_ongoing))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(callScreenIntent(callId))
            .build()
    }

    private fun callScreenIntent(callId: String?): PendingIntent {
        val intent = Intent(context, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CALL_ID, callId)
        }
        return PendingIntent.getActivity(
            context,
            INCOMING_CALL_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}
