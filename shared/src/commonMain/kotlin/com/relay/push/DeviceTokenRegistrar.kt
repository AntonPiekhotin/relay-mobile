package com.relay.push

import com.relay.auth.AuthState
import com.relay.auth.SessionManager
import com.relay.db.PushStore
import com.relay.network.NotificationApi
import com.relay.network.NotificationApiResult
import com.relay.network.RegisterDeviceTokenRequest
import com.relay.network.backoffDelayMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PushPlatform(val name: String)

class DeviceTokenRegistrar(
    private val store: PushStore,
    private val api: NotificationApi,
    private val session: SessionManager,
    private val scope: CoroutineScope
) {
    private var job: Job? = null
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    private enum class Outcome { Settled, Retry }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            launch { session.state.collect { wake() } }
            syncLoop()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun wake() {
        wakeups.trySend(Unit)
    }

    suspend fun onFcmToken(token: String) {
        store.setFcmToken(token)
        wake()
    }

    suspend fun onVoipToken(token: String) {
        store.setVoipToken(token)
        wake()
    }

    suspend fun deviceId(): String = store.device().deviceId

    suspend fun unregister() {
        val device = store.device()
        if (device.registeredUser != null) api.unregisterDevice(device.deviceId)
        store.clearRegistration()
    }

    private suspend fun syncLoop() {
        var failures = 0
        while (currentCoroutineContext().isActive) {
            when (syncOnce()) {
                Outcome.Settled -> {
                    failures = 0
                    wakeups.receive()
                }
                Outcome.Retry -> {
                    delay(backoffDelayMillis(failures))
                    failures++
                }
            }
        }
    }

    private suspend fun syncOnce(): Outcome {
        val userId = (session.state.value as? AuthState.LoggedIn)?.userId ?: return Outcome.Settled
        val device = store.device()
        if (!device.hasAnyToken() || device.isRegisteredFor(userId)) return Outcome.Settled
        val result = api.registerDevice(
            RegisterDeviceTokenRequest(
                deviceId = device.deviceId,
                platform = device.platform,
                fcmToken = device.fcmToken,
                voipToken = device.voipToken
            )
        )
        return when (result) {
            is NotificationApiResult.Success -> {
                store.markRegistered(userId, device.fcmToken, device.voipToken)
                Outcome.Settled
            }
            is NotificationApiResult.Unavailable -> Outcome.Retry
            is NotificationApiResult.Rejected -> Outcome.Settled
        }
    }
}
