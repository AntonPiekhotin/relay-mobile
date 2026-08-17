package com.relay.call

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MicPermission(private val grantedByPlatform: Boolean) {
    private val mutablePrompts = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val prompts: SharedFlow<Unit> = mutablePrompts.asSharedFlow()

    private val guard = Mutex()
    private var pending: CompletableDeferred<Boolean>? = null

    suspend fun ensureGranted(): Boolean {
        if (grantedByPlatform) return true
        val answer = guard.withLock {
            pending ?: CompletableDeferred<Boolean>().also { pending = it }
        }
        mutablePrompts.emit(Unit)
        return answer.await()
    }

    fun onResult(granted: Boolean) {
        val answer = pending ?: return
        pending = null
        answer.complete(granted)
    }
}
