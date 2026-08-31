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
    fun parsesMessageSystem() {
        val frame = parseInboundFrame(
            """{"v":1,"type":"message.system","ts":1730000000200,
               "payload":{"message_id":"m-1","dialog_id":"g-1","actor_id":"u-2",
                          "kind":"member_added","target_user_id":"u-3","title":"team",
                          "created_at":"2026-07-26T10:00:00Z"}}"""
        )
        val system = assertIs<InboundFrame.MessageSystem>(frame)
        assertEquals("m-1", system.payload.messageId)
        assertEquals("g-1", system.payload.dialogId)
        assertEquals("u-2", system.payload.actorId)
        assertEquals("member_added", system.payload.kind)
        assertEquals("u-3", system.payload.targetUserId)
        assertEquals("team", system.payload.title)
    }

    @Test
    fun parsesMessageSystemWithNullTarget() {
        val frame = parseInboundFrame(
            """{"v":1,"type":"message.system","ts":1730000000200,
               "payload":{"message_id":"m-1","dialog_id":"g-1","actor_id":"u-2",
                          "kind":"group_created","target_user_id":null,"title":"team",
                          "created_at":"2026-07-26T10:00:00Z"}}"""
        )
        val system = assertIs<InboundFrame.MessageSystem>(frame)
        assertNull(system.payload.targetUserId)
    }

    @Test
    fun parsesDialogDeleted() {
        val frame = parseInboundFrame(
            """{"v":1,"type":"dialog.deleted","ts":1730000000200,
               "payload":{"dialog_id":"g-1","actor_id":"u-2"}}"""
        )
        val deleted = assertIs<InboundFrame.DialogDeleted>(frame)
        assertEquals("g-1", deleted.payload.dialogId)
        assertEquals("u-2", deleted.payload.actorId)
    }

    @Test
    fun parsesReadReceipt() {
        val frame = parseInboundFrame(
            """{"v":1,"type":"message.read","ts":1730000000000,
               "payload":{"dialog_id":"d-1","user_id":"u-2",
                          "up_to_message_id":"m-9","read_at":"2026-07-26T10:00:00Z"}}"""
        )
        val read = assertIs<InboundFrame.MessageRead>(frame)
        assertEquals("d-1", read.payload.dialogId)
        assertEquals("u-2", read.payload.userId)
        assertEquals("m-9", read.payload.upToMessageId)
        assertEquals("2026-07-26T10:00:00Z", read.payload.readAt)
    }

    @Test
    fun readReceiptWithoutUserIdIsMalformed() {
        val frame = parseInboundFrame(
            """{"v":1,"type":"message.read","ts":1,
               "payload":{"dialog_id":"d-1","up_to_message_id":"m-9","read_at":"2026-07-26T10:00:00Z"}}"""
        )
        assertIs<InboundFrame.Malformed>(frame)
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
            """{"v":1,"type":"reaction.new","ts":1,"payload":{"message_id":"m-1","emoji":"+1"}}"""
        )
        val unknown = assertIs<InboundFrame.Unknown>(frame)
        assertEquals("reaction.new", unknown.type)
    }

    @Test
    fun parsesPresenceUpdateOnline() {
        val frame = parseInboundFrame(
            """{"v":1,"type":"presence.update","ts":1,"payload":{"user_id":"u-1","status":"online"}}"""
        )
        val update = assertIs<InboundFrame.PresenceUpdate>(frame)
        assertEquals("u-1", update.payload.userId)
        assertEquals("online", update.payload.status)
        assertNull(update.payload.lastSeen)
    }

    @Test
    fun parsesPresenceUpdateOfflineWithLastSeen() {
        val frame = parseInboundFrame(
            """{"v":1,"type":"presence.update","ts":1,
               "payload":{"user_id":"u-1","status":"offline","last_seen":"2026-08-13T10:00:00Z"}}"""
        )
        val update = assertIs<InboundFrame.PresenceUpdate>(frame)
        assertEquals("offline", update.payload.status)
        assertEquals("2026-08-13T10:00:00Z", update.payload.lastSeen)
    }

    @Test
    fun parsesPresenceUpdateWithNullLastSeen() {
        val frame = parseInboundFrame(
            """{"v":1,"type":"presence.update","ts":1,
               "payload":{"user_id":"u-1","status":"offline","last_seen":null}}"""
        )
        val update = assertIs<InboundFrame.PresenceUpdate>(frame)
        assertNull(update.payload.lastSeen)
    }

    @Test
    fun parsesTypingStart() {
        val frame = parseInboundFrame(
            """{"v":1,"type":"typing.start","ts":1,"payload":{"dialog_id":"d-1","user_id":"u-2"}}"""
        )
        val typing = assertIs<InboundFrame.TypingStart>(frame)
        assertEquals("d-1", typing.payload.dialogId)
        assertEquals("u-2", typing.payload.userId)
    }

    @Test
    fun typingStartWithoutUserIdIsMalformed() {
        val frame = parseInboundFrame(
            """{"v":1,"type":"typing.start","ts":1,"payload":{"dialog_id":"d-1"}}"""
        )
        assertIs<InboundFrame.Malformed>(frame)
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
