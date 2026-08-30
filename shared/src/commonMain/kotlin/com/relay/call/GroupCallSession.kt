package com.relay.call

import com.relay.protocol.GroupParticipantState

enum class GroupCallStage { STARTING, INCOMING, CONNECTING, ACTIVE, ENDED }

data class GroupParticipant(
    val userId: String,
    val state: String
)

data class GroupCallSession(
    val callId: String,
    val selfId: String,
    val initiatorId: String,
    val media: String,
    val direction: CallDirection,
    val stage: GroupCallStage,
    val participants: List<GroupParticipant>,
    val startedAt: Long,
    val answeredAt: Long? = null,
    val ringExpiresAt: Long? = null,
    val muted: Boolean = false,
    val speakerOn: Boolean = false,
    val endReason: String? = null,
    val failure: String? = null
) {
    val isSettled: Boolean get() = stage == GroupCallStage.ENDED
    val others: List<GroupParticipant> get() = participants.filter { it.userId != selfId }
    val joinedOthersCount: Int get() = others.count { it.state == GroupParticipantState.JOINED }
}
