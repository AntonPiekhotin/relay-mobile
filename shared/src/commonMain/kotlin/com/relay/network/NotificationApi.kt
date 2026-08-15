package com.relay.network

import com.relay.auth.SessionManager
import com.relay.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

@Serializable
data class RegisterDeviceTokenRequest(
    val deviceId: String,
    val platform: String,
    val fcmToken: String? = null,
    val voipToken: String? = null
)

sealed interface NotificationApiResult {
    data object Success : NotificationApiResult
    data class Unavailable(val reason: String) : NotificationApiResult
    data class Rejected(val status: Int) : NotificationApiResult
}

interface NotificationApi {
    suspend fun registerDevice(request: RegisterDeviceTokenRequest): NotificationApiResult
    suspend fun unregisterDevice(deviceId: String): NotificationApiResult
}

class KtorNotificationApi(
    private val http: HttpClient,
    private val config: AppConfig,
    private val session: SessionManager
) : NotificationApi {
    private val base: String get() = "${config.apiBaseUrl}/api/v1/notification/device-tokens"

    override suspend fun registerDevice(request: RegisterDeviceTokenRequest): NotificationApiResult =
        execute { token ->
            http.put(base) {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun unregisterDevice(deviceId: String): NotificationApiResult =
        execute { token -> http.delete("$base/$deviceId") { bearerAuth(token) } }

    private suspend fun execute(send: suspend (String) -> HttpResponse): NotificationApiResult {
        val token = session.validAccessToken()
            ?: return NotificationApiResult.Unavailable("not authenticated")
        var response = attempt(send, token)
            ?: return NotificationApiResult.Unavailable("network failure")
        if (response.status == HttpStatusCode.Unauthorized) {
            val refreshed = session.forceRefresh()
                ?: return NotificationApiResult.Unavailable("token refresh failed")
            response = attempt(send, refreshed)
                ?: return NotificationApiResult.Unavailable("network failure")
        }
        return when {
            response.status.isSuccess() -> NotificationApiResult.Success
            isTransientStatus(response.status) -> NotificationApiResult.Unavailable("HTTP ${response.status.value}")
            else -> NotificationApiResult.Rejected(response.status.value)
        }
    }

    private suspend fun attempt(send: suspend (String) -> HttpResponse, token: String): HttpResponse? =
        try {
            send(token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) { // allow: broad-catch Ktor engines throw platform-specific transport exceptions with no common KMP supertype; any of them means the backend is unreachable and the registration should be retried
            null
        }

    private fun isTransientStatus(status: HttpStatusCode): Boolean =
        status.value >= 500 ||
            status == HttpStatusCode.Unauthorized ||
            status == HttpStatusCode.RequestTimeout ||
            status == HttpStatusCode.TooManyRequests
}
