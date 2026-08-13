package com.relay.db

import com.relay.testutil.createTestDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

private const val SELF = "me"
private const val PEER = "peer"

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.newStore(): MessageStore =
    MessageStore(createTestDb(), UnconfinedTestDispatcher(testScheduler))

class ReadCursorStoreTest {

    @Test
    fun peerReadCursorOnlyMovesForward() = runTest {
        val store = newStore()
        store.insertPending("cm-1", "d1", SELF, "hi", 100)
        store.applyReadReceipt("d1", PEER, "srv-1", readAt = 300, selfId = SELF)
        store.applyReadReceipt("d1", PEER, "srv-0", readAt = 200, selfId = SELF)

        assertEquals(300L, store.observeDialog("d1").first()?.peerReadAt)
    }

    @Test
    fun aReceiptFromAnotherOfMyDevicesMovesMyCursorInsteadOfThePeers() = runTest {
        val store = newStore()
        store.applyRemoteMessage("srv-1", null, "d1", PEER, "hello", 100, selfId = SELF)
        store.applyReadReceipt("d1", SELF, "srv-1", readAt = 100, selfId = SELF)

        assertNull(store.observeDialog("d1").first()?.peerReadAt)
        assertEquals(0L, store.observeDialogSummaries(SELF).first().single().unreadCount)
        assertEquals(emptyList(), store.unsentReads())
    }

    @Test
    fun ownReadCursorIsQueuedUntilItIsReportedAsSent() = runTest {
        val store = newStore()
        store.applyRemoteMessage("srv-1", null, "d1", PEER, "hello", 100, selfId = SELF)
        store.markSelfRead("d1", "srv-1", 100)

        assertEquals(listOf("srv-1"), store.unsentReads().map { it.upToMessageId })

        store.markSelfReadSent("d1", "srv-1")
        assertEquals(emptyList(), store.unsentReads())
    }

    @Test
    fun aStaleSentAcknowledgementDoesNotDropANewerQueuedCursor() = runTest {
        val store = newStore()
        store.applyRemoteMessage("srv-1", null, "d1", PEER, "one", 100, selfId = SELF)
        store.applyRemoteMessage("srv-2", null, "d1", PEER, "two", 200, selfId = SELF)
        store.markSelfRead("d1", "srv-1", 100)
        store.markSelfRead("d1", "srv-2", 200)

        store.markSelfReadSent("d1", "srv-1")

        assertEquals(listOf("srv-2"), store.unsentReads().map { it.upToMessageId })
    }

    @Test
    fun ownReadCursorNeverMovesBackwards() = runTest {
        val store = newStore()
        store.applyRemoteMessage("srv-1", null, "d1", PEER, "one", 100, selfId = SELF)
        store.applyRemoteMessage("srv-2", null, "d1", PEER, "two", 200, selfId = SELF)
        store.markSelfRead("d1", "srv-2", 200)
        store.markSelfRead("d1", "srv-1", 100)

        assertEquals(listOf("srv-2"), store.unsentReads().map { it.upToMessageId })
        assertEquals(0L, store.observeDialogSummaries(SELF).first().single().unreadCount)
    }

    @Test
    fun unreadCountsOnlyMessagesFromOthersPastMyCursor() = runTest {
        val store = newStore()
        store.applyRemoteMessage("srv-1", null, "d1", PEER, "one", 100, selfId = SELF)
        store.applyRemoteMessage("srv-2", null, "d1", PEER, "two", 200, selfId = SELF)
        store.insertPending("cm-1", "d1", SELF, "mine", 300)

        assertEquals(2L, store.observeDialogSummaries(SELF).first().single().unreadCount)

        store.markSelfRead("d1", "srv-1", 100)
        assertEquals(1L, store.observeDialogSummaries(SELF).first().single().unreadCount)

        store.markSelfRead("d1", "srv-2", 200)
        assertEquals(0L, store.observeDialogSummaries(SELF).first().single().unreadCount)
    }

    @Test
    fun theNewestIncomingMessageIsTheReadPosition() = runTest {
        val store = newStore()
        store.applyRemoteMessage("srv-1", null, "d1", PEER, "one", 100, selfId = SELF)
        store.applyRemoteMessage("srv-2", null, "d1", PEER, "two", 200, selfId = SELF)
        store.insertPending("cm-1", "d1", SELF, "mine", 300)

        val position = store.newestIncoming("d1", SELF)
        assertEquals("srv-2", position?.messageId)
        assertEquals(200L, position?.createdAt)
    }

    @Test
    fun aDialogWithOnlyMyOwnMessagesHasNoReadPosition() = runTest {
        val store = newStore()
        store.insertPending("cm-1", "d1", SELF, "mine", 100)

        assertNull(store.newestIncoming("d1", SELF))
    }
}
