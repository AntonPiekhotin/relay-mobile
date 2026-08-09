package com.relay.db

import com.relay.model.MessageState
import com.relay.testutil.createTestDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.newStore(): MessageStore =
    MessageStore(createTestDb(), UnconfinedTestDispatcher(testScheduler))

class MessageStoreTest {

    @Test
    fun sameServerIdInsertedTwiceYieldsOneRow() = runTest {
        val store = newStore()
        store.applyRemoteMessage("srv-1", null, "d1", "peer", "hello", 100)
        store.applyRemoteMessage("srv-1", null, "d1", "peer", "hello", 100)
        assertEquals(1L, store.countAllMessages())
    }

    @Test
    fun ackPromotesPendingAndDuplicateAckIsIgnored() = runTest {
        val store = newStore()
        store.insertPending("cm-1", "d1", "me", "hi", 100)
        store.applyAck("cm-1", "srv-1", 200)
        store.applyAck("cm-1", "srv-1", 999)
        val messages = store.observeMessages("d1").first()
        assertEquals(1, messages.size)
        assertEquals(MessageState.SENT, messages[0].state)
        assertEquals("srv-1", messages[0].serverId)
        assertEquals(200L, messages[0].createdAt)
    }

    @Test
    fun ackWithUnparseableTimestampPromotesButKeepsLocalCreatedAt() = runTest {
        val store = newStore()
        store.insertPending("cm-1", "d1", "me", "hi", 100)
        store.applyAck("cm-1", "srv-1", null)
        val message = store.observeMessages("d1").first().single()
        assertEquals(MessageState.SENT, message.state)
        assertEquals("srv-1", message.serverId)
        assertEquals(100L, message.createdAt)
    }

    @Test
    fun ackForUnknownClientMsgIdIsIgnored() = runTest {
        val store = newStore()
        store.applyAck("cm-unknown", "srv-1", 200)
        assertEquals(0L, store.countAllMessages())
    }

    @Test
    fun ackWhenRemoteRowAlreadyExistsCollapsesToOneRow() = runTest {
        val store = newStore()
        store.insertPending("cm-1", "d1", "me", "hi", 100)
        store.applyRemoteMessage("srv-1", null, "d1", "me", "hi", 200)
        store.applyAck("cm-1", "srv-1", 200)
        val messages = store.observeMessages("d1").first()
        assertEquals(1, messages.size)
        assertEquals("srv-1", messages[0].serverId)
        assertEquals(MessageState.SENT, messages[0].state)
    }

    @Test
    fun remoteMessageForLocallyPendingRowPromotesInsteadOfDuplicating() = runTest {
        val store = newStore()
        store.insertPending("cm-1", "d1", "me", "hi", 100)
        store.applyRemoteMessage("srv-1", "cm-1", "d1", "me", "hi", 200)
        val messages = store.observeMessages("d1").first()
        assertEquals(1, messages.size)
        assertEquals("srv-1", messages[0].serverId)
        assertEquals("cm-1", messages[0].clientMsgId)
        assertEquals(MessageState.SENT, messages[0].state)
    }

    @Test
    fun serverCreatedAtReordersMessages() = runTest {
        val store = newStore()
        store.insertPending("cm-1", "d1", "me", "first", 100)
        store.insertPending("cm-2", "d1", "me", "second", 200)
        store.applyAck("cm-1", "srv-1", 300)
        val messages = store.observeMessages("d1").first()
        assertEquals("first", messages[0].text)
        assertEquals("second", messages[1].text)
    }

    @Test
    fun remoteMessageCreatesDialogAndSyncStateLazily() = runTest {
        val store = newStore()
        store.applyRemoteMessage("srv-1", null, "d-new", "peer", "hello", 100)
        val dialogs = store.observeDialogs().first()
        assertEquals(listOf("d-new"), dialogs.map { it.id })
        assertEquals(100L, dialogs[0].lastMessageAt)
        assertNotNull(store.syncState("d-new"))
    }

    @Test
    fun clearAllWipesEverything() = runTest {
        val store = newStore()
        store.insertPending("cm-1", "d1", "me", "hi", 100)
        store.applyRemoteMessage("srv-1", null, "d2", "peer", "yo", 200)
        store.clearAll()
        assertEquals(0L, store.countAllMessages())
        assertEquals(emptyList(), store.observeDialogs().first())
    }
}
