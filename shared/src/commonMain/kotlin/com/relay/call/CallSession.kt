package com.relay.call

enum class CallStage { DIALING, RINGING, INCOMING, CONNECTING, ACTIVE, ENDED }

enum class CallDirection { OUTGOING, INCOMING }

data class CallSession(
    val callId: String,
    val peerId: String,
    val dialogId: String?,
    val media: String,
    val direction: CallDirection,
    val stage: CallStage,
    val startedAt: Long,
    val answeredAt: Long? = null,
    val ringExpiresAt: Long? = null,
    val muted: Boolean = false,
    val speakerOn: Boolean = false,
    val endReason: String? = null,
    val failure: String? = null
) {
    val isOutgoing: Boolean get() = direction == CallDirection.OUTGOING
    val isSettled: Boolean get() = stage == CallStage.ENDED
}

sealed interface PlaceCallResult {
    data class Started(val callId: String) : PlaceCallResult
    data class Rejected(val reason: String) : PlaceCallResult
}
