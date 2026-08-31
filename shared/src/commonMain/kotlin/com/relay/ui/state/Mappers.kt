package com.relay.ui.state

import com.relay.model.Contact
import com.relay.model.DialogSummary
import com.relay.model.Message
import com.relay.model.MessageKind
import com.relay.model.MessageState
import com.relay.model.UserProfile
import com.relay.model.UserSearchResult
import com.relay.model.UserSummary
import com.relay.network.CallHistoryEntryResponse
import com.relay.presence.PeerPresence
import com.relay.repository.ConnectionPhase
import com.relay.ui.format.dayKeyOf
import com.relay.ui.format.formatClockTime
import com.relay.ui.format.formatDaySeparator
import com.relay.ui.format.formatDuration
import com.relay.ui.format.formatFullDate
import com.relay.ui.format.formatLastSeen
import com.relay.ui.format.formatListTimestamp
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

const val UNRESOLVED_PEER_TITLE = "Unknown user"
const val TYPING_SUBTITLE = "typing…"
const val ONLINE_SUBTITLE = "online"
const val OFFLINE_SUBTITLE = "offline"
const val UNRESOLVED_MEMBER_NAME = "Someone"
const val SELF_MEMBER_NAME = "You"

private const val MISSED_STATUS = "MISSED"
private const val OUTGOING_DIRECTION = "OUTGOING"
private const val MILLIS_PER_SECOND = 1000L

fun dialogTitleOf(title: String?): String =
    title?.takeIf { it.isNotBlank() } ?: UNRESOLVED_PEER_TITLE

fun chatSubtitleOf(presence: PeerPresence?, isTyping: Boolean, nowMillis: Long): String? =
    when {
        isTyping -> TYPING_SUBTITLE
        presence == null -> null
        presence.online -> ONLINE_SUBTITLE
        presence.lastSeenAt != null -> "last seen ${formatLastSeen(presence.lastSeenAt, nowMillis)}"
        else -> OFFLINE_SUBTITLE
    }

fun ConnectionPhase.toConnectionUi(): ConnectionUi = when (this) {
    ConnectionPhase.LIVE -> ConnectionUi.Live
    ConnectionPhase.RECONNECTING -> ConnectionUi.Reconnecting
    ConnectionPhase.UNKNOWN -> ConnectionUi.Unknown
}

fun messageStatusUi(state: MessageState, createdAt: Long, peerReadAt: Long?): MessageStatusUi =
    when (state) {
        MessageState.PENDING -> MessageStatusUi.SENDING
        MessageState.FAILED -> MessageStatusUi.FAILED
        MessageState.SENT ->
            if (peerReadAt != null && createdAt <= peerReadAt) {
                MessageStatusUi.READ
            } else {
                MessageStatusUi.SENT
            }
    }

fun systemMessageLabel(
    kind: String,
    actorName: String,
    targetName: String?,
    newTitle: String
): String = when (kind) {
    MessageKind.GROUP_CREATED -> "$actorName created the group"
    MessageKind.GROUP_RENAMED -> "$actorName renamed the group to “$newTitle”"
    MessageKind.MEMBER_ADDED -> "$actorName added ${targetName ?: UNRESOLVED_MEMBER_NAME}"
    MessageKind.MEMBER_REMOVED -> "$actorName removed ${targetName ?: UNRESOLVED_MEMBER_NAME}"
    MessageKind.MEMBER_LEFT -> "$actorName left"
    else -> "$actorName updated the conversation"
}

fun systemPreviewOf(kind: String): String = when (kind) {
    MessageKind.GROUP_CREATED -> "Group created"
    MessageKind.GROUP_RENAMED -> "Group renamed"
    MessageKind.MEMBER_ADDED -> "Member added"
    MessageKind.MEMBER_REMOVED -> "Member removed"
    MessageKind.MEMBER_LEFT -> "Member left"
    else -> "Conversation updated"
}

private fun memberNameOf(userId: String?, selfId: String?, names: Map<String, String>): String? =
    when {
        userId == null -> null
        userId == selfId -> SELF_MEMBER_NAME
        else -> names[userId] ?: UNRESOLVED_MEMBER_NAME
    }

