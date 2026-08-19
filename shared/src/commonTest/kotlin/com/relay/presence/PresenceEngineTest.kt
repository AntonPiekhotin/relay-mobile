package com.relay.presence

import com.relay.protocol.ErrorCode
import com.relay.protocol.FrameType
import com.relay.protocol.PresenceStatusWire
import com.relay.testutil.FakeSocket
import com.relay.testutil.TEST_ISO
import com.relay.testutil.errorFrame
import com.relay.testutil.presenceUpdateFrame
import com.relay.testutil.typingFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val DIALOG = "dialog-1"
private const val PEER = "peer-1"

@OptIn(ExperimentalCoroutinesApi::class)
private class PresenceHarness(scope: TestScope) {
    val socket = FakeSocket()
    val engine = PresenceEngine(
        socket = socket,
        scope = scope.backgroundScope,
        now = { scope.testScheduler.currentTime }
    )

    fun startConnected() {
        engine.start()
        socket.connect()
    }

    fun framesOfType(type: String) = socket.sentFrames.filter { it.type == type }
}

class PresenceEngineTest {

    @Test
    fun openingADialogWhileConnectedSubscribes() = runTest {
        val harness = PresenceHarness(this)
        harness.startConnected()
        runCurrent()

        harness.engine.dialogOpened(DIALOG)
        runCurrent()

        val subscribe = harness.framesOfType(FrameType.PRESENCE_SUBSCRIBE).single()
        assertNotNull(subscribe.id)
        assertEquals(
            DIALOG,
            subscribe.payload?.jsonObject?.get("dialog_id")?.jsonPrimitive?.content
        )
    }

    @Test
    fun openingADialogWhileDisconnectedSubscribesOnConnect() = runTest {
        val harness = PresenceHarness(this)
        harness.engine.start()
        runCurrent()

        harness.engine.dialogOpened(DIALOG)
        runCurrent()
        assertEquals(0, harness.framesOfType(FrameType.PRESENCE_SUBSCRIBE).size)

        harness.socket.connect()
        runCurrent()

        assertEquals(1, harness.framesOfType(FrameType.PRESENCE_SUBSCRIBE).size)
    }

    @Test
    fun reconnectResubscribesTheOpenDialog() = runTest {
        val harness = PresenceHarness(this)
        harness.startConnected()
        runCurrent()
        harness.engine.dialogOpened(DIALOG)
        runCurrent()

        harness.socket.disconnect()
        runCurrent()
        harness.socket.connect()
        runCurrent()

        assertEquals(2, harness.framesOfType(FrameType.PRESENCE_SUBSCRIBE).size)
    }

    @Test
    fun closingTheDialogUnsubscribesAndClearsState() = runTest {
        val harness = PresenceHarness(this)
        harness.startConnected()
        runCurrent()
        harness.engine.dialogOpened(DIALOG)
        runCurrent()
        harness.socket.emitFrame(presenceUpdateFrame(userId = PEER))
        harness.socket.emitFrame(typingFrame(DIALOG, PEER))
        runCurrent()
        assertTrue(harness.engine.presence.value.isNotEmpty())
        assertTrue(harness.engine.typing.value.isNotEmpty())

        harness.engine.dialogClosed(DIALOG)
        runCurrent()

        val unsubscribe = harness.framesOfType(FrameType.PRESENCE_UNSUBSCRIBE).single()
        assertEquals(
            DIALOG,
            unsubscribe.payload?.jsonObject?.get("dialog_id")?.jsonPrimitive?.content
        )
        assertTrue(harness.engine.presence.value.isEmpty())
        assertTrue(harness.engine.typing.value.isEmpty())
    }

