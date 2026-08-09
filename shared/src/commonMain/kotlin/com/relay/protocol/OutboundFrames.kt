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
