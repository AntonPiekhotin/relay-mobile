package com.relay.repository

import com.relay.auth.AuthState
import com.relay.auth.SessionManager
import com.relay.db.MessageStore
import com.relay.model.Dialog
import com.relay.model.DialogSummary
import com.relay.model.DialogSyncState
import com.relay.model.Message
import com.relay.network.MessageApi
import com.relay.network.MessageApiResult
import com.relay.protocol.isoToEpochMillisOrNull
import com.relay.protocol.newFrameId
import com.relay.protocol.nowEpochMillis
import com.relay.sync.Outbox
import com.relay.sync.applyWireMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

const val HISTORY_PAGE_SIZE = 50

sealed interface OpenDialogResult {
    data class Opened(val dialogId: String) : OpenDialogResult
    data class Failed(val message: String) : OpenDialogResult
}

interface MessageRepository {
    suspend fun openDirectDialog(peerId: String): OpenDialogResult
    fun observeDialogs(): Flow<List<Dialog>>
    fun observeDialogSummaries(): Flow<List<DialogSummary>>
    fun observeDialog(dialogId: String): Flow<Dialog?>
    fun observeMessages(dialogId: String, limit: Long): Flow<List<Message>>
    fun observeSyncState(dialogId: String): Flow<DialogSyncState?>
    suspend fun storedMessageCount(dialogId: String): Long
    suspend fun send(dialogId: String, text: String): Boolean
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

    override suspend fun openDirectDialog(peerId: String): OpenDialogResult =
        when (val result = api.openDirectDialog(peerId)) {
            is MessageApiResult.Success -> {
                val opened = result.value
                store.upsertDialog(
                    id = opened.id,
                    type = opened.type,
                    title = null,
                    lastMessageAt = opened.createdAt?.let { isoToEpochMillisOrNull(it) }
                )
                OpenDialogResult.Opened(opened.id)
            }
            is MessageApiResult.Unavailable -> OpenDialogResult.Failed(result.reason)
            is MessageApiResult.Rejected -> OpenDialogResult.Failed(
                "The server refused to open that conversation (HTTP ${result.status})"
            )
        }

    override fun observeDialogs(): Flow<List<Dialog>> = store.observeDialogs()

    override fun observeDialogSummaries(): Flow<List<DialogSummary>> = store.observeDialogSummaries()

    override fun observeDialog(dialogId: String): Flow<Dialog?> = store.observeDialog(dialogId)

    override fun observeMessages(dialogId: String, limit: Long): Flow<List<Message>> =
        store.observeMessages(dialogId, limit)

    override fun observeSyncState(dialogId: String): Flow<DialogSyncState?> =
        store.observeSyncState(dialogId)

    override suspend fun storedMessageCount(dialogId: String): Long = store.countMessages(dialogId)

    override suspend fun send(dialogId: String, text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val senderId = (session.state.value as? AuthState.LoggedIn)?.userId ?: return false
        store.insertPending(
            clientMsgId = newFrameId(),
            dialogId = dialogId,
            senderId = senderId,
            text = trimmed,
            createdAt = nowEpochMillis()
        )
        outbox.wake()
        return true
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