    @Test
    fun presenceUpdatesAreExposedPerUser() = runTest {
        val harness = PresenceHarness(this)
        harness.startConnected()
        runCurrent()
        harness.engine.dialogOpened(DIALOG)
        runCurrent()

        harness.socket.emitFrame(presenceUpdateFrame(userId = PEER, status = PresenceStatusWire.ONLINE))
        runCurrent()
        assertEquals(PeerPresence(online = true, lastSeenAt = null), harness.engine.presence.value[PEER])

        harness.socket.emitFrame(
            presenceUpdateFrame(userId = PEER, status = PresenceStatusWire.OFFLINE, lastSeen = TEST_ISO)
        )
        runCurrent()
        val offline = assertNotNull(harness.engine.presence.value[PEER])
        assertEquals(false, offline.online)
        assertNotNull(offline.lastSeenAt)
    }

    @Test
    fun anUnrecognisedStatusIsTreatedAsOffline() = runTest {
        val harness = PresenceHarness(this)
        harness.startConnected()
        runCurrent()
        harness.engine.dialogOpened(DIALOG)
        runCurrent()

        harness.socket.emitFrame(presenceUpdateFrame(userId = PEER, status = "away"))
        runCurrent()

        assertEquals(PeerPresence(online = false, lastSeenAt = null), harness.engine.presence.value[PEER])
    }

    @Test
    fun typingExpiresAfterFiveSecondsOfSilence() = runTest {
        val harness = PresenceHarness(this)
        harness.startConnected()
        runCurrent()
        harness.engine.dialogOpened(DIALOG)
        runCurrent()

        harness.socket.emitFrame(typingFrame(DIALOG, PEER))
        runCurrent()
        assertEquals(setOf(PEER), harness.engine.typing.value[DIALOG])

        advanceTimeBy(TYPING_EXPIRY_MILLIS + 1)
        runCurrent()

        assertNull(harness.engine.typing.value[DIALOG])
    }

    @Test
    fun aRepeatedTypingFrameExtendsTheIndicator() = runTest {
        val harness = PresenceHarness(this)
        harness.startConnected()
        runCurrent()
        harness.engine.dialogOpened(DIALOG)
        runCurrent()

        harness.socket.emitFrame(typingFrame(DIALOG, PEER))
        runCurrent()
        advanceTimeBy(TYPING_EXPIRY_MILLIS - 1_000)
        harness.socket.emitFrame(typingFrame(DIALOG, PEER))
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(setOf(PEER), harness.engine.typing.value[DIALOG])
    }

    @Test
    fun typingForAnotherDialogIsIgnored() = runTest {
        val harness = PresenceHarness(this)
        harness.startConnected()
        runCurrent()
        harness.engine.dialogOpened(DIALOG)
        runCurrent()

        harness.socket.emitFrame(typingFrame("other-dialog", PEER))
        runCurrent()

        assertTrue(harness.engine.typing.value.isEmpty())
    }

    @Test
    fun typingActivityIsThrottledToOneFramePerWindow() = runTest {
        val harness = PresenceHarness(this)
        harness.startConnected()
        runCurrent()
        harness.engine.dialogOpened(DIALOG)
        runCurrent()

        harness.engine.typingActivity(DIALOG)
        harness.engine.typingActivity(DIALOG)
        runCurrent()
        assertEquals(1, harness.framesOfType(FrameType.TYPING_START).size)

        advanceTimeBy(TYPING_THROTTLE_MILLIS - 1)
        harness.engine.typingActivity(DIALOG)
        runCurrent()
        assertEquals(1, harness.framesOfType(FrameType.TYPING_START).size)

        advanceTimeBy(2)
        harness.engine.typingActivity(DIALOG)
        runCurrent()
        assertEquals(2, harness.framesOfType(FrameType.TYPING_START).size)
    }

    @Test
    fun typingActivityWhileDisconnectedSendsNothing() = runTest {
        val harness = PresenceHarness(this)
        harness.engine.start()
        runCurrent()
        harness.engine.dialogOpened(DIALOG)
        runCurrent()

        harness.engine.typingActivity(DIALOG)
        runCurrent()

        assertEquals(0, harness.framesOfType(FrameType.TYPING_START).size)
    }

