package com.relay.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

const val MEDIA_AUDIO = "audio"
const val MEDIA_VIDEO = "video"

object CallVerb {
    const val INVITE = "invite"
    const val ACCEPT = "accept"
    const val REJECT = "reject"
    const val ICE = "ice"
    const val HANGUP = "hangup"
    const val CANCEL = "cancel"
    const val MISSED = "missed"
    const val STATE = "state"
    const val GROUP_INVITE = "group_invite"
    const val PARTICIPANT_JOINED = "participant_joined"
    const val PARTICIPANT_LEFT = "participant_left"
    const val PARTICIPANT_DECLINED = "participant_declined"
    const val PARTICIPANT_MISSED = "participant_missed"
    const val GROUP_ENDED = "group_ended"
}

object CallEndReason {
    const val HANGUP = "hangup"
    const val CALLER_CANCELED = "caller_canceled"
    const val CALLEE_CANCELED = "callee_canceled"
    const val DECLINED = "declined"
    const val RING_TIMEOUT = "ring_timeout"
    const val ANSWERED_ELSEWHERE = "answered_elsewhere"
    const val SETTLED_ELSEWHERE = "settled_elsewhere"
    const val ALL_DECLINED = "all_declined"
    const val ALL_LEFT = "all_left"
}

object GroupParticipantState {
    const val INVITED = "invited"
    const val JOINED = "joined"
    const val DECLINED = "declined"
    const val MISSED = "missed"
    const val LEFT = "left"
}

object CallStatusWire {
    const val RINGING = "ringing"
    const val ANSWERED = "answered"
    const val REJECTED = "rejected"
    const val MISSED = "missed"
    const val ENDED = "ended"
}

@Serializable
data class CallInvitePayload(
    @SerialName("call_id") val callId: String,
    @SerialName("callee_id") val calleeId: String,
    @SerialName("media") val media: String,
    @SerialName("sdp") val sdp: String,
    @SerialName("dialog_id") val dialogId: String? = null
)

@Serializable
data class CallAcceptPayload(
    @SerialName("call_id") val callId: String,
    @SerialName("sdp") val sdp: String
)

@Serializable
data class CallRejectPayload(
    @SerialName("call_id") val callId: String,
    @SerialName("reason") val reason: String? = null
)

@Serializable
data class CallIcePayload(
    @SerialName("call_id") val callId: String,
    @SerialName("candidate") val candidate: JsonObject
)

@Serializable
data class CallHangupPayload(
    @SerialName("call_id") val callId: String,
    @SerialName("reason") val reason: String? = null
)

@Serializable
data class CallSignalPayload(
    @SerialName("call_id") val callId: String,
    @SerialName("from_user_id") val fromUserId: String,
    @SerialName("signal") val signal: JsonObject
)

sealed interface CallSignal {
    data class Invite(
        val media: String,
        val sdp: String,
        val dialogId: String?,
        val startedAt: String?,
        val ringExpiresAt: String?
    ) : CallSignal

    data class Accept(val sdp: String) : CallSignal
    data class Reject(val reason: String?) : CallSignal
    data class Ice(val candidate: JsonObject) : CallSignal
    data class Hangup(val reason: String?, val durationSeconds: Long?) : CallSignal
    data class Cancel(val reason: String?) : CallSignal
    data class Missed(val reason: String?) : CallSignal
    data class State(val status: String?) : CallSignal

    data class GroupInvite(
        val media: String,
        val startedAt: String?,
        val ringExpiresAt: String?,
        val participants: List<GroupParticipantWire>
    ) : CallSignal

    data class ParticipantJoined(val userId: String) : CallSignal
    data class ParticipantLeft(val userId: String, val reason: String?) : CallSignal
    data class ParticipantDeclined(val userId: String, val reason: String?) : CallSignal
    data class ParticipantMissed(val userId: String) : CallSignal
    data class GroupEnded(val reason: String?, val durationSeconds: Long?) : CallSignal
    data class Unknown(val verb: String?) : CallSignal
}

@Serializable
data class GroupParticipantWire(
    @SerialName("user_id") val userId: String,
    @SerialName("state") val state: String = GroupParticipantState.INVITED
)

