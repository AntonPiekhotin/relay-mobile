package com.relay.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.content.getSystemService
import com.relay.R

const val MESSAGE_CHANNEL_ID = "messages"
const val CALL_CHANNEL_ID = "calls"

object NotificationChannels {
    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService<NotificationManager>() ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                MESSAGE_CHANNEL_ID,
                context.getString(R.string.channel_messages),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CALL_CHANNEL_ID,
                context.getString(R.string.channel_calls),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }
}
