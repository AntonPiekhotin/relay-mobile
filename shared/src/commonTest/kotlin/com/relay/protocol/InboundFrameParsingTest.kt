package com.relay.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class InboundFrameParsingTest {

    @Test
    fun parsesSessionConnected() {
        val frame = parseInboundFrame(
            """{"v":1,"type":"session.connected","ts":1730000000000,
               "payload":{"user_id":"u-1","session_id":"s-1"}}"""
        )
        val connected = assertIs<InboundFrame.SessionConnected>(frame)
        assertEquals("u-1", connected.payload.userId)
        assertEquals("s-1", connected.payload.sessionId)
    }

    @Test
    fun parsesAck() {
        val frame = parseInboundFrame(
            """{"v":1,"type":"ack","ts":1730000000123,
               "payload":{"client_msg_id":"c-1","message_id":"m-1","created_at":"2026-07-26T10:00:00Z"}}"""
        )
        val ack = assertIs<InboundFrame.Ack>(frame)
        assertEquals("c-1", ack.payload.clientMsgId)
        assertEquals("m-1", ack.payload.messageId)
        assertEquals("2026-07-26T10:00:00Z", ack.payload.createdAt)
    }

    @Test
    fun parsesMessageNewWithoutClientMsgId() {
        val frame = parseInboundFrame(
            """{"v":1,"type":"message.new","ts":1730000000200,
               "payload":{"message_id":"m-1","dialog_id":"d-1","sender_id":"u-2",
                          "text":"hi","created_at":"2026-07-26T10:00:00Z"}}"""
        )
        val messageNew = assertIs<InboundFrame.MessageNew>(frame)
        assertEquals("m-1", messageNew.payload.messageId)
        assertNull(messageNew.payload.clientMsgId)
    }

    @Test
    fun parsesErrorWithNullRefId() {
        val frame = parseInboundFrame(
            """{"v":1,"type":"error","ts":1730000000000,
               "payload":{"code":"BAD_FRAME","message":"unparseable","ref_id":null}}"""
        )
        val error = assertIs<InboundFrame.Error>(frame)
        assertEquals("BAD_FRAME", error.payload.code)
        assertNull(error.payload.refId)
    }

    @Test
    fun parsesErrorWithRefId() {
        val frame = parseInboundFrame(
            """{"v":1,"type":"error","ts":1,
               "payload":{"code":"DIALOG_NOT_FOUND","message":"no such dialog","ref_id":"c-9"}}"""
        )
        val error = assertIs<InboundFrame.Error>(frame)
        assertEquals("c-9", error.payload.refId)
    }

    @Test
    fun parsesPongWithRefId() {
        val frame = parseInboundFrame(
            """{"v":1,"type":"pong","ts":1,"payload":{"ref_id":"ping-1"}}"""
        )
        val pong = assertIs<InboundFrame.Pong>(frame)
        assertEquals("ping-1", pong.payload.refId)
    }

    @Test
    fun unknownFrameTypeIsIgnoredNotError() {
        val frame = parseInboundFrame(
            """{"v":1,"type":"presence.update","ts":1,"payload":{"user_id":"u-1","status":"online"}}"""
        )
        val unknown = assertIs<InboundFrame.Unknown>(frame)
        assertEquals("presence.update", unknown.type)
    }

    @Test
    fun unknownPayloadKeysAreIgnored() {
        val frame = parseInboundFrame(
            """{"v":1,"type":"ack","ts":1,"extra_envelope_key":true,
               "payload":{"client_msg_id":"c-1","message_id":"m-1",
                          "created_at":"2026-07-26T10:00:00Z","future_field":42}}"""
        )
        assertIs<InboundFrame.Ack>(frame)
    }

    @Test
    fun invalidJsonIsMalformed() {
        assertIs<InboundFrame.Malformed>(parseInboundFrame("not json at all"))
    }

    @Test
    fun envelopeMissingTypeIsMalformed() {
        assertIs<InboundFrame.Malformed>(parseInboundFrame("""{"v":1,"ts":1,"payload":{}}"""))
    }

    @Test
    fun knownTypeWithBrokenPayloadIsMalformed() {
        assertIs<InboundFrame.Malformed>(
            parseInboundFrame("""{"v":1,"type":"ack","ts":1,"payload":{"message_id":"m-1"}}""")
        )
    }
}
