package com.relay.ui.chat

import app.cash.turbine.test
import com.relay.auth.SessionManager
import com.relay.auth.StoredTokens
import com.relay.db.MessageStore
import com.relay.network.AuthApi
import com.relay.network.AuthApiResult
import com.relay.network.LoginRequest
import com.relay.network.MessageApiResult
import com.relay.protocol.FrameType
import com.relay.network.RegisterRequest
import com.relay.network.TokenResponse
import com.relay.push.AppPresence
import com.relay.push.PushPermissionRequests
import com.relay.repository.ConnectionPhase
import com.relay.repository.MessageRepositoryImpl
import com.relay.sync.Outbox
import com.relay.sync.ReadReceipts
import com.relay.testutil.FakeConnectionStatus
import com.relay.testutil.FakeMessageApi
import com.relay.testutil.FakeSocket
import com.relay.testutil.FakeTokenStore
import com.relay.testutil.createTestDb
import com.relay.testutil.testJwt
import com.relay.testutil.wireMessage
import com.relay.ui.state.ConnectionUi
import com.relay.ui.state.MessageStatusUi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val DIALOG = "d1"
private const val SELF = "user-1"
private const val FIXED_NOW = 1_785_000_000_000L

private class StubAuthApi : AuthApi {
    override suspend fun login(request: LoginRequest): AuthApiResult<TokenResponse> =
        AuthApiResult.Unreachable("stub")

    override suspend fun register(request: RegisterRequest): AuthApiResult<TokenResponse> =
        AuthApiResult.Unreachable("stub")

    override suspend fun refresh(refreshToken: String): AuthApiResult<TokenResponse> =
        AuthApiResult.Unreachable("stub")

    override suspend fun logout(refreshToken: String) = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
private class ChatHarness(scope: TestScope) {
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
    val tokenStore = FakeTokenStore()
    val session = SessionManager(StubAuthApi(), tokenStore)
    val readReceipts = ReadReceipts(store, socket)
    val repository = MessageRepositoryImpl(store, outbox, api, session, readReceipts)
    val connection = FakeConnectionStatus()

    val presence = AppPresence()
    val permissionRequests = PushPermissionRequests()

    fun viewModel() = ChatViewModel(
        dialogId = DIALOG,
        messages = repository,
        session = session,
        connection = connection,
        presence = presence,
        permissionRequests = permissionRequests,
        now = { FIXED_NOW }
    )

