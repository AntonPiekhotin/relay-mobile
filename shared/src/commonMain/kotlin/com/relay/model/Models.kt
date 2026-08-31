package com.relay.model

enum class MessageState { PENDING, SENT, FAILED }

object DialogType {
    const val DIRECT = "direct"
    const val GROUP = "group"
}

object MessageKind {
    const val USER = "user"
    const val GROUP_CREATED = "group_created"
    const val MEMBER_ADDED = "member_added"
    const val MEMBER_REMOVED = "member_removed"
    const val MEMBER_LEFT = "member_left"
    const val GROUP_RENAMED = "group_renamed"

    fun isSystem(kind: String): Boolean = kind != USER
}

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
    val firstAttemptAt: Long?,
    val kind: String = MessageKind.USER,
    val targetUserId: String? = null
)

data class Dialog(
    val id: String,
    val type: String,
    val title: String?,
    val peerId: String?,
    val lastMessageAt: Long?,
    val peerReadAt: Long?
)

data class DialogSummary(
    val id: String,
    val type: String,
    val title: String?,
    val peerId: String?,
    val lastMessageAt: Long?,
    val peerReadAt: Long?,
    val unreadCount: Long,
    val lastMessageText: String?,
    val lastMessageState: MessageState?,
    val lastMessageSenderId: String?,
    val lastMessageCreatedAt: Long?,
    val lastMessageKind: String? = null
)

data class ReadCursor(
    val dialogId: String,
    val upToMessageId: String
)

data class UnnamedDialog(
    val dialogId: String,
    val peerId: String
)

data class DialogSyncState(
    val dialogId: String,
    val newestSyncedId: String?,
    val oldestLoadedId: String?,
    val hasMoreHistory: Boolean
)
