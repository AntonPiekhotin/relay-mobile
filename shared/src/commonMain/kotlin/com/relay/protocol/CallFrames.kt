package com.relay.protocol

import kotlinx.serialization.json.JsonObject

fun callInviteFrame(
    callId: String,
    calleeId: String,
    media: String,
    sdp: String,
    dialogId: String?,
    frameId: String = newFrameId()
): Envelope =
    callFrame(
        type = FrameType.CALL_INVITE,
        frameId = frameId,
        payload = WireJson.encodeToJsonElement(
            CallInvitePayload.serializer(),
            CallInvitePayload(
                callId = callId,
                calleeId = calleeId,
                media = media,
                sdp = sdp,
                dialogId = dialogId
            )
        )
    )

fun callAcceptFrame(callId: String, sdp: String, frameId: String = newFrameId()): Envelope =
    callFrame(
        type = FrameType.CALL_ACCEPT,
        frameId = frameId,
        payload = WireJson.encodeToJsonElement(
            CallAcceptPayload.serializer(),
            CallAcceptPayload(callId = callId, sdp = sdp)
        )
    )

fun callRejectFrame(callId: String, reason: String?, frameId: String = newFrameId()): Envelope =
    callFrame(
        type = FrameType.CALL_REJECT,
        frameId = frameId,
        payload = WireJson.encodeToJsonElement(
            CallRejectPayload.serializer(),
            CallRejectPayload(callId = callId, reason = reason)
        )
    )

fun callIceFrame(callId: String, candidate: JsonObject, frameId: String = newFrameId()): Envelope =
    callFrame(
        type = FrameType.CALL_ICE,
        frameId = frameId,
        payload = WireJson.encodeToJsonElement(
            CallIcePayload.serializer(),
            CallIcePayload(callId = callId, candidate = candidate)
        )
    )

fun callHangupFrame(callId: String, reason: String?, frameId: String = newFrameId()): Envelope =
    callFrame(
        type = FrameType.CALL_HANGUP,
        frameId = frameId,
        payload = WireJson.encodeToJsonElement(
            CallHangupPayload.serializer(),
            CallHangupPayload(callId = callId, reason = reason)
        )
    )

private fun callFrame(
    type: String,
    frameId: String,
    payload: kotlinx.serialization.json.JsonElement
): Envelope =
    Envelope(type = type, id = frameId, ts = nowEpochMillis(), payload = payload)
