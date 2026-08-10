package com.relay.repository

import com.relay.auth.SessionManager
import com.relay.db.MessageStore
import com.relay.model.MessageState
import com.relay.network.AuthApi
import com.relay.network.AuthApiResult
import com.relay.network.LoginRequest
import com.relay.network.MessageApiResult
import com.relay.network.OpenedDialogResponse
import com.relay.network.RegisterRequest
import com.relay.network.TokenResponse
import com.relay.sync.Outbox
import com.relay.auth.StoredTokens
import com.relay.testutil.FakeMessageApi
import com.relay.testutil.FakeSocket
import com.relay.testutil.FakeTokenStore
import com.relay.testutil.createTestDb
import com.relay.testutil.testJwt
import com.relay.testutil.wireMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

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
private class RepoHarness(scope: TestScope) {
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
    val repository = MessageRepositoryImpl(store, outbox, api, session)

    suspend fun logIn(userId: String = "user-1") {
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

class MessageRepositoryTest {

    @Test
    fun sendInsertsPendingMessageImmediately() = runTest {
        val harness = RepoHarness(this)
        harness.logIn("user-1")
        harness.repository.send("d1", "  hello  ")
        val message = harness.store.observeMessages("d1").first().single()
        assertEquals(MessageState.PENDING, message.state)
        assertEquals("hello", message.text)
        assertEquals("user-1", message.senderId)
        assertNotNull(message.clientMsgId)
        assertEquals(null, message.serverId)
    }

    @Test
    fun blankSendIsIgnored() = runTest {
        val harness = RepoHarness(this)
        harness.logIn()
        harness.repository.send("d1", "   ")
        assertEquals(0L, harness.store.countAllMessages())
    }

    @Test
    fun sendWithoutALoggedInUserIsDropped() = runTest {
        val harness = RepoHarness(this)
        harness.repository.send("d1", "hello")
        assertEquals(0L, harness.store.countAllMessages())
    }

    @Test
    fun retryReusesTheSameRowAndClientMsgId() = runTest {
        val harness = RepoHarness(this)
        harness.logIn()
        harness.repository.send("d1", "hello")
        val original = harness.store.observeMessages("d1").first().single()
        harness.store.markFailed(original.localId, "retries exhausted")
        harness.repository.retry(original.localId)
        val retried = harness.store.observeMessages("d1").first().single()
        assertEquals(original.localId, retried.localId)
        assertEquals(original.clientMsgId, retried.clientMsgId)
        assertEquals(MessageState.PENDING, retried.state)
        assertEquals(0L, retried.attemptCount)
    }

    @Test
    fun openingADialogStoresItLocallySoItSurvivesWithoutADialogListEndpoint() = runTest {
        val harness = RepoHarness(this)
        harness.logIn()
        harness.api.openDialogHandler = {
            MessageApiResult.Success(
                OpenedDialogResponse(
                    id = "167f2922-36d9-4bc4-8cfe-0946601752ab",
                    type = "direct",
                    participantIds = listOf("user-1", "peer-9"),
                    createdAt = "2026-08-09T20:54:57.327612Z"
                )
            )
        }

        val result = harness.repository.openDirectDialog("peer-9")

        assertIs<OpenDialogResult.Opened>(result)
        assertEquals("167f2922-36d9-4bc4-8cfe-0946601752ab", result.dialogId)
        val stored = harness.store.observeDialogs().first().single()
        assertEquals("167f2922-36d9-4bc4-8cfe-0946601752ab", stored.id)
        assertEquals("direct", stored.type)
        assertEquals(1_786_308_897_327L, stored.lastMessageAt)
    }

    @Test
    fun aRefusedOpenIsReportedAndStoresNothing() = runTest {
        val harness = RepoHarness(this)
        harness.logIn()
        harness.api.openDialogHandler = { MessageApiResult.Rejected(400) }

        val result = harness.repository.openDirectDialog("myself")

        assertIs<OpenDialogResult.Failed>(result)
        assertEquals(0, harness.store.observeDialogs().first().size)
    }

    @Test
    fun concurrentLoadOlderIssuesASingleRequest() = runTest {
        val harness = RepoHarness(this)
        harness.store.upsertDialog("d1", "direct", null, null)
        harness.store.updateOldestLoaded("d1", "m10", hasMore = true)
        val gate = CompletableDeferred<Unit>()
        harness.api.beforeHandler = { _, _, _ ->
            gate.await()
            MessageApiResult.Success(listOf(wireMessage("m9")))
        }
        launch { harness.repository.loadOlder("d1") }
        launch { harness.repository.loadOlder("d1") }
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, harness.api.beforeCalls)
        assertEquals(1L, harness.store.countAllMessages())
    }

    @Test
    fun loadOlderUpdatesCursorAndStopsWhenHistoryIsExhausted() = runTest {
        val harness = RepoHarness(this)
        harness.store.upsertDialog("d1", "direct", null, null)
        harness.store.updateOldestLoaded("d1", "m10", hasMore = true)
        harness.api.beforeHandler = { _, before, _ ->
            if (before == "m10") {
                MessageApiResult.Success(listOf(wireMessage("m9"), wireMessage("m8")))
            } else {
                MessageApiResult.Success(emptyList())
            }
        }
        harness.repository.loadOlder("d1")
        val syncState = harness.store.syncState("d1")
        assertEquals("m8", syncState?.oldestLoadedId)
        assertEquals(false, syncState?.hasMoreHistory)
        harness.repository.loadOlder("d1")
        assertEquals(1, harness.api.beforeCalls)
    }
}
