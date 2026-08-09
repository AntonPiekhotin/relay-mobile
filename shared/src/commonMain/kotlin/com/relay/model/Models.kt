package com.relay.model

enum class MessageState { PENDING, SENT, FAILED }

data class Message(
    val localId: Long,
    val serverId: String?,
    val clientMsgId: String?,
    val dialogId: String,
    val senderId: String,
    val text: String,
    val createdAt: Long,
    val state: MessageState,
    val failReason: String?,
    val attemptCount: Long,
    val nextRetryAt: Long?,
    val firstAttemptAt: Long?
)

data class Dialog(
    val id: String,
    val type: String,
    val title: String?,
    val lastMessageAt: Long?,
    val unreadCount: Long
)

data class DialogSyncState(
    val dialogId: String,
    val newestSyncedId: String?,
    val oldestLoadedId: String?,
    val hasMoreHistory: Boolean
)
