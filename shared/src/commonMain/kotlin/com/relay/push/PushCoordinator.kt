package com.relay.push

import com.relay.db.MessageStore
import com.relay.sync.SyncEngine

private const val DEFAULT_TITLE = "New message"
private const val DEFAULT_BODY = "You have a new message"

sealed interface PushDisplay {
    data object Suppress : PushDisplay
    data class Message(
        val dialogId: String,
        val messageId: String,
        val title: String,
        val body: String
    ) : PushDisplay
}

class PushCoordinator(
    private val syncEngine: SyncEngine,
    private val store: MessageStore,
    private val presence: AppPresence
) {
    suspend fun handle(event: PushEvent, fallbackBody: String? = null): PushDisplay =
        when (event) {
            is PushEvent.NewMessage -> onNewMessage(event, fallbackBody)
            else -> PushDisplay.Suppress
        }

    private suspend fun onNewMessage(event: PushEvent.NewMessage, fallbackBody: String?): PushDisplay {
        syncEngine.wakeAndCatchUp(event.dialogId)
        if (presence.isShowing(event.dialogId)) return PushDisplay.Suppress
        val stored = store.findByServerId(event.messageId)
        return PushDisplay.Message(
            dialogId = event.dialogId,
            messageId = event.messageId,
            title = store.dialog(event.dialogId)?.title ?: DEFAULT_TITLE,
            body = stored?.text ?: fallbackBody ?: DEFAULT_BODY
        )
    }
}
