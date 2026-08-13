package com.relay.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.relay.model.DialogSummary
import com.relay.model.DialogSyncState
import com.relay.model.MessageState
import com.relay.model.ReadCursor
import com.relay.model.UnnamedDialog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.relay.model.Dialog as DomainDialog
import com.relay.model.Message as DomainMessage

private const val DEFAULT_DIALOG_TYPE = "direct"
private const val DEFAULT_VISIBLE_MESSAGES = 500L

data class ReadPosition(
    val messageId: String,
    val createdAt: Long
)

class MessageStore(
    private val db: RelayDb,
    private val dispatcher: CoroutineDispatcher
) {
    fun observeDialogs(): Flow<List<DomainDialog>> =
        db.dialogQueries.selectAll()
            .asFlow()
            .mapToList(dispatcher)
            .map { rows -> rows.map { it.toDomain() } }

    fun observeDialogSummaries(selfId: String?): Flow<List<DialogSummary>> =
        db.dialogQueries.selectAllWithPreview(selfId)
            .asFlow()
            .mapToList(dispatcher)
            .map { rows -> rows.map { it.toDomain() } }

    fun observeMessages(dialogId: String, limit: Long = DEFAULT_VISIBLE_MESSAGES): Flow<List<DomainMessage>> =
        db.messageQueries.selectForDialog(dialogId, limit)
            .asFlow()
            .mapToList(dispatcher)
            .map { rows -> rows.map { it.toDomain() } }

    fun observeDialog(dialogId: String): Flow<DomainDialog?> =
        db.dialogQueries.selectById(dialogId)
            .asFlow()
            .mapToOneOrNull(dispatcher)
            .map { row -> row?.toDomain() }

    fun observeUnnamedDialogs(): Flow<List<UnnamedDialog>> =
        db.dialogQueries.selectUnnamed()
            .asFlow()
            .mapToList(dispatcher)
            .map { rows -> rows.map { UnnamedDialog(it.id, it.peer_id) } }

    fun observeSyncState(dialogId: String): Flow<DialogSyncState?> =
        db.sync_stateQueries.selectByDialog(dialogId)
            .asFlow()
            .mapToOneOrNull(dispatcher)
            .map { row -> row?.toDomain() }

    suspend fun insertPending(
        clientMsgId: String,
        dialogId: String,
        senderId: String,
        text: String,
        createdAt: Long
    ): Unit = withContext(dispatcher) {
        db.transaction {
            db.dialogQueries.insertIfAbsent(dialogId, DEFAULT_DIALOG_TYPE, null, null, null)
            db.sync_stateQueries.insertIfAbsent(dialogId)
            db.messageQueries.insertPending(clientMsgId, dialogId, senderId, text, createdAt)
            db.dialogQueries.bumpLastMessageAt(createdAt, dialogId)
        }
    }

    suspend fun applyAck(clientMsgId: String, serverId: String, createdAt: Long?): Unit =
        withContext(dispatcher) {
            db.transaction {
                val row = db.messageQueries.findByClientMsgId(clientMsgId).executeAsOneOrNull()
                    ?: return@transaction
                if (row.state == MessageState.SENT.name) return@transaction
                val existing = db.messageQueries.findByServerId(serverId).executeAsOneOrNull()
                when {
                    existing != null && existing.local_id != row.local_id ->
                        db.messageQueries.deleteByLocalId(row.local_id)
                    createdAt != null ->
                        db.messageQueries.promote(serverId, createdAt, row.local_id)
                    else ->
                        db.messageQueries.promoteKeepingCreatedAt(serverId, row.local_id)
                }
                if (createdAt != null) {
                    db.dialogQueries.bumpLastMessageAt(createdAt, row.dialog_id)
                }
            }
        }

    suspend fun applyRemoteMessage(
        serverId: String,
        clientMsgId: String?,
        dialogId: String,
        senderId: String,
        text: String,
        createdAt: Long,
        selfId: String? = null
    ): Unit = withContext(dispatcher) {
        val peerId = senderId.takeIf { selfId != null && it != selfId }
        db.transaction {
            db.dialogQueries.insertIfAbsent(dialogId, DEFAULT_DIALOG_TYPE, null, null, peerId)
            if (peerId != null) db.dialogQueries.fillPeerIfMissing(peerId, dialogId)
            db.sync_stateQueries.insertIfAbsent(dialogId)
            val existing = db.messageQueries.findByServerId(serverId).executeAsOneOrNull()
            if (existing == null) {
                val pending = clientMsgId?.let {
                    db.messageQueries.findByClientMsgId(it).executeAsOneOrNull()
                }
                if (pending != null && pending.server_id == null) {
                    db.messageQueries.promote(serverId, createdAt, pending.local_id)
                } else {
                    db.messageQueries.insertRemote(serverId, dialogId, senderId, text, createdAt)
                }
            }
            db.dialogQueries.bumpLastMessageAt(createdAt, dialogId)
        }
    }

    suspend fun markSelfRead(dialogId: String, upToMessageId: String, readAt: Long): Unit =
        withContext(dispatcher) {
            db.dialogQueries.advanceSelfRead(readAt, upToMessageId, dialogId)
        }

    suspend fun markSelfReadSent(dialogId: String, upToMessageId: String): Unit =
        withContext(dispatcher) {
            db.dialogQueries.markSelfReadSent(dialogId, upToMessageId)
        }

    suspend fun unsentReads(): List<ReadCursor> = withContext(dispatcher) {
        db.dialogQueries.selectUnsentReads().executeAsList().mapNotNull { row ->
            row.self_read_id?.let { ReadCursor(row.id, it) }
        }
    }

    suspend fun applyReadReceipt(
        dialogId: String,
        userId: String,
        upToMessageId: String,
        readAt: Long,
        selfId: String?
    ): Unit = withContext(dispatcher) {
        if (userId == selfId) {
            db.dialogQueries.applyRemoteSelfRead(readAt, upToMessageId, dialogId)
        } else {
            db.dialogQueries.advancePeerRead(readAt, dialogId)
        }
    }

    suspend fun newestIncoming(dialogId: String, selfId: String): ReadPosition? =
        withContext(dispatcher) {
            db.messageQueries.newestIncoming(dialogId, selfId).executeAsOneOrNull()
                ?.let { row -> row.server_id?.let { ReadPosition(it, row.created_at) } }
        }

    suspend fun upsertDialog(
        id: String,
        type: String,
        title: String?,
        lastMessageAt: Long?,
        peerId: String? = null
    ): Unit =
        withContext(dispatcher) {
            db.transaction {
                db.dialogQueries.upsertFromServer(id, type, title, lastMessageAt, peerId)
                db.sync_stateQueries.insertIfAbsent(id)
            }
        }

    suspend fun setDialogTitle(dialogId: String, title: String): Unit = withContext(dispatcher) {
        db.dialogQueries.setTitle(title, dialogId)
    }

    suspend fun backfillPeers(selfId: String): Unit = withContext(dispatcher) {
        db.dialogQueries.backfillPeersFromMessages(selfId)
    }

    suspend fun dialogIds(): List<String> = withContext(dispatcher) {
        db.dialogQueries.selectIds().executeAsList()
    }

    suspend fun pendingDue(now: Long): List<DomainMessage> = withContext(dispatcher) {
        db.messageQueries.pendingDue(now).executeAsList().map { it.toDomain() }
    }

    suspend fun findByClientMsgId(clientMsgId: String): DomainMessage? = withContext(dispatcher) {
        db.messageQueries.findByClientMsgId(clientMsgId).executeAsOneOrNull()?.toDomain()
    }

    suspend fun markAttempt(localId: Long, nextRetryAt: Long, now: Long): Unit =
        withContext(dispatcher) {
            db.messageQueries.markAttempt(nextRetryAt, now, localId)
        }

    suspend fun scheduleRetry(localId: Long, nextRetryAt: Long, now: Long): Unit =
        withContext(dispatcher) {
            db.messageQueries.scheduleRetry(nextRetryAt, now, localId)
        }

    suspend fun markFailed(localId: Long, reason: String): Unit = withContext(dispatcher) {
        db.messageQueries.markFailed(reason, localId)
    }

    suspend fun failPendingByClientMsgId(clientMsgId: String, reason: String): Unit =
        withContext(dispatcher) {
            db.messageQueries.failByClientMsgId(reason, clientMsgId)
        }

    suspend fun resetForRetry(localId: Long): Unit = withContext(dispatcher) {
        db.messageQueries.resetForRetry(localId)
    }

    suspend fun syncState(dialogId: String): DialogSyncState? = withContext(dispatcher) {
        db.sync_stateQueries.selectByDialog(dialogId).executeAsOneOrNull()?.toDomain()
    }

    suspend fun ensureSyncState(dialogId: String): Unit = withContext(dispatcher) {
        db.sync_stateQueries.insertIfAbsent(dialogId)
    }

    suspend fun updateNewestSynced(dialogId: String, newestSyncedId: String?): Unit =
        withContext(dispatcher) {
            db.sync_stateQueries.updateNewestSynced(newestSyncedId, dialogId)
        }

    suspend fun updateOldestLoaded(dialogId: String, oldestLoadedId: String?, hasMore: Boolean): Unit =
        withContext(dispatcher) {
            db.sync_stateQueries.updateOldestLoaded(oldestLoadedId, if (hasMore) 1L else 0L, dialogId)
        }

    suspend fun countMessages(dialogId: String): Long = withContext(dispatcher) {
        db.messageQueries.countForDialog(dialogId).executeAsOne()
    }

    suspend fun countAllMessages(): Long = withContext(dispatcher) {
        db.messageQueries.countAll().executeAsOne()
    }

    suspend fun clearAll(): Unit = withContext(dispatcher) {
        db.transaction {
            db.messageQueries.deleteAll()
            db.sync_stateQueries.deleteAll()
            db.dialogQueries.deleteAll()
        }
    }
}

