package com.relay.push

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.relay.MainActivity
import com.relay.R

const val EXTRA_DIALOG_ID = "relay.dialogId"
const val FCM_DIALOG_ID = "dialogId"

class MessageNotifier(private val context: Context) {

    fun show(message: PushDisplay.Message) {
        if (!canPost()) return
        NotificationChannels.ensure(context)
        val notification = NotificationCompat.Builder(context, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(message.title)
            .setContentText(message.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openDialogIntent(message.dialogId))
            .build()
        NotificationManagerCompat.from(context)
            .notify(message.dialogId.hashCode(), notification)
    }

    fun dismiss(dialogId: String) {
        NotificationManagerCompat.from(context).cancel(dialogId.hashCode())
    }

    private fun openDialogIntent(dialogId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_DIALOG_ID, dialogId)
        }
        return PendingIntent.getActivity(
            context,
            dialogId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}
