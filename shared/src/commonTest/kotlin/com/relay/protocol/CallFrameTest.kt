package com.relay.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val CALL_ID = "call-1"

class CallFrameTest {

    @Test
    fun anInviteEncodesSnakeCasePayloadKeys() {
        val encoded = encodeFrame(
            callInviteFrame(
                callId = CALL_ID,
                calleeId = "peer-1",
                media = MEDIA_AUDIO,
                sdp = "v=0",
                dialogId = "dialog-1"
            )
        )

        val payload = WireJson.parseToJsonElement(encoded).jsonObject["payload"]!!.jsonObject
        assertEquals(CALL_ID, payload["call_id"]?.jsonPrimitive?.content)
        assertEquals("peer-1", payload["callee_id"]?.jsonPrimitive?.content)
        assertEquals("audio", payload["media"]?.jsonPrimitive?.content)
        assertEquals("dialog-1", payload["dialog_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun anInviteWithNoDialogOmitsTheKeyRatherThanSendingNull() {
        val encoded = encodeFrame(
            callInviteFrame(CALL_ID, "peer-1", MEDIA_AUDIO, "v=0", dialogId = null)
        )

        val payload = WireJson.parseToJsonElement(encoded).jsonObject["payload"]!!.jsonObject
        assertNull(payload["dialog_id"])
    }

    @Test
    fun anIceFrameRelaysTheCandidateObjectUntouched() {
        val candidate = WireJson.parseToJsonElement(
            """{"candidate":"candidate:1 1 udp","sdpMid":"0","sdpMLineIndex":0}"""
        ).jsonObject

        val encoded = encodeFrame(callIceFrame(CALL_ID, candidate))

        val relayed = WireJson.parseToJsonElement(encoded)
            .jsonObject["payload"]!!.jsonObject["candidate"]!!.jsonObject
        assertEquals(candidate, relayed)
    }

    @Test
    fun anInviteSignalIsParsedWithItsRingDeadline() {
        val frame = parseInboundFrame(
            """
            {"v":1,"type":"call.signal","ts":1730000000000,
             "payload":{"call_id":"$CALL_ID","from_user_id":"peer-1",
               "signal":{"verb":"invite","media":"audio","sdp":"v=0",
                 "dialog_id":"dialog-1","started_at":"2026-07-26T10:00:00Z",
                 "ring_expires_at":"2026-07-26T10:00:40Z"}}}
            """.trimIndent()
        )

        val signal = assertIs<InboundFrame.CallSignalFrame>(frame)
        assertEquals(CALL_ID, signal.callId)
        assertEquals("peer-1", signal.fromUserId)
        val invite = assertIs<CallSignal.Invite>(signal.signal)
        assertEquals("v=0", invite.sdp)
        assertEquals("dialog-1", invite.dialogId)
        assertEquals("2026-07-26T10:00:40Z", invite.ringExpiresAt)
    }

    @Test
    fun aHangupSignalCarriesTalkTime() {
        val frame = parseInboundFrame(
            """
            {"v":1,"type":"call.signal","ts":1,
             "payload":{"call_id":"$CALL_ID","from_user_id":"peer-1",
               "signal":{"verb":"hangup","reason":"hangup","duration_s":42}}}
            """.trimIndent()
        )

        val hangup = assertIs<CallSignal.Hangup>(
            assertIs<InboundFrame.CallSignalFrame>(frame).signal
        )
        assertEquals(42, hangup.durationSeconds)
        assertEquals(CallEndReason.HANGUP, hangup.reason)
    }

    @Test
    fun aMissedSignalWithoutTalkTimeStillParses() {
        val frame = parseInboundFrame(
            """
            {"v":1,"type":"call.signal","ts":1,
             "payload":{"call_id":"$CALL_ID","from_user_id":"peer-1",
               "signal":{"verb":"missed","reason":"ring_timeout"}}}
            """.trimIndent()
        )

        val missed = assertIs<CallSignal.Missed>(
            assertIs<InboundFrame.CallSignalFrame>(frame).signal
        )
        assertEquals(CallEndReason.RING_TIMEOUT, missed.reason)
    }

    @Test
    fun anUnknownVerbBecomesUnknownRatherThanFailingTheFrame() {
        val frame = parseInboundFrame(
            """
            {"v":1,"type":"call.signal","ts":1,
             "payload":{"call_id":"$CALL_ID","from_user_id":"peer-1",
               "signal":{"verb":"hold"}}}
            """.trimIndent()
        )

        val unknown = assertIs<CallSignal.Unknown>(
            assertIs<InboundFrame.CallSignalFrame>(frame).signal
        )
        assertEquals("hold", unknown.verb)
    }

    @Test
    fun anIncompleteSignalDegradesToUnknownInsteadOfMalformed() {
        val frame = parseInboundFrame(
            """
            {"v":1,"type":"call.signal","ts":1,
             "payload":{"call_id":"$CALL_ID","from_user_id":"peer-1",
               "signal":{"verb":"accept"}}}
            """.trimIndent()
        )

        assertIs<CallSignal.Unknown>(assertIs<InboundFrame.CallSignalFrame>(frame).signal)
    }
}
