package com.relay.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OutboundFrameEncodingTest {

    @Test
    fun messageSendCarriesClientMsgIdAsEnvelopeId() {
        val encoded = encodeFrame(messageSendFrame("client-1", "dialog-1", "hello"))
        val json = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(1, json["v"]?.jsonPrimitive?.content?.toInt())
        assertEquals("message.send", json["type"]?.jsonPrimitive?.content)
        assertEquals("client-1", json["id"]?.jsonPrimitive?.content)
        val payload = json["payload"]?.jsonObject
        assertEquals("dialog-1", payload?.get("dialog_id")?.jsonPrimitive?.content)
        assertEquals("hello", payload?.get("text")?.jsonPrimitive?.content)
    }

    @Test
    fun payloadKeysAreSnakeCase() {
        val encoded = encodeFrame(messageSendFrame("c", "d", "t"))
        assertTrue("dialog_id" in encoded)
        assertTrue("dialogId" !in encoded)
    }

    @Test
    fun messageReadCarriesTheCursorPosition() {
        val encoded = encodeFrame(messageReadFrame("dialog-1", "m-9", "frame-1"))
        val json = Json.parseToJsonElement(encoded).jsonObject
        assertEquals("message.read", json["type"]?.jsonPrimitive?.content)
        assertEquals("frame-1", json["id"]?.jsonPrimitive?.content)
        val payload = json["payload"]?.jsonObject
        assertEquals("dialog-1", payload?.get("dialog_id")?.jsonPrimitive?.content)
        assertEquals("m-9", payload?.get("up_to_message_id")?.jsonPrimitive?.content)
    }

    @Test
    fun pingHasIdAndEmptyPayload() {
        val encoded = encodeFrame(pingFrame("ping-id-1"))
        val json = Json.parseToJsonElement(encoded).jsonObject
        assertEquals("ping", json["type"]?.jsonPrimitive?.content)
        assertEquals("ping-id-1", json["id"]?.jsonPrimitive?.content)
        assertEquals(0, json["payload"]?.jsonObject?.size)
    }

    @Test
    fun generatedFrameIdsAreUnique() {
        assertNotEquals(newFrameId(), newFrameId())
    }

    @Test
    fun envelopeRoundTripsThroughWireJson() {
        val original = messageSendFrame("client-2", "dialog-2", "round trip")
        val decoded = WireJson.decodeFromString(Envelope.serializer(), encodeFrame(original))
        assertEquals(original, decoded)
    }
}
