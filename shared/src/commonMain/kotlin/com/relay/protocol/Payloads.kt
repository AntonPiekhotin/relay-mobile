package com.relay.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SessionConnectedPayload(
    @SerialName("user_id") val userId: String,
    @SerialName("session_id") val sessionId: String
)

@Serializable
data class MessageSendPayload(
    @SerialName("dialog_id") val dialogId: String,
    @SerialName("text") val text: String
)

@Serializable
data class AckPayload(
    @SerialName("client_msg_id") val clientMsgId: String,
    @SerialName("message_id") val messageId: String,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class MessageNewPayload(
    @SerialName("message_id") val messageId: String,
    @SerialName("dialog_id") val dialogId: String,
    @SerialName("sender_id") val senderId: String,
    @SerialName("text") val text: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("client_msg_id") val clientMsgId: String? = null
)

@Serializable
data class MessageReadPayload(
    @SerialName("dialog_id") val dialogId: String,
    @SerialName("up_to_message_id") val upToMessageId: String
)

@Serializable
data class MessageReadReceiptPayload(
    @SerialName("dialog_id") val dialogId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("up_to_message_id") val upToMessageId: String,
    @SerialName("read_at") val readAt: String
)

@Serializable
data class PresenceDialogPayload(
    @SerialName("dialog_id") val dialogId: String
)

@Serializable
data class PresenceUpdatePayload(
    @SerialName("user_id") val userId: String,
    @SerialName("status") val status: String,
    @SerialName("last_seen") val lastSeen: String? = null
)

@Serializable
data class TypingStartPayload(
    @SerialName("dialog_id") val dialogId: String
)

@Serializable
data class TypingReceiptPayload(
    @SerialName("dialog_id") val dialogId: String,
    @SerialName("user_id") val userId: String
)

object PresenceStatusWire {
    const val ONLINE = "online"
    const val OFFLINE = "offline"
}

@Serializable
data class ErrorPayload(
    @SerialName("code") val code: String,
    @SerialName("message") val message: String? = null,
    @SerialName("ref_id") val refId: String? = null
)

@Serializable
data class PongPayload(
    @SerialName("ref_id") val refId: String? = null
)
