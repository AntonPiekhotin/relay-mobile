package com.relay.sync

import com.relay.db.MessageStore
import com.relay.model.MessageState
import com.relay.network.FallbackSendResponse
import com.relay.network.MessageApiResult
import com.relay.testutil.FakeMessageApi
import com.relay.testutil.FakeSocket
import com.relay.testutil.TEST_ISO
import com.relay.testutil.createTestDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
private class OutboxHarness(scope: TestScope) {
    val store = MessageStore(createTestDb(), UnconfinedTestDispatcher(scope.testScheduler))
    val socket = FakeSocket()
    val api = FakeMessageApi()
    val outbox = Outbox(
        store = store,
        socket = socket,
        api = api,
        scope = scope.backgroundScope,
        now = { scope.testScheduler.currentTime }
    )
}

class OutboxTest {

    @Test
    fun retryResendsTheSameClientMsgId() = runTest {
        val harness = OutboxHarness(this)
        harness.socket.connect()
        harness.store.insertPending("cm-1", "d1", "me", "hi", currentTime())
        harness.outbox.start()
        advanceTimeBy(30_000)
        assertTrue(harness.socket.sentFrames.size >= 2)
        assertTrue(harness.socket.sentFrames.all { it.id == "cm-1" })
        assertEquals(1L, harness.store.countAllMessages())
    }

    @Test
    fun ackStopsRetries() = runTest {
        val harness = OutboxHarness(this)
        harness.socket.connect()
        harness.store.insertPending("cm-1", "d1", "me", "hi", currentTime())
        harness.outbox.start()
        advanceTimeBy(6_000)
        harness.store.applyAck("cm-1", "srv-1", currentTime())
        val sentSoFar = harness.socket.sentFrames.size
        advanceTimeBy(120_000)
        assertEquals(sentSoFar, harness.socket.sentFrames.size)
        assertEquals(1L, harness.store.countAllMessages())
    }

    @Test
    fun fallsBackToRestWhenSocketIsDownAndPromotesOnSuccess() = runTest {
        val harness = OutboxHarness(this)
        harness.api.fallbackHandler = { clientMsgId, _, _ ->
            MessageApiResult.Success(FallbackSendResponse("srv-9", clientMsgId, TEST_ISO))
        }
        harness.store.insertPending("cm-1", "d1", "me", "hi", currentTime())
        harness.outbox.start()
        runCurrent()
        assertEquals(1, harness.api.fallbackCalls)
        assertEquals(0, harness.socket.sentFrames.size)
        val message = harness.store.observeMessages("d1").first().single()
        assertEquals(MessageState.SENT, message.state)
        assertEquals("srv-9", message.serverId)
    }

    @Test
    fun permanentRestRejectionFailsTheMessage() = runTest {
        val harness = OutboxHarness(this)
        harness.api.fallbackHandler = { _, _, _ -> MessageApiResult.Rejected(400) }
        harness.store.insertPending("cm-1", "d1", "me", "hi", currentTime())
        harness.outbox.start()
        advanceTimeBy(120_000)
        val message = harness.store.observeMessages("d1").first().single()
        assertEquals(MessageState.FAILED, message.state)
        assertEquals(1, harness.api.fallbackCalls)
    }

    @Test
    fun exhaustsAfterMaxUnackedSocketAttempts() = runTest {
        val harness = OutboxHarness(this)
        harness.socket.connect()
        harness.store.insertPending("cm-1", "d1", "me", "hi", currentTime())
        harness.outbox.start()
        advanceTimeBy(60 * 60 * 1000)
        val message = harness.store.observeMessages("d1").first().single()
        assertEquals(MessageState.FAILED, message.state)
        assertEquals(10L, message.attemptCount)
    }

    @Test
    fun offlineSendsDoNotBurnAttemptsAndStayPending() = runTest {
        val harness = OutboxHarness(this)
        harness.store.insertPending("cm-1", "d1", "me", "hi", currentTime())
        harness.outbox.start()
        advanceTimeBy(2 * 60 * 60 * 1000)
        val message = harness.store.observeMessages("d1").first().single()
        assertEquals(MessageState.PENDING, message.state)
        assertEquals(0L, message.attemptCount)
        assertTrue(harness.api.fallbackCalls > 0)
    }

    @Test
    fun rest404KeepsMessagePendingWithoutBurningAttempts() = runTest {
        val harness = OutboxHarness(this)
        harness.api.fallbackHandler = { _, _, _ -> MessageApiResult.Rejected(404) }
        harness.store.insertPending("cm-1", "d1", "me", "hi", currentTime())
        harness.outbox.start()
        advanceTimeBy(10 * 60 * 1000)
        val message = harness.store.observeMessages("d1").first().single()
        assertEquals(MessageState.PENDING, message.state)
        assertEquals(0L, message.attemptCount)
    }

    @Test
    fun failedSocketWriteFallsBackToRestInTheSameFlush() = runTest {
        val harness = OutboxHarness(this)
        harness.socket.connect()
        harness.socket.sendResult = false
        harness.api.fallbackHandler = { clientMsgId, _, _ ->
            MessageApiResult.Success(FallbackSendResponse("srv-7", clientMsgId, TEST_ISO))
        }
        harness.store.insertPending("cm-1", "d1", "me", "hi", currentTime())
        harness.outbox.start()
        runCurrent()
        assertEquals(1, harness.api.fallbackCalls)
        val message = harness.store.observeMessages("d1").first().single()
        assertEquals(MessageState.SENT, message.state)
        assertEquals("srv-7", message.serverId)
    }

    @Test
    fun retryAfterExhaustionGetsAFreshAttemptBudget() = runTest {
        val harness = OutboxHarness(this)
        harness.socket.connect()
        harness.store.insertPending("cm-1", "d1", "me", "hi", currentTime())
        harness.outbox.start()
        advanceTimeBy(60 * 60 * 1000)
        val failed = harness.store.observeMessages("d1").first().single()
        assertEquals(MessageState.FAILED, failed.state)
        harness.outbox.stop()
        advanceTimeBy(25 * 60 * 60 * 1000L)
        harness.store.resetForRetry(failed.localId)
        harness.outbox.start()
        advanceTimeBy(10_000)
        val retried = harness.store.observeMessages("d1").first().single()
        assertEquals(MessageState.PENDING, retried.state)
        assertTrue(retried.attemptCount >= 1L)
    }

    @Test
    fun rapidSendsGoOutInCreationOrder() = runTest {
        val harness = OutboxHarness(this)
        harness.socket.connect()
        repeat(50) { index ->
            harness.store.insertPending("cm-$index", "d1", "me", "msg $index", currentTime())
        }
        harness.outbox.start()
        advanceTimeBy(1)
        val firstPass = harness.socket.sentFrames.take(50).map { it.id }
        assertEquals((0 until 50).map { "cm-$it" }, firstPass)
    }

    private fun TestScope.currentTime(): Long = testScheduler.currentTime
}
