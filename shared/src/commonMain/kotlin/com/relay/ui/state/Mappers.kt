package com.relay.ui.state

import com.relay.model.Contact
import com.relay.model.DialogSummary
import com.relay.model.Message
import com.relay.model.UserProfile
import com.relay.model.UserSearchResult
import com.relay.model.UserSummary
import com.relay.repository.ConnectionPhase
import com.relay.ui.format.dayKeyOf
import com.relay.ui.format.formatClockTime
import com.relay.ui.format.formatDaySeparator
import com.relay.ui.format.formatFullDate
import com.relay.ui.format.formatListTimestamp

const val UNRESOLVED_PEER_TITLE = "Unknown user"

fun dialogTitleOf(title: String?): String =
    title?.takeIf { it.isNotBlank() } ?: UNRESOLVED_PEER_TITLE

fun ConnectionPhase.toConnectionUi(): ConnectionUi = when (this) {
    ConnectionPhase.LIVE -> ConnectionUi.Live
    ConnectionPhase.RECONNECTING -> ConnectionUi.Reconnecting
    ConnectionPhase.UNKNOWN -> ConnectionUi.Unknown
}

fun List<Message>.toMessageUi(selfId: String?, nowMillis: Long): List<MessageUi> =
    mapIndexed { index, message ->
        val olderNeighbour = getOrNull(index + 1)
        val startsDay = olderNeighbour == null ||
            dayKeyOf(olderNeighbour.createdAt) != dayKeyOf(message.createdAt)
        MessageUi(
            localId = message.localId,
            text = message.text,
            isMine = selfId != null && message.senderId == selfId,
            timestamp = formatClockTime(message.createdAt),
            status = message.state,
            failReason = message.failReason,
            daySeparator = if (startsDay) formatDaySeparator(message.createdAt, nowMillis) else null
        )
    }

fun List<DialogSummary>.toDialogUi(selfId: String?, nowMillis: Long): List<DialogUi> =
    map { summary ->
        DialogUi(
            id = summary.id,
            title = dialogTitleOf(summary.title),
            preview = summary.lastMessageText ?: "No messages yet",
            timestamp = summary.lastMessageAt?.let { formatListTimestamp(it, nowMillis) } ?: "",
            unreadCount = summary.unreadCount,
            previewIsMine = selfId != null && summary.lastMessageSenderId == selfId,
            previewStatus = summary.lastMessageState
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
