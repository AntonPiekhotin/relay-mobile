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
}

object CallEndReason {
    const val HANGUP = "hangup"
    const val CALLER_CANCELED = "caller_canceled"
    const val CALLEE_CANCELED = "callee_canceled"
    const val DECLINED = "declined"
    const val RING_TIMEOUT = "ring_timeout"
    const val ANSWERED_ELSEWHERE = "answered_elsewhere"
    const val SETTLED_ELSEWHERE = "settled_elsewhere"
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
    data class Unknown(val verb: String?) : CallSignal
}

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
