package com.relay.network

import com.relay.auth.SessionManager
import com.relay.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

private const val TRANSPORT_FAILURE = "Cannot reach the server"

@Serializable
data class IceServerResponse(
    val urls: List<String> = emptyList(),
    val username: String? = null,
    val credential: String? = null
)

@Serializable
data class IceServersResponse(
    val iceServers: List<IceServerResponse> = emptyList(),
    val ttlSeconds: Long = 0
)

@Serializable
data class CallHistoryEntryResponse(
    val id: String,
    val dialogId: String? = null,
    val direction: String,
    val peerId: String? = null,
    val media: String,
    val status: String,
    val startedAt: String,
    val answeredAt: String? = null,
    val endedAt: String? = null,
    val durationSeconds: Long? = null,
    val endReason: String? = null
)

@Serializable
data class CallHistoryResponse(
    val calls: List<CallHistoryEntryResponse> = emptyList(),
    val nextCursor: String? = null
)

sealed interface CallApiResult<out T> {
    data class Success<T>(val value: T) : CallApiResult<T>
    data class Failure(val reason: String) : CallApiResult<Nothing>
}

interface CallApi {
    suspend fun iceServers(): CallApiResult<IceServersResponse>
    suspend fun history(before: String?, limit: Int): CallApiResult<CallHistoryResponse>
}

class KtorCallApi(
    private val http: HttpClient,
    private val config: AppConfig,
    private val session: SessionManager
) : CallApi {
    private val base: String get() = "${config.apiBaseUrl}/api/v1/call"

    override suspend fun iceServers(): CallApiResult<IceServersResponse> =
        get("$base/ice-servers") { }

    override suspend fun history(before: String?, limit: Int): CallApiResult<CallHistoryResponse> =
        get("$base/calls") {
            if (before != null) parameter("before", before)
            parameter("limit", limit)
        }

    private suspend inline fun <reified T> get(
        url: String,
        crossinline configure: HttpRequestBuilder.() -> Unit
    ): CallApiResult<T> {
        val token = session.validAccessToken()
            ?: return CallApiResult.Failure("not authenticated")
        var response = attempt(url, token, configure) ?: return CallApiResult.Failure(TRANSPORT_FAILURE)
        if (response.status == HttpStatusCode.Unauthorized) {
            val refreshed = session.forceRefresh()
                ?: return CallApiResult.Failure("token refresh failed")
            response = attempt(url, refreshed, configure)
                ?: return CallApiResult.Failure(TRANSPORT_FAILURE)
        }
        if (!response.status.isSuccess()) {
            return CallApiResult.Failure("HTTP ${response.status.value}")
        }
        return try {
            CallApiResult.Success(response.body())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) { // allow: broad-catch an unreadable body surfaces as a serialization or engine exception with no common KMP supertype; all of them mean the response was unusable and must degrade to a failure value
            CallApiResult.Failure("unreadable response: ${e.message ?: e::class.simpleName}")
        }
    }

    private suspend inline fun attempt(
        url: String,
        token: String,
        crossinline configure: HttpRequestBuilder.() -> Unit
    ): HttpResponse? =
        try {
            http.get(url) {
                bearerAuth(token)
                configure()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) { // allow: broad-catch Ktor engines throw platform-specific transport exceptions with no common KMP supertype; any of them means the backend is unreachable
            null
        }
}