    @Test
    fun disconnectClearsPresenceAndTyping() = runTest {
        val harness = PresenceHarness(this)
        harness.startConnected()
        runCurrent()
        harness.engine.dialogOpened(DIALOG)
        runCurrent()
        harness.socket.emitFrame(presenceUpdateFrame(userId = PEER))
        harness.socket.emitFrame(typingFrame(DIALOG, PEER))
        runCurrent()

        harness.socket.disconnect()
        runCurrent()

        assertTrue(harness.engine.presence.value.isEmpty())
        assertTrue(harness.engine.typing.value.isEmpty())
    }

    @Test
    fun aRetryableSubscribeErrorRetriesAfterTheBackoff() = runTest {
        val harness = PresenceHarness(this)
        harness.startConnected()
        runCurrent()
        harness.engine.dialogOpened(DIALOG)
        runCurrent()
        val subscribeId = harness.framesOfType(FrameType.PRESENCE_SUBSCRIBE).single().id

        harness.socket.emitFrame(errorFrame(ErrorCode.INTERNAL, subscribeId))
        runCurrent()
        assertEquals(1, harness.framesOfType(FrameType.PRESENCE_SUBSCRIBE).size)

        advanceTimeBy(SUBSCRIBE_RETRY_MILLIS + 1)
        runCurrent()

        assertEquals(2, harness.framesOfType(FrameType.PRESENCE_SUBSCRIBE).size)
    }

    @Test
    fun dialogNotFoundStopsWithoutRetry() = runTest {
        val harness = PresenceHarness(this)
        harness.startConnected()
        runCurrent()
        harness.engine.dialogOpened(DIALOG)
        runCurrent()
        val subscribeId = harness.framesOfType(FrameType.PRESENCE_SUBSCRIBE).single().id

        harness.socket.emitFrame(errorFrame(ErrorCode.DIALOG_NOT_FOUND, subscribeId))
        runCurrent()
        advanceTimeBy(SUBSCRIBE_RETRY_MILLIS * 3)
        runCurrent()

        assertEquals(1, harness.framesOfType(FrameType.PRESENCE_SUBSCRIBE).size)
    }

    @Test
    fun anErrorForAForeignFrameIsIgnored() = runTest {
        val harness = PresenceHarness(this)
        harness.startConnected()
        runCurrent()
        harness.engine.dialogOpened(DIALOG)
        runCurrent()

        harness.socket.emitFrame(errorFrame(ErrorCode.INTERNAL, "some-other-frame"))
        runCurrent()
        advanceTimeBy(SUBSCRIBE_RETRY_MILLIS + 1)
        runCurrent()

        assertEquals(1, harness.framesOfType(FrameType.PRESENCE_SUBSCRIBE).size)
    }

    @Test
    fun openingAnotherDialogDropsTheOldPeerState() = runTest {
        val harness = PresenceHarness(this)
        harness.startConnected()
        runCurrent()
        harness.engine.dialogOpened(DIALOG)
        runCurrent()
        harness.socket.emitFrame(presenceUpdateFrame(userId = PEER))
        runCurrent()

        harness.engine.dialogOpened("dialog-2")
        runCurrent()

        assertTrue(harness.engine.presence.value.isEmpty())
        assertEquals(2, harness.framesOfType(FrameType.PRESENCE_SUBSCRIBE).size)
    }

    @Test
    fun stopClearsEverything() = runTest {
        val harness = PresenceHarness(this)
        harness.startConnected()
        runCurrent()
        harness.engine.dialogOpened(DIALOG)
        runCurrent()
        harness.socket.emitFrame(presenceUpdateFrame(userId = PEER))
        harness.socket.emitFrame(typingFrame(DIALOG, PEER))
        runCurrent()

        harness.engine.stop()
        runCurrent()

        assertTrue(harness.engine.presence.value.isEmpty())
        assertTrue(harness.engine.typing.value.isEmpty())
    }
}
