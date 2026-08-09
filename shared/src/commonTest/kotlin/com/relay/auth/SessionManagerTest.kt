package com.relay.auth

import com.relay.network.AuthApi
import com.relay.network.AuthApiResult
import com.relay.network.LoginRequest
import com.relay.network.RegisterRequest
import com.relay.network.TokenResponse
import com.relay.protocol.nowEpochMillis
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val HOUR_SECONDS = 3_600L
private const val DAY_SECONDS = 86_400L

class SessionManagerTest {

    private val tokenStore = InMemoryTokenStore()

    @Test
    fun successfulLoginStoresTokensAndLogsIn() = runTest {
        val manager = SessionManager(FakeAuthApi(loginResult = success("access-1")), tokenStore)
        val result = manager.login("a@b.c", "pw")
        assertIs<AuthResult.Success>(result)
        assertIs<AuthState.LoggedIn>(manager.state.value)
        assertEquals("access-1", tokenStore.load()?.accessToken)
    }

    @Test
    fun invalidCredentialsFailWithoutStoringTokens() = runTest {
        val manager = SessionManager(
            FakeAuthApi(loginResult = AuthApiResult.InvalidCredentials(401)),
            tokenStore
        )
        val result = manager.login("a@b.c", "wrong")
        assertIs<AuthResult.Failure>(result)
        assertNull(tokenStore.load())
    }

    @Test
    fun restoreWithNoTokensIsLoggedOut() = runTest {
        val manager = SessionManager(FakeAuthApi(), tokenStore)
        manager.restoreSession()
        assertIs<AuthState.LoggedOut>(manager.state.value)
    }

    @Test
    fun restoreWithLiveRefreshTokenIsLoggedIn() = runTest {
        tokenStore.save(freshTokens())
        val manager = SessionManager(FakeAuthApi(), tokenStore)
        manager.restoreSession()
        assertIs<AuthState.LoggedIn>(manager.state.value)
    }

    @Test
    fun restoreWithExpiredRefreshTokenClearsAndLogsOut() = runTest {
        tokenStore.save(
            freshTokens().copy(refreshExpiresAtMillis = nowEpochMillis() - 1_000)
        )
        val manager = SessionManager(FakeAuthApi(), tokenStore)
        manager.restoreSession()
        assertIs<AuthState.LoggedOut>(manager.state.value)
        assertNull(tokenStore.load())
    }

    @Test
    fun validAccessTokenReturnsCurrentWhenFresh() = runTest {
        tokenStore.save(freshTokens(accessToken = "still-good"))
        val api = FakeAuthApi()
        val manager = SessionManager(api, tokenStore)
        assertEquals("still-good", manager.validAccessToken())
        assertEquals(0, api.refreshCalls)
    }

    @Test
    fun validAccessTokenRefreshesWhenExpired() = runTest {
        tokenStore.save(
            freshTokens(accessToken = "stale").copy(accessExpiresAtMillis = nowEpochMillis() - 1)
        )
        val api = FakeAuthApi(refreshResult = success("refreshed"))
        val manager = SessionManager(api, tokenStore)
        assertEquals("refreshed", manager.validAccessToken())
        assertEquals(1, api.refreshCalls)
        assertEquals("refreshed", tokenStore.load()?.accessToken)
    }

    @Test
    fun rejectedRefreshLogsOutAndClearsTokens() = runTest {
        tokenStore.save(
            freshTokens().copy(accessExpiresAtMillis = nowEpochMillis() - 1)
        )
        val api = FakeAuthApi(refreshResult = AuthApiResult.InvalidCredentials(401))
        val manager = SessionManager(api, tokenStore)
        assertNull(manager.validAccessToken())
        assertIs<AuthState.LoggedOut>(manager.state.value)
        assertNull(tokenStore.load())
    }

    @Test
    fun unreachableRefreshKeepsExistingToken() = runTest {
        tokenStore.save(
            freshTokens(accessToken = "old").copy(accessExpiresAtMillis = nowEpochMillis() - 1)
        )
        val api = FakeAuthApi(refreshResult = AuthApiResult.Unreachable("offline"))
        val manager = SessionManager(api, tokenStore)
        assertEquals("old", manager.validAccessToken())
        assertNotNull(tokenStore.load())
    }

    @Test
    fun logoutClearsTokensAndRevokesRemotely() = runTest {
        tokenStore.save(freshTokens())
        val api = FakeAuthApi()
        val manager = SessionManager(api, tokenStore)
        manager.logout()
        assertIs<AuthState.LoggedOut>(manager.state.value)
        assertNull(tokenStore.load())
        assertEquals(1, api.logoutCalls)
    }

    private fun freshTokens(accessToken: String = "access") = StoredTokens(
        accessToken = accessToken,
        refreshToken = "refresh",
        accessExpiresAtMillis = nowEpochMillis() + HOUR_SECONDS * 1000,
        refreshExpiresAtMillis = nowEpochMillis() + DAY_SECONDS * 1000
    )

    private fun success(accessToken: String) = AuthApiResult.Success(
        TokenResponse(
            accessToken = accessToken,
            expiresIn = HOUR_SECONDS,
            refreshExpiresIn = DAY_SECONDS,
            refreshToken = "refresh-next",
            tokenType = "Bearer",
            scope = "openid"
        )
    )
}

private class InMemoryTokenStore : TokenStore {
    private var tokens: StoredTokens? = null
    override suspend fun save(tokens: StoredTokens) {
        this.tokens = tokens
    }

    override suspend fun load(): StoredTokens? = tokens
    override suspend fun clear() {
        tokens = null
    }
}

private class FakeAuthApi(
    private val loginResult: AuthApiResult<TokenResponse> = AuthApiResult.Unreachable("not stubbed"),
    private val refreshResult: AuthApiResult<TokenResponse> = AuthApiResult.Unreachable("not stubbed")
) : AuthApi {
    var refreshCalls = 0
    var logoutCalls = 0

    override suspend fun login(request: LoginRequest): AuthApiResult<TokenResponse> = loginResult

    override suspend fun register(request: RegisterRequest): AuthApiResult<TokenResponse> = loginResult

    override suspend fun refresh(refreshToken: String): AuthApiResult<TokenResponse> {
        refreshCalls++
        return refreshResult
    }

    override suspend fun logout(refreshToken: String) {
        logoutCalls++
    }
}
