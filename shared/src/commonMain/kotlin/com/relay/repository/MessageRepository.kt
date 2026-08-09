package com.relay.repository

import com.relay.auth.AuthState
import com.relay.auth.SessionManager
import com.relay.db.MessageStore
import com.relay.model.Dialog
import com.relay.model.Message
import com.relay.network.MessageApi
import com.relay.network.MessageApiResult
import com.relay.protocol.newFrameId
import com.relay.protocol.nowEpochMillis
import com.relay.sync.Outbox
import com.relay.sync.applyWireMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val HISTORY_PAGE_SIZE = 50

interface MessageRepository {
    fun observeDialogs(): Flow<List<Dialog>>
    fun observeMessages(dialogId: String): Flow<List<Message>>
    suspend fun send(dialogId: String, text: String)
    suspend fun retry(localId: Long)
    suspend fun loadOlder(dialogId: String)
}

class MessageRepositoryImpl(
    private val store: MessageStore,
    private val outbox: Outbox,
    private val api: MessageApi,
    private val session: SessionManager
) : MessageRepository {
    private val loadOlderGuard = Mutex()
    private val loadingOlder = mutableSetOf<String>()

    override fun observeDialogs(): Flow<List<Dialog>> = store.observeDialogs()

    override fun observeMessages(dialogId: String): Flow<List<Message>> =
        store.observeMessages(dialogId)

    override suspend fun send(dialogId: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val senderId = (session.state.value as? AuthState.LoggedIn)?.userId ?: return
        store.insertPending(
            clientMsgId = newFrameId(),
            dialogId = dialogId,
            senderId = senderId,
            text = trimmed,
            createdAt = nowEpochMillis()
        )
        outbox.wake()
    }

    override suspend fun retry(localId: Long) {
        store.resetForRetry(localId)
        outbox.wake()
    }

    override suspend fun loadOlder(dialogId: String) {
        val acquired = loadOlderGuard.withLock { loadingOlder.add(dialogId) }
        if (!acquired) return
        try {
            loadOlderPage(dialogId)
        } finally {
            loadOlderGuard.withLock { loadingOlder.remove(dialogId) }
        }
    }

    private suspend fun loadOlderPage(dialogId: String) {
        val syncState = store.syncState(dialogId) ?: return
        if (!syncState.hasMoreHistory) return
        val result = api.messagesBefore(dialogId, syncState.oldestLoadedId, HISTORY_PAGE_SIZE)
        if (result !is MessageApiResult.Success) return
        val page = result.value
        page.forEach { store.applyWireMessage(it) }
        store.updateOldestLoaded(
            dialogId = dialogId,
            oldestLoadedId = page.lastOrNull()?.messageId ?: syncState.oldestLoadedId,
            hasMore = page.size == HISTORY_PAGE_SIZE
        )
    }
}
