package com.relay.network

import com.relay.auth.SessionManager
import com.relay.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

private const val TRANSPORT_FAILURE = "Cannot reach the server"

@Serializable
data class GroupParticipantResponse(
    val userId: String,
    val state: String
)

@Serializable
data class SfuAccessResponse(
    val url: String,
    val token: String,
    val expiresAt: String? = null
)

@Serializable
data class GroupCallStateResponse(
    val callId: String,
    val kind: String = "group",
    val media: String = "audio",
    val status: String,
    val initiator: String,
    val startedAt: String,
    val ringExpiresAt: String? = null,
    val answeredAt: String? = null,
    val endedAt: String? = null,
    val endReason: String? = null,
    val durationSeconds: Long? = null,
    val participants: List<GroupParticipantResponse> = emptyList(),
    val livekit: SfuAccessResponse? = null
)

@Serializable
private data class CreateGroupCallBody(
    val callId: String,
    val media: String,
    val inviteeIds: List<String>,
    val sessionId: String? = null
)

@Serializable
private data class SessionOnlyBody(
    val sessionId: String? = null
)

@Serializable
private data class DeclineGroupCallBody(
    val reason: String? = null,
    val sessionId: String? = null
)

sealed interface GroupCallApiResult<out T> {
    data class Success<T>(val value: T) : GroupCallApiResult<T>
    data class Failure(val status: Int?, val reason: String) : GroupCallApiResult<Nothing>
}

interface GroupCallApi {
    suspend fun create(
        callId: String,
        media: String,
        inviteeIds: List<String>,
        sessionId: String?
    ): GroupCallApiResult<GroupCallStateResponse>

    suspend fun join(callId: String, sessionId: String?): GroupCallApiResult<GroupCallStateResponse>
    suspend fun decline(callId: String, reason: String?, sessionId: String?): GroupCallApiResult<GroupCallStateResponse>
    suspend fun leave(callId: String, sessionId: String?): GroupCallApiResult<GroupCallStateResponse>
    suspend fun describe(callId: String): GroupCallApiResult<GroupCallStateResponse>
}

class KtorGroupCallApi(
    private val http: HttpClient,
    private val config: AppConfig,
    private val session: SessionManager
) : GroupCallApi {
    private val base: String get() = "${config.apiBaseUrl}/api/v1/call/group-calls"

    override suspend fun create(
        callId: String,
        media: String,
        inviteeIds: List<String>,
        sessionId: String?
    ): GroupCallApiResult<GroupCallStateResponse> =
        request { token ->
            http.post(base) {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(CreateGroupCallBody(callId, media, inviteeIds, sessionId))
            }
        }

    override suspend fun join(callId: String, sessionId: String?): GroupCallApiResult<GroupCallStateResponse> =
        request { token ->
            http.post("$base/$callId/join") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(SessionOnlyBody(sessionId))
            }
        }

    override suspend fun decline(
        callId: String,
        reason: String?,
        sessionId: String?
    ): GroupCallApiResult<GroupCallStateResponse> =
        request { token ->
            http.post("$base/$callId/decline") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(DeclineGroupCallBody(reason, sessionId))
            }
        }

    override suspend fun leave(callId: String, sessionId: String?): GroupCallApiResult<GroupCallStateResponse> =
        request { token ->
            http.post("$base/$callId/leave") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(SessionOnlyBody(sessionId))
            }
        }

    override suspend fun describe(callId: String): GroupCallApiResult<GroupCallStateResponse> =
        request { token ->
            http.get("$base/$callId") {
                bearerAuth(token)
            }
        }

    private suspend fun request(
        send: suspend (token: String) -> HttpResponse
    ): GroupCallApiResult<GroupCallStateResponse> {
        val token = session.validAccessToken()
            ?: return GroupCallApiResult.Failure(null, "not authenticated")
        var response = attempt(token, send) ?: return GroupCallApiResult.Failure(null, TRANSPORT_FAILURE)
        if (response.status == HttpStatusCode.Unauthorized) {
            val refreshed = session.forceRefresh()
                ?: return GroupCallApiResult.Failure(null, "token refresh failed")
            response = attempt(refreshed, send)
                ?: return GroupCallApiResult.Failure(null, TRANSPORT_FAILURE)
        }
        if (!response.status.isSuccess()) {
            return GroupCallApiResult.Failure(response.status.value, describe(response.status.value))
        }
        return try {
            GroupCallApiResult.Success(response.body())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) { // allow: broad-catch an unreadable body surfaces as a serialization or engine exception with no common KMP supertype; all of them mean the response was unusable and must degrade to a failure value
            GroupCallApiResult.Failure(null, "unreadable response: ${e.message ?: e::class.simpleName}")
        }
    }

    private suspend fun attempt(
        token: String,
        send: suspend (token: String) -> HttpResponse
    ): HttpResponse? =
        try {
            send(token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) { // allow: broad-catch Ktor engines throw platform-specific transport exceptions with no common KMP supertype; any of them means the backend is unreachable
            null
        }

    private fun describe(status: Int): String =
        when (status) {
            403 -> "You are not part of that call"
            404 -> "The call no longer exists"
            409 -> "You are already in a call"
            422 -> "The call was already settled"
            else -> "The call failed (HTTP $status)"
        }
}
