package com.relay.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

sealed interface InboundFrame {
    data class SessionConnected(val payload: SessionConnectedPayload) : InboundFrame
    data class Ack(val payload: AckPayload) : InboundFrame
    data class MessageNew(val payload: MessageNewPayload) : InboundFrame
    data class MessageSystem(val payload: MessageSystemPayload) : InboundFrame
    data class DialogDeleted(val payload: DialogDeletedPayload) : InboundFrame
    data class MessageRead(val payload: MessageReadReceiptPayload) : InboundFrame
    data class Error(val payload: ErrorPayload) : InboundFrame
    data class Pong(val payload: PongPayload) : InboundFrame
    data class CallSignalFrame(
        val callId: String,
        val fromUserId: String,
        val signal: CallSignal
    ) : InboundFrame
    data class PresenceUpdate(val payload: PresenceUpdatePayload) : InboundFrame
    data class TypingStart(val payload: TypingReceiptPayload) : InboundFrame
    data class Unknown(val type: String) : InboundFrame
    data class Malformed(val rawText: String, val cause: String) : InboundFrame
}

fun parseInboundFrame(text: String): InboundFrame {
    val envelope = try {
        WireJson.decodeFromString(Envelope.serializer(), text)
    } catch (e: SerializationException) {
        return InboundFrame.Malformed(text, e.message ?: "unparseable envelope")
    } catch (e: IllegalArgumentException) {
        return InboundFrame.Malformed(text, e.message ?: "invalid envelope")
    }
    val payload = envelope.payload ?: JsonNull
    return try {
        when (envelope.type) {
            FrameType.SESSION_CONNECTED ->
                InboundFrame.SessionConnected(payload.decode(SessionConnectedPayload.serializer()))
            FrameType.ACK -> InboundFrame.Ack(payload.decode(AckPayload.serializer()))
            FrameType.MESSAGE_NEW -> InboundFrame.MessageNew(payload.decode(MessageNewPayload.serializer()))
            FrameType.MESSAGE_SYSTEM ->
                InboundFrame.MessageSystem(payload.decode(MessageSystemPayload.serializer()))
            FrameType.DIALOG_DELETED ->
                InboundFrame.DialogDeleted(payload.decode(DialogDeletedPayload.serializer()))
            FrameType.MESSAGE_READ ->
                InboundFrame.MessageRead(payload.decode(MessageReadReceiptPayload.serializer()))
            FrameType.ERROR -> InboundFrame.Error(payload.decode(ErrorPayload.serializer()))
            FrameType.CALL_SIGNAL -> payload.decode(CallSignalPayload.serializer()).let {
                InboundFrame.CallSignalFrame(
                    callId = it.callId,
                    fromUserId = it.fromUserId,
                    signal = parseCallSignal(it.signal)
                )
            }
            FrameType.PRESENCE_UPDATE ->
                InboundFrame.PresenceUpdate(payload.decode(PresenceUpdatePayload.serializer()))
            FrameType.TYPING_START ->
                InboundFrame.TypingStart(payload.decode(TypingReceiptPayload.serializer()))
            FrameType.PONG -> InboundFrame.Pong(
                if (payload is JsonNull) PongPayload() else payload.decode(PongPayload.serializer())
            )
            else -> InboundFrame.Unknown(envelope.type)
        }
    } catch (e: SerializationException) {
        InboundFrame.Malformed(text, e.message ?: "unparseable payload")
    } catch (e: IllegalArgumentException) {
        InboundFrame.Malformed(text, e.message ?: "invalid payload")
    }
}

private fun <T> JsonElement.decode(serializer: kotlinx.serialization.KSerializer<T>): T =
    WireJson.decodeFromJsonElement(serializer, this)