    suspend fun logIn(userId: String = SELF) {
        tokenStore.save(
            StoredTokens(
                accessToken = testJwt(userId),
                refreshToken = "refresh",
                accessExpiresAtMillis = Long.MAX_VALUE / 2,
                refreshExpiresAtMillis = Long.MAX_VALUE / 2
            )
        )
        session.restoreSession()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun sendRendersPendingImmediatelyAndClearsTheDraft() = runTest {
        val harness = ChatHarness(this)
        harness.logIn()
        val viewModel = harness.viewModel()
        runCurrent()

        viewModel.onDraftChange("hello")
        viewModel.send()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("", state.draft)
        val message = state.messages.single()
        assertEquals("hello", message.text)
        assertEquals(MessageStatusUi.SENDING, message.status)
        assertTrue(message.isMine)
    }

    @Test
    fun ackFlipsToSentWithoutChangingRowIdentity() = runTest {
        val harness = ChatHarness(this)
        harness.logIn()
        val viewModel = harness.viewModel()
        runCurrent()

        viewModel.onDraftChange("hello")
        viewModel.send()
        advanceUntilIdle()
        val pending = viewModel.state.value.messages.single()
        val clientMsgId = assertNotNull(
            harness.store.observeMessages(DIALOG).first().single().clientMsgId
        )

        harness.store.applyAck(clientMsgId, serverId = "srv-1", createdAt = FIXED_NOW)
        advanceUntilIdle()

        val sent = viewModel.state.value.messages.single()
        assertEquals(pending.localId, sent.localId)
        assertEquals(MessageStatusUi.SENT, sent.status)
    }

    @Test
    fun failedMessageExposesReasonAndRetryReturnsItToPending() = runTest {
        val harness = ChatHarness(this)
        harness.logIn()
        val viewModel = harness.viewModel()
        runCurrent()

        viewModel.onDraftChange("hello")
        viewModel.send()
        advanceUntilIdle()
        val localId = viewModel.state.value.messages.single().localId
        harness.store.markFailed(localId, "PAYLOAD_TOO_LARGE")
        advanceUntilIdle()

        assertEquals(MessageStatusUi.FAILED, viewModel.state.value.messages.single().status)
        assertEquals("PAYLOAD_TOO_LARGE", viewModel.state.value.messages.single().failReason)

        viewModel.retry(localId)
        advanceUntilIdle()

        val retried = viewModel.state.value.messages.single()
        assertEquals(localId, retried.localId)
        assertEquals(MessageStatusUi.SENDING, retried.status)
        assertNull(retried.failReason)
    }

    @Test
    fun aPeerReceiptTurnsMyDeliveredMessageIntoAReadOne() = runTest {
        val harness = ChatHarness(this)
        harness.logIn()
        val viewModel = harness.viewModel()
        runCurrent()

        viewModel.onDraftChange("hello")
        viewModel.send()
        advanceUntilIdle()
        val clientMsgId = assertNotNull(
            harness.store.observeMessages(DIALOG).first().single().clientMsgId
        )
        harness.store.applyAck(clientMsgId, serverId = "srv-1", createdAt = FIXED_NOW)
        advanceUntilIdle()
        assertEquals(MessageStatusUi.SENT, viewModel.state.value.messages.single().status)

        harness.store.applyReadReceipt(DIALOG, "peer", "srv-1", readAt = FIXED_NOW, selfId = SELF)
        advanceUntilIdle()

        assertEquals(MessageStatusUi.READ, viewModel.state.value.messages.single().status)
    }

    @Test
    fun aReceiptOlderThanAMessageLeavesThatMessageUnread() = runTest {
        val harness = ChatHarness(this)
        harness.logIn()
        val viewModel = harness.viewModel()
        runCurrent()

        harness.store.insertPending("cm-old", DIALOG, SELF, "older", FIXED_NOW - 1_000)
        harness.store.applyAck("cm-old", serverId = "srv-old", createdAt = FIXED_NOW - 1_000)
        harness.store.insertPending("cm-new", DIALOG, SELF, "newer", FIXED_NOW)
        harness.store.applyAck("cm-new", serverId = "srv-new", createdAt = FIXED_NOW)
        harness.store.applyReadReceipt(
            DIALOG,
            "peer",
            "srv-old",
            readAt = FIXED_NOW - 1_000,
            selfId = SELF
        )
        advanceUntilIdle()

        val messages = viewModel.state.value.messages
        assertEquals(MessageStatusUi.SENT, messages[0].status)
        assertEquals(MessageStatusUi.READ, messages[1].status)
    }

    @Test
    fun openingAChatReportsTheNewestIncomingMessageAsRead() = runTest {
        val harness = ChatHarness(this)
        harness.logIn()
        harness.socket.connect(userId = SELF)
        harness.store.applyRemoteMessage("srv-1", null, DIALOG, "peer", "one", FIXED_NOW, SELF)
        harness.store.applyRemoteMessage("srv-2", null, DIALOG, "peer", "two", FIXED_NOW + 1, SELF)
        harness.viewModel()
        advanceUntilIdle()

        val reads = harness.socket.sentFrames.filter { it.type == FrameType.MESSAGE_READ }
        assertEquals(1, reads.size)
        assertEquals(
            "srv-2",
            reads.single().payload?.jsonObject?.get("up_to_message_id")?.jsonPrimitive?.content
        )
        assertEquals(emptyList(), harness.store.unsentReads())
    }

    @Test
    fun aReadTakenWhileDisconnectedStaysQueued() = runTest {
        val harness = ChatHarness(this)
        harness.logIn()
        harness.store.applyRemoteMessage("srv-1", null, DIALOG, "peer", "one", FIXED_NOW, SELF)
        harness.viewModel()
        advanceUntilIdle()

        assertTrue(harness.socket.sentFrames.none { it.type == FrameType.MESSAGE_READ })
        assertEquals(listOf("srv-1"), harness.store.unsentReads().map { it.upToMessageId })
    }

    @Test
    fun myOwnMessagesDoNotTriggerAReadReport() = runTest {
        val harness = ChatHarness(this)
        harness.logIn()
        harness.socket.connect(userId = SELF)
        val viewModel = harness.viewModel()
        runCurrent()

        viewModel.onDraftChange("hello")
        viewModel.send()
        advanceUntilIdle()

        assertTrue(harness.socket.sentFrames.none { it.type == FrameType.MESSAGE_READ })
    }

    @Test
    fun messagesFromOthersAreNotMine() = runTest {
        val harness = ChatHarness(this)
        harness.logIn()
        val viewModel = harness.viewModel()
        harness.store.applyRemoteMessage(
            serverId = "srv-1",
            clientMsgId = null,
            dialogId = DIALOG,
            senderId = "peer",
            text = "hi there",
            createdAt = FIXED_NOW
        )
        advanceUntilIdle()

        val message = viewModel.state.value.messages.single()
        assertEquals(false, message.isMine)
        assertEquals("hi there", message.text)
    }

    @Test
    fun onlyTheOldestMessageOfEachDayCarriesASeparator() = runTest {
        val harness = ChatHarness(this)
        harness.logIn()
        val viewModel = harness.viewModel()
        val dayMillis = 24L * 60 * 60 * 1000
        harness.store.applyRemoteMessage("srv-1", null, DIALOG, "peer", "older", FIXED_NOW - dayMillis)
        harness.store.applyRemoteMessage("srv-2", null, DIALOG, "peer", "newer-a", FIXED_NOW)
        harness.store.applyRemoteMessage("srv-3", null, DIALOG, "peer", "newer-b", FIXED_NOW + 1000)
        advanceUntilIdle()

        val messages = viewModel.state.value.messages
        assertEquals(listOf("newer-b", "newer-a", "older"), messages.map { it.text })
        assertNull(messages[0].daySeparator)
        assertNotNull(messages[1].daySeparator)
        assertNotNull(messages[2].daySeparator)
    }

    @Test
    fun hasMoreHistoryFollowsTheStoredSyncState() = runTest {
        val harness = ChatHarness(this)
        harness.logIn()
        val viewModel = harness.viewModel()
        runCurrent()
        assertEquals(false, viewModel.state.value.hasMoreHistory)

        harness.store.upsertDialog(DIALOG, "direct", null, null)
        harness.store.updateOldestLoaded(DIALOG, "m10", hasMore = true)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.hasMoreHistory)
    }

