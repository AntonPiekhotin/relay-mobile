package com.relay.network

import com.relay.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val firstName: String,
    val lastName: String
)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("refresh_expires_in") val refreshExpiresIn: Long,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("scope") val scope: String
)

sealed interface AuthApiResult<out T> {
    data class Success<T>(val value: T) : AuthApiResult<T>
    data class InvalidCredentials(val status: Int) : AuthApiResult<Nothing>
    data class Rejected(val status: Int, val body: String) : AuthApiResult<Nothing>
    data class Unreachable(val cause: String) : AuthApiResult<Nothing>
}

interface AuthApi {
    suspend fun login(request: LoginRequest): AuthApiResult<TokenResponse>
    suspend fun register(request: RegisterRequest): AuthApiResult<TokenResponse>
    suspend fun refresh(refreshToken: String): AuthApiResult<TokenResponse>
    suspend fun logout(refreshToken: String)
}

class KtorAuthApi(
    private val http: HttpClient,
    private val config: AppConfig
) : AuthApi {
    override suspend fun login(request: LoginRequest): AuthApiResult<TokenResponse> =
        postForTokens("${config.apiBaseUrl}/api/v1/auth/login", request)

    override suspend fun register(request: RegisterRequest): AuthApiResult<TokenResponse> =
        postForTokens("${config.apiBaseUrl}/api/v1/auth/register", request)

    override suspend fun refresh(refreshToken: String): AuthApiResult<TokenResponse> =
        postForTokens("${config.apiBaseUrl}/api/v1/auth/refresh", RefreshRequest(refreshToken))

    override suspend fun logout(refreshToken: String) {
        runCatching {
            http.post("${config.apiBaseUrl}/api/v1/auth/logout") {
                contentType(ContentType.Application.Json)
                setBody(RefreshRequest(refreshToken))
            }
        }
    }

    private suspend inline fun <reified B> postForTokens(
        url: String,
        body: B
    ): AuthApiResult<TokenResponse> {
        val response = try {
            http.post(url) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: Exception) { // allow: broad-catch Ktor engines throw platform-specific transport exceptions with no common KMP supertype; any of them means the backend is unreachable
            return AuthApiResult.Unreachable(e.message ?: e::class.simpleName ?: "network failure")
        }
        return when {
            response.status.isSuccess() -> AuthApiResult.Success(response.body())
            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden ->
                AuthApiResult.InvalidCredentials(response.status.value)
            else -> AuthApiResult.Rejected(response.status.value, response.bodyTextSafely())
        }
    }
}

private suspend fun HttpResponse.bodyTextSafely(): String =
    runCatching { bodyAsText() }.getOrDefault("")