private fun Message.toDomain(): DomainMessage =
    DomainMessage(
        localId = local_id,
        serverId = server_id,
        clientMsgId = client_msg_id,
        dialogId = dialog_id,
        senderId = sender_id,
        text = text,
        createdAt = created_at,
        state = MessageState.valueOf(state),
        failReason = fail_reason,
        attemptCount = attempt_count,
        nextRetryAt = next_retry_at,
        firstAttemptAt = first_attempt_at
    )

private fun Sync_state.toDomain(): DialogSyncState =
    DialogSyncState(
        dialogId = dialog_id,
        newestSyncedId = newest_synced_id,
        oldestLoadedId = oldest_loaded_id,
        hasMoreHistory = has_more_history != 0L
    )

private fun SelectAllWithPreview.toDomain(): DialogSummary =
    DialogSummary(
        id = id,
        type = type,
        title = title,
        peerId = peer_id,
        lastMessageAt = last_message_at,
        peerReadAt = peer_read_at,
        unreadCount = unread_count,
        lastMessageText = last_text,
        lastMessageState = last_state?.let { MessageState.valueOf(it) },
        lastMessageSenderId = last_sender_id,
        lastMessageCreatedAt = last_created_at
    )

private fun Dialog.toDomain(): DomainDialog =
    DomainDialog(
        id = id,
        type = type,
        title = title,
        peerId = peer_id,
        lastMessageAt = last_message_at,
        peerReadAt = peer_read_at
    )
