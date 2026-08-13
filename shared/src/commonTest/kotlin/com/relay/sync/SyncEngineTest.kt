package com.relay.sync

import com.relay.db.MessageStore
import com.relay.model.MessageState
import com.relay.network.MessageApiResult
import com.relay.network.WireDialog
import com.relay.protocol.ErrorCode
import com.relay.protocol.FrameType
import com.relay.protocol.isoToEpochMillis
import com.relay.testutil.FakeMessageApi
import com.relay.testutil.FakeSocket
import com.relay.testutil.ackFrame
import com.relay.testutil.createTestDb
import com.relay.testutil.errorFrame
import com.relay.testutil.messageNewFrame
import com.relay.testutil.readReceiptFrame
import com.relay.testutil.TEST_ISO
import com.relay.testutil.wireMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
private class EngineHarness(scope: TestScope) {
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
    val readReceipts = ReadReceipts(store, socket)
    val engine = SyncEngine(
        store = store,
        socket = socket,
        api = api,
        outbox = outbox,
        readReceipts = readReceipts,
        scope = scope.backgroundScope
    )
}

class SyncEngineTest {

    @Test
    fun ackFramePromotesPendingMessage() = runTest {
        val harness = EngineHarness(this)
        harness.store.insertPending("cm-1", "d1", "me", "hi", 100)
        harness.engine.start()
        runCurrent()
        harness.socket.emitFrame(ackFrame("cm-1", "srv-1"))
        runCurrent()
        val message = harness.store.observeMessages("d1").first().single()
        assertEquals(MessageState.SENT, message.state)
        assertEquals("srv-1", message.serverId)
    }

    @Test
    fun duplicateMessageNewFramesYieldOneRow() = runTest {
        val harness = EngineHarness(this)
        harness.engine.start()
        runCurrent()
        harness.socket.emitFrame(messageNewFrame("srv-1"))
        harness.socket.emitFrame(messageNewFrame("srv-1"))
        runCurrent()
        assertEquals(1L, harness.store.countAllMessages())
    }

    @Test
    fun messageNewForLocallyPendingMessagePromotes() = runTest {
        val harness = EngineHarness(this)
        harness.store.insertPending("cm-1", "d1", "me", "hi", 100)
        harness.engine.start()
        runCurrent()
        harness.socket.emitFrame(messageNewFrame("srv-1", senderId = "me", clientMsgId = "cm-1"))
        runCurrent()
        val message = harness.store.observeMessages("d1").first().single()
        assertEquals(MessageState.SENT, message.state)
        assertEquals("srv-1", message.serverId)
        assertEquals("cm-1", message.clientMsgId)
    }

    @Test
    fun aPeerReceiptAdvancesThePeerReadCursor() = runTest {
        val harness = EngineHarness(this)
        harness.store.insertPending("cm-1", "d1", "me", "hi", 100)
        harness.engine.start()
        runCurrent()
        harness.socket.emitFrame(
            readReceiptFrame(userId = "peer", upToMessageId = "srv-1", readAt = TEST_ISO)
        )
        runCurrent()
        assertEquals(
            isoToEpochMillis(TEST_ISO),
            harness.store.observeDialog("d1").first()?.peerReadAt
        )
    }

    @Test
    fun aReceiptWithAnUnparseableTimestampIsIgnored() = runTest {
        val harness = EngineHarness(this)
        harness.store.insertPending("cm-1", "d1", "me", "hi", 100)
        harness.engine.start()
        runCurrent()
        harness.socket.emitFrame(
            readReceiptFrame(userId = "peer", upToMessageId = "srv-1", readAt = "not-a-timestamp")
        )
        runCurrent()
        assertNull(harness.store.observeDialog("d1").first()?.peerReadAt)
    }

    @Test
    fun readsTakenWhileOfflineAreFlushedOnReconnect() = runTest {
        val harness = EngineHarness(this)
        harness.store.applyRemoteMessage("srv-1", null, "d1", "peer", "hello", 100, selfId = "me")
        harness.store.markSelfRead("d1", "srv-1", 100)
        harness.engine.start()
        runCurrent()
        harness.socket.connect(userId = "me")
        runCurrent()

        val read = harness.socket.sentFrames.single { it.type == FrameType.MESSAGE_READ }
        assertEquals(
            "srv-1",
            read.payload?.jsonObject?.get("up_to_message_id")?.jsonPrimitive?.content
        )
        assertEquals(emptyList(), harness.store.unsentReads())
    }

