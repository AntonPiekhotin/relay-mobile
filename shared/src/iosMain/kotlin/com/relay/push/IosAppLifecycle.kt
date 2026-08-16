package com.relay.push

import com.relay.auth.AuthState
import com.relay.auth.SessionManager
import com.relay.network.ConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification

class IosAppLifecycle(
    private val presence: AppPresence,
    private val connection: ConnectionManager,
    private val session: SessionManager,
    private val presenter: IosNotificationPresenter,
    private val permissionRequests: PushPermissionRequests,
    private val scope: CoroutineScope
) {
    private var started = false

    fun start() {
        if (started) return
        started = true
        observe(UIApplicationDidBecomeActiveNotification) {
            presence.onForeground()
            if (session.state.value is AuthState.LoggedIn) connection.start()
        }
        observe(UIApplicationDidEnterBackgroundNotification) {
            presence.onBackground()
            connection.stop()
        }
        scope.launch {
            permissionRequests.requests.collect { presenter.requestAuthorization() }
        }
    }

    private fun observe(name: String?, block: () -> Unit) {
        NSNotificationCenter.defaultCenter.addObserverForName(
            name = name,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
            usingBlock = { block() }
        )
    }
}