fun List<Message>.toMessageUi(
    selfId: String?,
    nowMillis: Long,
    peerReadAt: Long?,
    isGroup: Boolean = false,
    names: Map<String, String> = emptyMap()
): List<MessageUi> =
    mapIndexed { index, message ->
        val olderNeighbour = getOrNull(index + 1)
        val startsDay = olderNeighbour == null ||
            dayKeyOf(olderNeighbour.createdAt) != dayKeyOf(message.createdAt)
        val isSystem = MessageKind.isSystem(message.kind)
        val isMine = !isSystem && selfId != null && message.senderId == selfId
        val showSender = isGroup && !isSystem && !isMine &&
            (olderNeighbour == null ||
                olderNeighbour.senderId != message.senderId ||
                MessageKind.isSystem(olderNeighbour.kind))
        MessageUi(
            localId = message.localId,
            text = if (isSystem) {
                systemMessageLabel(
                    kind = message.kind,
                    actorName = memberNameOf(message.senderId, selfId, names)
                        ?: UNRESOLVED_MEMBER_NAME,
                    targetName = memberNameOf(message.targetUserId, selfId, names),
                    newTitle = message.text
                )
            } else {
                message.text
            },
            isMine = isMine,
            timestamp = formatClockTime(message.createdAt),
            status = messageStatusUi(message.state, message.createdAt, peerReadAt),
            failReason = message.failReason,
            daySeparator = if (startsDay) formatDaySeparator(message.createdAt, nowMillis) else null,
            senderName = if (showSender) names[message.senderId] else null,
            isSystem = isSystem
        )
    }

fun List<DialogSummary>.toDialogUi(selfId: String?, nowMillis: Long): List<DialogUi> =
    map { summary ->
        val previewIsSystem = summary.lastMessageKind?.let { MessageKind.isSystem(it) } ?: false
        DialogUi(
            id = summary.id,
            title = dialogTitleOf(summary.title),
            preview = when {
                previewIsSystem -> systemPreviewOf(summary.lastMessageKind.orEmpty())
                else -> summary.lastMessageText ?: "No messages yet"
            },
            timestamp = summary.lastMessageAt?.let { formatListTimestamp(it, nowMillis) } ?: "",
            unreadCount = summary.unreadCount,
            previewIsMine = !previewIsSystem && selfId != null && summary.lastMessageSenderId == selfId,
            previewStatus = summary.lastMessageState
                ?.takeUnless { previewIsSystem }
                ?.let { messageStatusUi(it, summary.lastMessageCreatedAt ?: 0L, summary.peerReadAt) }
        )
    }

fun UserSummary.toPersonUi(isContact: Boolean): PersonUi =
    PersonUi(id = id, name = displayName, isContact = isContact)

fun UserProfile.toProfileUi(): ProfileUi =
    ProfileUi(
        name = user.displayName,
        email = user.email,
        memberSince = createdAtMillis?.let { formatFullDate(it) }
    )

fun List<Contact>.toPersonUi(): List<PersonUi> = map { it.user.toPersonUi(isContact = true) }

fun List<UserSearchResult>.searchResultsToPersonUi(contactIds: Set<String>): List<PersonUi> =
    map { it.user.toPersonUi(isContact = it.user.id in contactIds) }

fun CallHistoryEntryResponse.toCallLogUi(peerNames: Map<String, String>, nowMillis: Long): CallLogUi {
    val missed = status.equals(MISSED_STATUS, ignoreCase = true)
    val directionWord = when {
        missed -> "Missed"
        direction.equals(OUTGOING_DIRECTION, ignoreCase = true) -> "Outgoing"
        else -> "Incoming"
    }
    val durationLabel = durationSeconds
        ?.takeIf { it > 0 && !missed }
        ?.let { formatDuration(it * MILLIS_PER_SECOND) }
    return CallLogUi(
        id = id,
        peerId = peerId,
        dialogId = dialogId,
        peerName = peerId?.let(peerNames::get) ?: UNRESOLVED_PEER_TITLE,
        isMissed = missed,
        subtitle = listOfNotNull(directionWord, durationLabel).joinToString(" · "),
        timestamp = parseEpochMillis(startedAt)?.let { formatListTimestamp(it, nowMillis) } ?: ""
    )
}

@OptIn(ExperimentalTime::class)
private fun parseEpochMillis(iso: String): Long? =
    try {
        Instant.parse(iso).toEpochMilliseconds()
    } catch (e: IllegalArgumentException) {
        null
    }