    @Test
    fun permanentErrorFrameFailsThePendingMessage() = runTest {
        val harness = EngineHarness(this)
        harness.store.insertPending("cm-1", "d1", "me", "hi", 100)
        harness.engine.start()
        runCurrent()
        harness.socket.emitFrame(errorFrame(ErrorCode.DIALOG_NOT_FOUND, refId = "cm-1"))
        runCurrent()
        val message = harness.store.observeMessages("d1").first().single()
        assertEquals(MessageState.FAILED, message.state)
        assertEquals(ErrorCode.DIALOG_NOT_FOUND, message.failReason)
    }

    @Test
    fun retryableErrorFrameKeepsTheMessagePending() = runTest {
        val harness = EngineHarness(this)
        harness.store.insertPending("cm-1", "d1", "me", "hi", 100)
        harness.engine.start()
        runCurrent()
        harness.socket.emitFrame(errorFrame(ErrorCode.SEND_FAILED, refId = "cm-1"))
        runCurrent()
        val message = harness.store.observeMessages("d1").first().single()
        assertEquals(MessageState.PENDING, message.state)
    }

    @Test
    fun catchUpWithStaleCursorPagesUntilPartialPage() = runTest {
        val harness = EngineHarness(this)
        harness.store.upsertDialog("d1", "direct", null, null)
        harness.store.updateNewestSynced("d1", "m0")
        harness.api.afterHandler = { _, after, _ ->
            when (after) {
                "m0" -> MessageApiResult.Success((1..100).map { wireMessage("m$it") })
                "m100" -> MessageApiResult.Success((101..130).map { wireMessage("m$it") })
                else -> MessageApiResult.Success(emptyList())
            }
        }
        harness.engine.start()
        runCurrent()
        harness.socket.connect()
        runCurrent()
        assertEquals(130L, harness.store.countAllMessages())
        assertEquals("m130", harness.store.syncState("d1")?.newestSyncedId)
        assertEquals(2, harness.api.afterCalls)
        assertEquals(SyncEngineState.Live, harness.engine.state.value)
    }

    @Test
    fun catchUpWithNullCursorLoadsOnlyTheMostRecentPage() = runTest {
        val harness = EngineHarness(this)
        harness.api.dialogsHandler = {
            MessageApiResult.Success(listOf(WireDialog("d1", "direct")))
        }
        harness.api.beforeHandler = { _, before, _ ->
            if (before == null) {
                MessageApiResult.Success((50 downTo 1).map { wireMessage("m$it") })
            } else {
                MessageApiResult.Success(emptyList())
            }
        }
        harness.engine.start()
        runCurrent()
        harness.socket.connect()
        runCurrent()
        assertEquals(50L, harness.store.countAllMessages())
        assertEquals(0, harness.api.afterCalls)
        assertEquals(1, harness.api.beforeCalls)
        val syncState = harness.store.syncState("d1")
        assertEquals("m50", syncState?.newestSyncedId)
        assertEquals("m1", syncState?.oldestLoadedId)
        assertEquals(false, syncState?.hasMoreHistory)
    }

    @Test
    fun catchUpOverlapWithHeldMessagesDoesNotDuplicate() = runTest {
        val harness = EngineHarness(this)
        (1..3).forEach {
            harness.store.applyRemoteMessage("m$it", null, "d1", "peer", "old $it", it.toLong())
        }
        harness.store.updateNewestSynced("d1", "m3")
        harness.api.afterHandler = { _, after, _ ->
            if (after == "m3") {
                MessageApiResult.Success(listOf(wireMessage("m3"), wireMessage("m4")))
            } else {
                MessageApiResult.Success(emptyList())
            }
        }
        harness.engine.start()
        runCurrent()
        harness.socket.connect()
        runCurrent()
        assertEquals(4L, harness.store.countAllMessages())
        assertEquals("m4", harness.store.syncState("d1")?.newestSyncedId)
    }

