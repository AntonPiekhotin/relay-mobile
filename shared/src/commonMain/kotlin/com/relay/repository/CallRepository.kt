package com.relay.repository

import com.relay.call.CallEngine
import com.relay.call.CallSession
import com.relay.call.PlaceCallResult
import kotlinx.coroutines.flow.StateFlow

interface CallRepository {
    val session: StateFlow<CallSession?>
    suspend fun call(peerId: String, dialogId: String?): PlaceCallResult
    suspend fun peerName(userId: String): String?
    fun accept()
    fun reject()
    fun hangup()
    fun setMuted(muted: Boolean)
    fun setSpeakerOn(enabled: Boolean)
}

class CallRepositoryImpl(
    private val engine: CallEngine,
    private val users: UserRepository
) : CallRepository {

    override val session: StateFlow<CallSession?> = engine.session

    override suspend fun call(peerId: String, dialogId: String?): PlaceCallResult =
        engine.place(peerId, dialogId)

    override suspend fun peerName(userId: String): String? =
        when (val result = users.lookup(userId)) {
            is UserResult.Success -> result.value.displayName
            is UserResult.Failure -> null
        }

    override fun accept() = engine.accept()

    override fun reject() = engine.reject()

    override fun hangup() = engine.hangup()

    override fun setMuted(muted: Boolean) = engine.setMuted(muted)

    override fun setSpeakerOn(enabled: Boolean) = engine.setSpeakerOn(enabled)
}
