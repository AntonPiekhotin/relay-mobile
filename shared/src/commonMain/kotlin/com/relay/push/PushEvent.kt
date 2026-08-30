package com.relay.push

const val KIND_MESSAGE_NEW = "MESSAGE_NEW"
const val KIND_INCOMING_CALL = "INCOMING_CALL"
const val KIND_MISSED_CALL = "MISSED_CALL"
const val CALL_KIND_GROUP = "group"

sealed interface PushEvent {
    data class NewMessage(
        val dialogId: String,
        val messageId: String,
        val senderId: String
    ) : PushEvent

    data class IncomingCall(
        val callId: String,
        val callerId: String,
        val media: String,
        val ringExpiresAt: String?,
        val isGroup: Boolean = false
    ) : PushEvent

    data class MissedCall(
        val callId: String,
        val callerId: String,
        val media: String,
        val isGroup: Boolean = false
    ) : PushEvent

    data object Unknown : PushEvent
}

fun parsePushEvent(data: Map<String, String>): PushEvent =
    when (data["kind"]) {
        KIND_MESSAGE_NEW -> PushEvent.NewMessage(
            dialogId = data.required("dialogId") ?: return PushEvent.Unknown,
            messageId = data.required("messageId") ?: return PushEvent.Unknown,
            senderId = data.required("senderId") ?: return PushEvent.Unknown
        )
        KIND_INCOMING_CALL -> PushEvent.IncomingCall(
            callId = data.required("callId") ?: return PushEvent.Unknown,
            callerId = data.required("callerId") ?: return PushEvent.Unknown,
            media = data.required("media") ?: "voice",
            ringExpiresAt = data.required("ringExpiresAt"),
            isGroup = data.required("callKind") == CALL_KIND_GROUP
        )
        KIND_MISSED_CALL -> PushEvent.MissedCall(
            callId = data.required("callId") ?: return PushEvent.Unknown,
            callerId = data.required("callerId") ?: return PushEvent.Unknown,
            media = data.required("media") ?: "voice",
            isGroup = data.required("callKind") == CALL_KIND_GROUP
        )
        else -> PushEvent.Unknown
    }

private fun Map<String, String>.required(key: String): String? =
    this[key]?.takeIf { it.isNotBlank() && it != "null" }
