package com.relay.push

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow

class PushPermissionRequests {
    private val mutableRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requests: SharedFlow<Unit> = mutableRequests.asSharedFlow()

    fun request() {
        mutableRequests.tryEmit(Unit)
    }
}

class PushNavigator {
    private val pending = Channel<String>(Channel.CONFLATED)
    val targets: Flow<String> = pending.receiveAsFlow()

    fun openDialog(dialogId: String) {
        pending.trySend(dialogId)
    }
}
