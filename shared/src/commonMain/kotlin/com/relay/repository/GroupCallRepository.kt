package com.relay.repository

import com.relay.call.GroupCallEngine
import com.relay.call.GroupCallSession
import com.relay.call.PlaceCallResult
import kotlinx.coroutines.flow.StateFlow

interface GroupCallRepository {
    val session: StateFlow<GroupCallSession?>
    suspend fun start(inviteeIds: List<String>): PlaceCallResult
    suspend fun nameOf(userId: String): String?
    fun accept()
    fun decline()
    fun leave()
    fun setMuted(muted: Boolean)
    fun setSpeakerOn(enabled: Boolean)
}

class GroupCallRepositoryImpl(
    private val engine: GroupCallEngine,
    private val users: UserRepository
) : GroupCallRepository {

    override val session: StateFlow<GroupCallSession?> = engine.session

    override suspend fun start(inviteeIds: List<String>): PlaceCallResult = engine.place(inviteeIds)

    override suspend fun nameOf(userId: String): String? =
        when (val result = users.lookup(userId)) {
            is UserResult.Success -> result.value.displayName
            is UserResult.Failure -> null
        }

    override fun accept() = engine.accept()

    override fun decline() = engine.decline()

    override fun leave() = engine.leave()

    override fun setMuted(muted: Boolean) = engine.setMuted(muted)

    override fun setSpeakerOn(enabled: Boolean) = engine.setSpeakerOn(enabled)
}
