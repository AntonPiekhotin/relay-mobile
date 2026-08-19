package com.relay.protocol

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

@OptIn(ExperimentalUuidApi::class)
fun newFrameId(): String = Uuid.random().toString()

@OptIn(ExperimentalTime::class)
fun nowEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()

fun messageSendFrame(clientMsgId: String, dialogId: String, text: String): Envelope =
    Envelope(
        type = FrameType.MESSAGE_SEND,
        id = clientMsgId,
        ts = nowEpochMillis(),
        payload = WireJson.encodeToJsonElement(
            MessageSendPayload.serializer(),
            MessageSendPayload(dialogId = dialogId, text = text)
        )
    )

fun messageReadFrame(
    dialogId: String,
    upToMessageId: String,
    frameId: String = newFrameId()
): Envelope =
    Envelope(
        type = FrameType.MESSAGE_READ,
        id = frameId,
        ts = nowEpochMillis(),
        payload = WireJson.encodeToJsonElement(
            MessageReadPayload.serializer(),
            MessageReadPayload(dialogId = dialogId, upToMessageId = upToMessageId)
        )
    )

fun presenceSubscribeFrame(dialogId: String, frameId: String = newFrameId()): Envelope =
    presenceDialogFrame(FrameType.PRESENCE_SUBSCRIBE, dialogId, frameId)

fun presenceUnsubscribeFrame(dialogId: String, frameId: String = newFrameId()): Envelope =
    presenceDialogFrame(FrameType.PRESENCE_UNSUBSCRIBE, dialogId, frameId)

fun typingStartFrame(dialogId: String, frameId: String = newFrameId()): Envelope =
    Envelope(
        type = FrameType.TYPING_START,
        id = frameId,
        ts = nowEpochMillis(),
        payload = WireJson.encodeToJsonElement(
            TypingStartPayload.serializer(),
            TypingStartPayload(dialogId = dialogId)
        )
    )

private fun presenceDialogFrame(type: String, dialogId: String, frameId: String): Envelope =
    Envelope(
        type = type,
        id = frameId,
        ts = nowEpochMillis(),
        payload = WireJson.encodeToJsonElement(
            PresenceDialogPayload.serializer(),
            PresenceDialogPayload(dialogId = dialogId)
        )
    )

fun pingFrame(frameId: String = newFrameId()): Envelope =
    Envelope(
        type = FrameType.PING,
        id = frameId,
        ts = nowEpochMillis(),
        payload = emptyPayload()
    )

fun encodeFrame(envelope: Envelope): String =
    WireJson.encodeToString(Envelope.serializer(), envelope)

private fun emptyPayload(): JsonObject = buildJsonObject { }
