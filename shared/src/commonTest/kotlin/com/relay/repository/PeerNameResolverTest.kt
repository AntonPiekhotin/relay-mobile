package com.relay.repository

import com.relay.auth.SessionManager
import com.relay.auth.StoredTokens
import com.relay.db.MessageStore
import com.relay.network.AuthApi
import com.relay.network.AuthApiResult
import com.relay.network.LoginRequest
import com.relay.network.RegisterRequest
import com.relay.network.TokenResponse
import com.relay.testutil.FakeTokenStore
import com.relay.testutil.FakeUserRepository
import com.relay.testutil.createTestDb
import com.relay.testutil.testJwt
import com.relay.testutil.userSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private const val SELF = "me"

private class OfflineAuthApi : AuthApi {
    override suspend fun login(request: LoginRequest): AuthApiResult<TokenResponse> =
        AuthApiResult.Unreachable("stub")

    override suspend fun register(request: RegisterRequest): AuthApiResult<TokenResponse> =
        AuthApiResult.Unreachable("stub")

    override suspend fun refresh(refreshToken: String): AuthApiResult<TokenResponse> =
        AuthApiResult.Unreachable("stub")

    override suspend fun logout(refreshToken: String) = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
private class ResolverHarness(scope: TestScope) {
    val store = MessageStore(createTestDb(), UnconfinedTestDispatcher(scope.testScheduler))
    val users = FakeUserRepository()
    val tokenStore = FakeTokenStore()
    val session = SessionManager(OfflineAuthApi(), tokenStore)
    val resolver = PeerNameResolver(store, users, session, scope.backgroundScope)

    suspend fun titleOf(dialogId: String): String? =
        store.observeDialog(dialogId).first()?.title

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
class PeerNameResolverTest {

    @Test
    fun aDialogLearnedFromAnIncomingMessageIsNamedAfterItsSender() = runTest {
        val harness = ResolverHarness(this)
        harness.users.lookupHandler = { id -> UserResult.Success(userSummary(id, "Ada", "Lovelace")) }
        harness.resolver.start()
        runCurrent()

        harness.store.applyRemoteMessage("srv-1", null, "d1", "peer-1", "hi", 100, selfId = "me")
        runCurrent()

        assertEquals("Ada Lovelace", harness.titleOf("d1"))
        assertEquals(listOf("peer-1"), harness.users.lookedUp)
    }

    @Test
    fun anAlreadyNamedDialogIsNeverLookedUp() = runTest {
        val harness = ResolverHarness(this)
        harness.resolver.start()
        runCurrent()

        harness.store.upsertDialog("d1", "direct", "Ada Lovelace", null, peerId = "peer-1")
        runCurrent()

        assertEquals("Ada Lovelace", harness.titleOf("d1"))
        assertEquals(emptyList(), harness.users.lookedUp)
    }

    @Test
    fun myOwnMessagesNeverNameTheDialogAfterMe() = runTest {
        val harness = ResolverHarness(this)
        harness.resolver.start()
        runCurrent()

        harness.store.applyRemoteMessage("srv-1", null, "d1", "me", "hi", 100, selfId = "me")
        runCurrent()

        assertNull(harness.titleOf("d1"))
        assertEquals(emptyList(), harness.users.lookedUp)
    }

    @Test
    fun aDialogStoredBeforePeersWereTrackedIsNamedFromItsMessageHistory() = runTest {
        val harness = ResolverHarness(this)
        harness.logIn()
        harness.users.lookupHandler = { id -> UserResult.Success(userSummary(id, "Ada", "Lovelace")) }
        harness.store.upsertDialog("d1", "direct", null, null)
        harness.store.applyRemoteMessage("srv-1", null, "d1", "peer-1", "hi", 100)

        harness.resolver.start()
        runCurrent()

        assertEquals("Ada Lovelace", harness.titleOf("d1"))
    }

    @Test
    fun anUnreachableDirectoryLeavesTheDialogUnnamedAndRetriesLater() = runTest {
        val harness = ResolverHarness(this)
        harness.users.lookupHandler = { UserResult.Failure("Cannot reach the server") }
        harness.resolver.start()
        runCurrent()

        harness.store.applyRemoteMessage("srv-1", null, "d1", "peer-1", "hi", 100, selfId = "me")
        runCurrent()
        assertNull(harness.titleOf("d1"))

        harness.users.lookupHandler = { id -> UserResult.Success(userSummary(id, "Ada", "Lovelace")) }
        harness.store.applyRemoteMessage("srv-2", null, "d1", "peer-1", "again", 200, selfId = "me")
        runCurrent()

        assertEquals("Ada Lovelace", harness.titleOf("d1"))
    }
}
