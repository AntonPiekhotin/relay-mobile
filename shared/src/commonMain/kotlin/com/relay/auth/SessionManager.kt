package com.relay.auth

import com.relay.network.AuthApi
import com.relay.network.AuthApiResult
import com.relay.network.LoginRequest
import com.relay.network.RegisterRequest
import com.relay.network.TokenResponse
import com.relay.protocol.nowEpochMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface AuthState {
    data object Unknown : AuthState
    data object LoggedOut : AuthState
    data class LoggedIn(val userId: String?) : AuthState
}

sealed interface AuthResult {
    data object Success : AuthResult
    data class Failure(val reason: String) : AuthResult
}

private const val EXPIRY_SKEW_MILLIS = 30_000L

class SessionManager(
    private val authApi: AuthApi,
    private val tokenStore: TokenStore
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<AuthState>(AuthState.Unknown)
    val state: StateFlow<AuthState> = mutableState.asStateFlow()

    suspend fun restoreSession() {
        val tokens = tokenStore.load()
        mutableState.value = when {
            tokens == null -> AuthState.LoggedOut
            tokens.refreshExpiresAtMillis <= nowEpochMillis() -> {
                tokenStore.clear()
                AuthState.LoggedOut
            }
            else -> AuthState.LoggedIn(JwtClaims.subjectOf(tokens.accessToken))
        }
    }

    suspend fun login(email: String, password: String): AuthResult =
        applyTokenResult(authApi.login(LoginRequest(email, password)))

    suspend fun register(
        email: String,
        password: String,
        firstName: String,
        lastName: String
    ): AuthResult =
        applyTokenResult(authApi.register(RegisterRequest(email, password, firstName, lastName)))

    suspend fun logout() {
        tokenStore.load()?.let { authApi.logout(it.refreshToken) }
        tokenStore.clear()
        mutableState.value = AuthState.LoggedOut
    }

    suspend fun validAccessToken(): String? = mutex.withLock {
        val tokens = tokenStore.load() ?: return@withLock null
        if (tokens.accessExpiresAtMillis > nowEpochMillis() + EXPIRY_SKEW_MILLIS) {
            return@withLock tokens.accessToken
        }
        refreshLocked(tokens)
    }

    suspend fun forceRefresh(): String? = mutex.withLock {
        val tokens = tokenStore.load() ?: return@withLock null
        refreshLocked(tokens)
    }

    private suspend fun refreshLocked(tokens: StoredTokens): String? =
        when (val result = authApi.refresh(tokens.refreshToken)) {
            is AuthApiResult.Success -> {
                val stored = result.value.toStoredTokens()
                tokenStore.save(stored)
                mutableState.value = AuthState.LoggedIn(JwtClaims.subjectOf(stored.accessToken))
                stored.accessToken
            }
            is AuthApiResult.InvalidCredentials, is AuthApiResult.Rejected -> {
                tokenStore.clear()
                mutableState.value = AuthState.LoggedOut
                null
            }
            is AuthApiResult.Unreachable -> tokens.accessToken
        }

    private suspend fun applyTokenResult(result: AuthApiResult<TokenResponse>): AuthResult =
        when (result) {
            is AuthApiResult.Success -> {
                val stored = result.value.toStoredTokens()
                tokenStore.save(stored)
                mutableState.value = AuthState.LoggedIn(JwtClaims.subjectOf(stored.accessToken))
                AuthResult.Success
            }
            is AuthApiResult.InvalidCredentials -> AuthResult.Failure("Invalid email or password")
            is AuthApiResult.Rejected -> AuthResult.Failure("Request rejected (HTTP ${result.status})")
            is AuthApiResult.Unreachable -> AuthResult.Failure("Cannot reach server: ${result.cause}")
        }
}

private fun TokenResponse.toStoredTokens(): StoredTokens {
    val now = nowEpochMillis()
    return StoredTokens(
        accessToken = accessToken,
        refreshToken = refreshToken,
        accessExpiresAtMillis = now + expiresIn * 1000,
        refreshExpiresAtMillis = now + refreshExpiresIn * 1000
    )
}
