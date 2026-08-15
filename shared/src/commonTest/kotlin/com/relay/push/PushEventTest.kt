package com.relay.push

import kotlin.test.Test
import kotlin.test.assertEquals

class PushEventTest {

    @Test
    fun parsesANewMessagePush() {
        val event = parsePushEvent(
            mapOf(
                "kind" to KIND_MESSAGE_NEW,
                "dialogId" to "d1",
                "messageId" to "srv-1",
                "senderId" to "peer"
            )
        )
        assertEquals(PushEvent.NewMessage("d1", "srv-1", "peer"), event)
    }

    @Test
    fun anUnknownKindIsIgnoredRatherThanFailing() {
        assertEquals(PushEvent.Unknown, parsePushEvent(mapOf("kind" to "SOMETHING_NEW")))
        assertEquals(PushEvent.Unknown, parsePushEvent(emptyMap()))
    }

    @Test
    fun aMessagePushMissingItsIdentifiersIsUnknown() {
        val event = parsePushEvent(
            mapOf("kind" to KIND_MESSAGE_NEW, "dialogId" to "d1", "messageId" to "srv-1")
        )
        assertEquals(PushEvent.Unknown, event)
    }

    @Test
    fun theStringNullFromTheBackendIsTreatedAsAbsent() {
        val event = parsePushEvent(
            mapOf(
                "kind" to KIND_MESSAGE_NEW,
                "dialogId" to "d1",
                "messageId" to "null",
                "senderId" to "peer"
            )
        )
        assertEquals(PushEvent.Unknown, event)
    }

    @Test
    fun parsesCallPushesForTheCallPhase() {
        val incoming = parsePushEvent(
            mapOf(
                "kind" to KIND_INCOMING_CALL,
                "callId" to "c1",
                "callerId" to "peer",
                "media" to "video",
                "ringExpiresAt" to "2026-07-26T10:00:40Z"
            )
        )
        assertEquals(
            PushEvent.IncomingCall("c1", "peer", "video", "2026-07-26T10:00:40Z"),
            incoming
        )

        val missed = parsePushEvent(
            mapOf("kind" to KIND_MISSED_CALL, "callId" to "c1", "callerId" to "peer")
        )
        assertEquals(PushEvent.MissedCall("c1", "peer", "voice"), missed)
    }
}