    @Test
    fun framesArrivingDuringCatchUpAreBufferedAndAppliedAfterwards() = runTest {
        val harness = EngineHarness(this)
        val gate = CompletableDeferred<Unit>()
        harness.api.dialogsHandler = {
            MessageApiResult.Success(listOf(WireDialog("d1", "direct")))
        }
        harness.api.beforeHandler = { _, _, _ ->
            gate.await()
            MessageApiResult.Success(emptyList())
        }
        harness.engine.start()
        runCurrent()
        harness.socket.connect()
        runCurrent()
        assertEquals(SyncEngineState.CatchingUp, harness.engine.state.value)
        harness.socket.emitFrame(messageNewFrame("srv-live"))
        runCurrent()
        assertEquals(0L, harness.store.countAllMessages())
        gate.complete(Unit)
        runCurrent()
        assertEquals(SyncEngineState.Live, harness.engine.state.value)
        assertEquals(1L, harness.store.countAllMessages())
    }

    @Test
    fun catchUpFailureStillGoesLive() = runTest {
        val harness = EngineHarness(this)
        harness.api.dialogsHandler = { MessageApiResult.Unavailable("HTTP 404") }
        harness.engine.start()
        runCurrent()
        harness.socket.connect()
        runCurrent()
        assertEquals(SyncEngineState.Live, harness.engine.state.value)
        harness.socket.disconnect()
        runCurrent()
    }

    @Test
    fun failedCatchUpIsRetriedWhileConnected() = runTest {
        val harness = EngineHarness(this)
        var dialogsCall = 0
        harness.api.dialogsHandler = {
            dialogsCall++
            if (dialogsCall == 1) {
                MessageApiResult.Unavailable("HTTP 503")
            } else {
                MessageApiResult.Success(listOf(WireDialog("d1", "direct")))
            }
        }
        harness.api.beforeHandler = { _, _, _ ->
            MessageApiResult.Success(listOf(wireMessage("m1")))
        }
        harness.engine.start()
        runCurrent()
        harness.socket.connect()
        runCurrent()
        assertEquals(SyncEngineState.Live, harness.engine.state.value)
        assertEquals(0L, harness.store.countAllMessages())
        advanceTimeBy(120_000)
        assertEquals(1L, harness.store.countAllMessages())
        assertEquals(2, dialogsCall)
        harness.socket.disconnect()
        runCurrent()
    }

    @Test
    fun catchUpRetryStopsAfterDisconnect() = runTest {
        val harness = EngineHarness(this)
        harness.api.dialogsHandler = { MessageApiResult.Unavailable("HTTP 503") }
        harness.engine.start()
        runCurrent()
        harness.socket.connect()
        runCurrent()
        harness.socket.disconnect()
        runCurrent()
        val callsAfterDisconnect = harness.api.dialogsCalls
        advanceTimeBy(300_000)
        assertEquals(callsAfterDisconnect, harness.api.dialogsCalls)
    }

    @Test
    fun bufferedEventsAreDroppedOnStop() = runTest {
        val harness = EngineHarness(this)
        val gate = CompletableDeferred<Unit>()
        harness.api.dialogsHandler = {
            MessageApiResult.Success(listOf(WireDialog("d1", "direct")))
        }
        harness.api.beforeHandler = { _, _, _ ->
            gate.await()
            MessageApiResult.Success(emptyList())
        }
        harness.engine.start()
        runCurrent()
        harness.socket.connect()
        runCurrent()
        assertEquals(SyncEngineState.CatchingUp, harness.engine.state.value)
        harness.socket.emitFrame(messageNewFrame("srv-stale"))
        runCurrent()
        harness.engine.stop()
        harness.socket.disconnect()
        runCurrent()
        harness.engine.start()
        runCurrent()
        assertEquals(0L, harness.store.countAllMessages())
        assertEquals(SyncEngineState.Disconnected, harness.engine.state.value)
    }

    @Test
    fun disconnectMovesEngineOutOfLive() = runTest {
        val harness = EngineHarness(this)
        harness.engine.start()
        runCurrent()
        harness.socket.connect()
        runCurrent()
        assertEquals(SyncEngineState.Live, harness.engine.state.value)
        harness.socket.disconnect()
        runCurrent()
        assertEquals(SyncEngineState.Disconnected, harness.engine.state.value)
        assertFalse(harness.engine.state.value is SyncEngineState.Live)
    }
}
