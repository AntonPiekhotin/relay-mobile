package com.relay.repository

import com.relay.presence.PeerPresence
import com.relay.presence.PresenceEngine
import kotlinx.coroutines.flow.StateFlow

interface PresenceRepository {
    val presence: StateFlow<Map<String, PeerPresence>>
    val typing: StateFlow<Map<String, Set<String>>>
    fun dialogOpened(dialogId: String)
    fun dialogClosed(dialogId: String)
    fun typingActivity(dialogId: String)
}

class PresenceRepositoryImpl(
    private val engine: PresenceEngine
) : PresenceRepository {

    override val presence: StateFlow<Map<String, PeerPresence>> = engine.presence

    override val typing: StateFlow<Map<String, Set<String>>> = engine.typing

    override fun dialogOpened(dialogId: String) = engine.dialogOpened(dialogId)

    override fun dialogClosed(dialogId: String) = engine.dialogClosed(dialogId)

    override fun typingActivity(dialogId: String) = engine.typingActivity(dialogId)
}
