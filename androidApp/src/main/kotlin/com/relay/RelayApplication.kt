package com.relay

import android.app.Application
import com.google.firebase.messaging.FirebaseMessaging
import com.relay.di.initKoin
import com.relay.push.DeviceTokenRegistrar
import com.relay.push.NotificationChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext

class RelayApplication : Application() {

    private val registrar: DeviceTokenRegistrar by inject()
    private val scope: CoroutineScope by inject()

    override fun onCreate() {
        super.onCreate()
        initKoin {
            androidContext(this@RelayApplication)
        }
        NotificationChannels.ensure(this)
        adoptCurrentPushToken()
    }

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
