package com.relay.push

import com.relay.call.CallEngine
import com.relay.call.GroupCallEngine
import com.relay.call.IosRtc
import com.relay.call.IosSfu
import com.relay.call.RtcClientFactory
import com.relay.call.SfuClientFactory
import com.relay.di.ensureKoinStarted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform

private const val APS_KEY = "aps"
private const val ALERT_KEY = "alert"
private const val BODY_KEY = "body"

object SharedBridge {

    fun start() {
        ensureKoinStarted()
        resolve<IosAppLifecycle>().start()
    }

    fun registerRtcFactory(factory: RtcClientFactory) {
        IosRtc.factory = factory
    }

    fun registerSfuFactory(factory: SfuClientFactory) {
        IosSfu.factory = factory
    }

    fun onIncomingCallPush(callId: String, callerId: String, media: String, ringExpiresAt: String?) {
        ensureKoinStarted()
        resolve<CallEngine>().onIncomingCallPush(callId, callerId, media, ringExpiresAt)
    }

    fun onIncomingGroupCallPush(callId: String, callerId: String, media: String, ringExpiresAt: String?) {
        ensureKoinStarted()
        resolve<GroupCallEngine>().onIncomingGroupCallPush(callId, callerId, media, ringExpiresAt)
    }

    fun registerApnsToken(token: String) {
        ensureKoinStarted()
        val registrar = resolve<DeviceTokenRegistrar>()
        resolve<CoroutineScope>().launch { registrar.onFcmToken(token) }
    }

    fun registerVoipToken(token: String) {
        ensureKoinStarted()
        val registrar = resolve<DeviceTokenRegistrar>()
        resolve<CoroutineScope>().launch { registrar.onVoipToken(token) }
    }

    fun handlePush(userInfo: Map<Any?, *>) {
        ensureKoinStarted()
        val data = userInfo.stringValues()
        val event = parsePushEvent(data)
        if (event is PushEvent.Unknown) return
        val coordinator = resolve<PushCoordinator>()
        val presenter = resolve<IosNotificationPresenter>()
        resolve<CoroutineScope>().launch {
            val display = coordinator.handle(event, userInfo.alertBody())
            if (display is PushDisplay.Message) presenter.show(display)
        }
    }

    fun shouldSuppress(userInfo: Map<Any?, *>): Boolean {
        ensureKoinStarted()
        val dialogId = userInfo.stringValues()["dialogId"] ?: return false
        return resolve<AppPresence>().isShowing(dialogId)
    }

    fun openFromNotification(userInfo: Map<Any?, *>) {
        ensureKoinStarted()
        val dialogId = userInfo.stringValues()["dialogId"] ?: return
        resolve<IosNotificationPresenter>().dismiss(dialogId)
        resolve<PushNavigator>().openDialog(dialogId)
    }

    private inline fun <reified T : Any> resolve(): T = KoinPlatform.getKoin().get<T>()
}

private fun Map<Any?, *>.stringValues(): Map<String, String> =
    entries.mapNotNull { (key, value) ->
        val name = key as? String ?: return@mapNotNull null
        val text = value as? String ?: return@mapNotNull null
        name to text
    }.toMap()

private fun Map<Any?, *>.alertBody(): String? {
    val aps = this[APS_KEY] as? Map<*, *> ?: return null
    return when (val alert = aps[ALERT_KEY]) {
        is String -> alert
        is Map<*, *> -> alert[BODY_KEY] as? String
        else -> null
    }
}