    @Test
    fun concurrentLoadOlderIssuesASingleRequest() = runTest {
        val harness = ChatHarness(this)
        harness.logIn()
        harness.store.upsertDialog(DIALOG, "direct", null, null)
        harness.store.updateOldestLoaded(DIALOG, "m10", hasMore = true)
        val viewModel = harness.viewModel()
        val gate = CompletableDeferred<Unit>()
        harness.api.beforeHandler = { _, _, _ ->
            gate.await()
            MessageApiResult.Success(listOf(wireMessage("m9", dialogId = DIALOG)))
        }

        viewModel.loadOlder()
        viewModel.loadOlder()
        runCurrent()
        assertTrue(viewModel.state.value.isLoadingOlder)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, harness.api.beforeCalls)
        assertEquals(false, viewModel.state.value.isLoadingOlder)
    }

    @Test
    fun connectionPhaseIsSurfacedToTheUi() = runTest {
        val harness = ChatHarness(this)
        harness.logIn()
        val viewModel = harness.viewModel()

        viewModel.state.test {
            harness.connection.emit(ConnectionPhase.RECONNECTING)
            advanceUntilIdle()
            assertEquals(ConnectionUi.Reconnecting, expectMostRecentItem().connection)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aSendThatCannotBeEnqueuedKeepsTheDraftAndReportsIt() = runTest {
        val harness = ChatHarness(this)
        val viewModel = harness.viewModel()
        runCurrent()

        viewModel.onDraftChange("hello")
        viewModel.send()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("hello", state.draft)
        assertEquals(0, state.messages.size)
        assertNotNull(state.error)

        viewModel.dismissError()
        advanceUntilIdle()
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun textTypedWhileASendIsInFlightIsNotWiped() = runTest {
        val harness = ChatHarness(this)
        harness.logIn()
        val viewModel = harness.viewModel()
        runCurrent()

        viewModel.onDraftChange("first")
        viewModel.send()
        viewModel.onDraftChange("second")
        advanceUntilIdle()

        assertEquals("second", viewModel.state.value.draft)
        assertEquals("first", viewModel.state.value.messages.single().text)
    }

    @Test
    fun historyBeyondTheInitialWindowBecomesVisibleAfterLoadOlder() = runTest {
        val harness = ChatHarness(this)
        harness.logIn()
        val stored = INITIAL_VISIBLE_MESSAGES.toInt() + 40
        repeat(stored) { index ->
            harness.store.applyRemoteMessage(
                serverId = "srv-$index",
                clientMsgId = null,
                dialogId = DIALOG,
                senderId = "peer",
                text = "message-$index",
                createdAt = FIXED_NOW + index
            )
        }
        val viewModel = harness.viewModel()
        advanceUntilIdle()
        assertEquals(INITIAL_VISIBLE_MESSAGES.toInt(), viewModel.state.value.messages.size)

        viewModel.loadOlder()
        advanceUntilIdle()

        assertEquals(stored, viewModel.state.value.messages.size)
        assertEquals(0, harness.api.beforeCalls)
    }

    @Test
    fun blankDraftIsNotSent() = runTest {
        val harness = ChatHarness(this)
        harness.logIn()
        val viewModel = harness.viewModel()
        viewModel.onDraftChange("   ")
        viewModel.send()
        advanceUntilIdle()

        assertEquals(0, viewModel.state.value.messages.size)
        assertEquals("   ", viewModel.state.value.draft)
    }
}
