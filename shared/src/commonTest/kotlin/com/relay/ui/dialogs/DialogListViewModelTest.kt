package com.relay.ui.dialogs

import com.relay.auth.SessionManager
import com.relay.auth.StoredTokens
import com.relay.db.MessageStore
import com.relay.model.MessageState
import com.relay.network.AuthApi
import com.relay.network.AuthApiResult
import com.relay.network.LoginRequest
import com.relay.network.RegisterRequest
import com.relay.network.TokenResponse
import com.relay.repository.MessageRepositoryImpl
import com.relay.sync.Outbox
import com.relay.testutil.FakeConnectionStatus
import com.relay.testutil.FakeMessageApi
import com.relay.testutil.FakeSocket
import com.relay.testutil.FakeTokenStore
import com.relay.testutil.createTestDb
import com.relay.testutil.testJwt
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

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
private class ListHarness(scope: TestScope) {
    val store = MessageStore(createTestDb(), UnconfinedTestDispatcher(scope.testScheduler))
    val api = FakeMessageApi()
    val outbox = Outbox(
        store = store,
        socket = FakeSocket(),
        api = api,
        scope = scope.backgroundScope,
        now = { scope.testScheduler.currentTime }
    )
    val tokenStore = FakeTokenStore()
    val session = SessionManager(StubAuthApi(), tokenStore)
    val repository = MessageRepositoryImpl(store, outbox, api, session)
    val connection = FakeConnectionStatus()

    fun viewModel() = DialogListViewModel(repository, session, connection, now = { FIXED_NOW })

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
class DialogListViewModelTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun anEmptyDatabaseYieldsAnEmptyButLoadedList() = runTest {
        val harness = ListHarness(this)
        harness.logIn()
        val viewModel = harness.viewModel()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.dialogs.isEmpty())
        assertTrue(viewModel.state.value.isLoaded)
    }

    @Test
    fun dialogsCarryTheNewestMessageAsPreview() = runTest {
        val harness = ListHarness(this)
        harness.logIn()
        val viewModel = harness.viewModel()
        harness.store.upsertDialog("d1", "direct", "Ada", null)
        harness.store.applyRemoteMessage("srv-1", null, "d1", "peer", "older", FIXED_NOW - 5_000)
        harness.store.applyRemoteMessage("srv-2", null, "d1", "peer", "newest", FIXED_NOW)
        advanceUntilIdle()

        val row = viewModel.state.value.dialogs.single()
        assertEquals("d1", row.id)
        assertEquals("Ada", row.title)
        assertEquals("newest", row.preview)
        assertEquals(false, row.previewIsMine)
    }

    @Test
    fun ownPendingSendIsMarkedAsMineWithItsState() = runTest {
        val harness = ListHarness(this)
        harness.logIn()
        val viewModel = harness.viewModel()
        harness.repository.send("d1", "mine")
        advanceUntilIdle()

        val row = viewModel.state.value.dialogs.single()
        assertEquals("mine", row.preview)
        assertTrue(row.previewIsMine)
        assertEquals(MessageState.PENDING, row.previewStatus)
    }

    @Test
    fun dialogsAreOrderedByMostRecentActivity() = runTest {
        val harness = ListHarness(this)
        harness.logIn()
        val viewModel = harness.viewModel()
        harness.store.applyRemoteMessage("srv-1", null, "old", "peer", "a", FIXED_NOW - 60_000)
        harness.store.applyRemoteMessage("srv-2", null, "recent", "peer", "b", FIXED_NOW)
        advanceUntilIdle()

        assertEquals(listOf("recent", "old"), viewModel.state.value.dialogs.map { it.id })
    }

    @Test
    fun aDialogWithoutMessagesFallsBackToAPlaceholderPreview() = runTest {
        val harness = ListHarness(this)
        harness.logIn()
        val viewModel = harness.viewModel()
        harness.store.upsertDialog("d1", "direct", null, null)
        advanceUntilIdle()

        val row = viewModel.state.value.dialogs.single()
        assertEquals("No messages yet", row.preview)
        assertEquals("", row.timestamp)
        assertEquals("Unknown user", row.title)
    }
}
