package com.relay.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.relay.model.DialogSyncState
import com.relay.model.MessageState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.relay.model.Dialog as DomainDialog
import com.relay.model.Message as DomainMessage

private const val DEFAULT_DIALOG_TYPE = "direct"
private const val DEFAULT_VISIBLE_MESSAGES = 500L

class MessageStore(
    private val db: RelayDb,
    private val dispatcher: CoroutineDispatcher
) {
    fun observeDialogs(): Flow<List<DomainDialog>> =
        db.dialogQueries.selectAll()
            .asFlow()
            .mapToList(dispatcher)
            .map { rows -> rows.map { it.toDomain() } }

    fun observeMessages(dialogId: String, limit: Long = DEFAULT_VISIBLE_MESSAGES): Flow<List<DomainMessage>> =
        db.messageQueries.selectForDialog(dialogId, limit)
            .asFlow()
            .mapToList(dispatcher)
            .map { rows -> rows.map { it.toDomain() } }

    suspend fun insertPending(
        clientMsgId: String,
        dialogId: String,
        senderId: String,
        text: String,
        createdAt: Long
    ): Unit = withContext(dispatcher) {
        db.transaction {
            db.dialogQueries.insertIfAbsent(dialogId, DEFAULT_DIALOG_TYPE, null, null)
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
        createdAt: Long
    ): Unit = withContext(dispatcher) {
        db.transaction {
            db.dialogQueries.insertIfAbsent(dialogId, DEFAULT_DIALOG_TYPE, null, null)
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

    suspend fun upsertDialog(id: String, type: String, title: String?, lastMessageAt: Long?): Unit =
        withContext(dispatcher) {
            db.transaction {
                db.dialogQueries.upsertFromServer(id, type, title, lastMessageAt)
                db.sync_stateQueries.insertIfAbsent(id)
            }
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
        db.sync_stateQueries.selectByDialog(dialogId).executeAsOneOrNull()?.let {
            DialogSyncState(
                dialogId = it.dialog_id,
                newestSyncedId = it.newest_synced_id,
                oldestLoadedId = it.oldest_loaded_id,
                hasMoreHistory = it.has_more_history != 0L
            )
        }
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

private fun Dialog.toDomain(): DomainDialog =
    DomainDialog(
        id = id,
        type = type,
        title = title,
        lastMessageAt = last_message_at,
        unreadCount = unread_count
    )