@Serializable
private data class InviteSignalBody(
    @SerialName("media") val media: String = MEDIA_AUDIO,
    @SerialName("sdp") val sdp: String,
    @SerialName("dialog_id") val dialogId: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("ring_expires_at") val ringExpiresAt: String? = null
)

@Serializable
private data class SdpSignalBody(
    @SerialName("sdp") val sdp: String
)

@Serializable
private data class IceSignalBody(
    @SerialName("candidate") val candidate: JsonObject
)

@Serializable
private data class HangupSignalBody(
    @SerialName("reason") val reason: String? = null,
    @SerialName("duration_s") val durationSeconds: Long? = null
)

@Serializable
private data class GroupInviteSignalBody(
    @SerialName("media") val media: String = MEDIA_AUDIO,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("ring_expires_at") val ringExpiresAt: String? = null,
    @SerialName("participants") val participants: List<GroupParticipantWire> = emptyList()
)

@Serializable
private data class ParticipantSignalBody(
    @SerialName("user_id") val userId: String,
    @SerialName("reason") val reason: String? = null
)

fun parseCallSignal(signal: JsonObject): CallSignal {
    val verb = (signal[CALL_SIGNAL_VERB] as? JsonPrimitive)?.takeIf { it.isString }?.content
    return try {
        when (verb) {
            CallVerb.INVITE -> signal.decode(InviteSignalBody.serializer()).let {
                CallSignal.Invite(
                    media = it.media,
                    sdp = it.sdp,
                    dialogId = it.dialogId,
                    startedAt = it.startedAt,
                    ringExpiresAt = it.ringExpiresAt
                )
            }
            CallVerb.ACCEPT -> CallSignal.Accept(signal.decode(SdpSignalBody.serializer()).sdp)
            CallVerb.REJECT -> CallSignal.Reject(signal.stringOrNull(CALL_SIGNAL_REASON))
            CallVerb.ICE -> CallSignal.Ice(signal.decode(IceSignalBody.serializer()).candidate)
            CallVerb.HANGUP -> signal.decode(HangupSignalBody.serializer()).let {
                CallSignal.Hangup(reason = it.reason, durationSeconds = it.durationSeconds)
            }
            CallVerb.CANCEL -> CallSignal.Cancel(signal.stringOrNull(CALL_SIGNAL_REASON))
            CallVerb.MISSED -> CallSignal.Missed(signal.stringOrNull(CALL_SIGNAL_REASON))
            CallVerb.STATE -> CallSignal.State(signal.stringOrNull(CALL_SIGNAL_STATUS))
            CallVerb.GROUP_INVITE -> signal.decode(GroupInviteSignalBody.serializer()).let {
                CallSignal.GroupInvite(
                    media = it.media,
                    startedAt = it.startedAt,
                    ringExpiresAt = it.ringExpiresAt,
                    participants = it.participants
                )
            }
            CallVerb.PARTICIPANT_JOINED ->
                CallSignal.ParticipantJoined(signal.decode(ParticipantSignalBody.serializer()).userId)
            CallVerb.PARTICIPANT_LEFT -> signal.decode(ParticipantSignalBody.serializer()).let {
                CallSignal.ParticipantLeft(userId = it.userId, reason = it.reason)
            }
            CallVerb.PARTICIPANT_DECLINED -> signal.decode(ParticipantSignalBody.serializer()).let {
                CallSignal.ParticipantDeclined(userId = it.userId, reason = it.reason)
            }
            CallVerb.PARTICIPANT_MISSED ->
                CallSignal.ParticipantMissed(signal.decode(ParticipantSignalBody.serializer()).userId)
            CallVerb.GROUP_ENDED -> signal.decode(HangupSignalBody.serializer()).let {
                CallSignal.GroupEnded(reason = it.reason, durationSeconds = it.durationSeconds)
            }
            else -> CallSignal.Unknown(verb)
        }
    } catch (e: SerializationException) {
        CallSignal.Unknown(verb)
    } catch (e: IllegalArgumentException) {
        CallSignal.Unknown(verb)
    }
}

private const val CALL_SIGNAL_VERB = "verb"
private const val CALL_SIGNAL_REASON = "reason"
private const val CALL_SIGNAL_STATUS = "status"

private fun <T> JsonObject.decode(serializer: kotlinx.serialization.KSerializer<T>): T =
    WireJson.decodeFromJsonElement(serializer, this)

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
