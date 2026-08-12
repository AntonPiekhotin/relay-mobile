package com.relay.network

import com.relay.auth.SessionManager
import com.relay.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

@Serializable
data class WireMessage(
    val messageId: String,
    val dialogId: String,
    val senderId: String,
    val text: String,
    val createdAt: String,
    val clientMsgId: String? = null
)

@Serializable
data class WireDialog(
    val dialogId: String,
    val type: String,
    val participantIds: List<String> = emptyList(),
    val lastMessageAt: String? = null,
    val unreadCount: Long = 0
)

@Serializable
data class MessagePageResponse(
    val messages: List<WireMessage>,
    val nextCursor: String? = null
)

@Serializable
data class DialogListResponse(
    val dialogs: List<WireDialog>
)

@Serializable
data class FallbackSendRequest(
    val clientMsgId: String,
    val dialogId: String,
    val text: String
)

@Serializable
data class OpenDirectDialogRequest(
    val peerId: String
)

@Serializable
data class OpenedDialogResponse(
    val id: String,
    val type: String,
    val participantIds: List<String> = emptyList(),
    val createdAt: String? = null
)

@Serializable
data class FallbackSendResponse(
    val messageId: String,
    val clientMsgId: String,
    val createdAt: String
)

sealed interface MessageApiResult<out T> {
    data class Success<T>(val value: T) : MessageApiResult<T>
    data class Unavailable(val reason: String) : MessageApiResult<Nothing>
    data class Rejected(val status: Int) : MessageApiResult<Nothing>
}

inline fun <T, R> MessageApiResult<T>.mapValue(transform: (T) -> R): MessageApiResult<R> =
    when (this) {
        is MessageApiResult.Success -> MessageApiResult.Success(transform(value))
        is MessageApiResult.Unavailable -> this
        is MessageApiResult.Rejected -> this
    }

interface MessageApi {
    suspend fun openDirectDialog(peerId: String): MessageApiResult<OpenedDialogResponse>
    suspend fun dialogs(): MessageApiResult<List<WireDialog>>
    suspend fun messagesAfter(dialogId: String, after: String, limit: Int): MessageApiResult<List<WireMessage>>
    suspend fun messagesBefore(dialogId: String, before: String?, limit: Int): MessageApiResult<List<WireMessage>>
    suspend fun sendFallback(clientMsgId: String, dialogId: String, text: String): MessageApiResult<FallbackSendResponse>
}

class KtorMessageApi(
    private val http: HttpClient,
    private val config: AppConfig,
    private val session: SessionManager
) : MessageApi {
    private val base: String get() = "${config.apiBaseUrl}/api/v1/message"

    override suspend fun openDirectDialog(peerId: String): MessageApiResult<OpenedDialogResponse> =
        execute { token ->
            http.post("$base/dialogs") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(OpenDirectDialogRequest(peerId))
            }
        }

    override suspend fun dialogs(): MessageApiResult<List<WireDialog>> =
        get<DialogListResponse>("$base/dialogs") { }.mapValue { it.dialogs }

    override suspend fun messagesAfter(
        dialogId: String,
        after: String,
        limit: Int
    ): MessageApiResult<List<WireMessage>> =
        get<MessagePageResponse>("$base/dialogs/$dialogId/messages") {
            parameter("after", after)
            parameter("limit", limit)
        }.mapValue { it.messages }

    override suspend fun messagesBefore(
        dialogId: String,
        before: String?,
        limit: Int
    ): MessageApiResult<List<WireMessage>> =
        get<MessagePageResponse>("$base/dialogs/$dialogId/messages") {
            if (before != null) parameter("before", before)
            parameter("limit", limit)
        }.mapValue { it.messages }

    override suspend fun sendFallback(
        clientMsgId: String,
        dialogId: String,
        text: String
    ): MessageApiResult<FallbackSendResponse> =
        execute { token ->
            http.post("$base/messages") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(FallbackSendRequest(clientMsgId, dialogId, text))
            }
        }

    private suspend inline fun <reified T> get(
        url: String,
        crossinline configure: HttpRequestBuilder.() -> Unit
    ): MessageApiResult<T> =
        execute { token ->
            http.get(url) {
                bearerAuth(token)
                configure()
            }
        }

    private suspend inline fun <reified T> execute(
        send: (String) -> HttpResponse
    ): MessageApiResult<T> {
        val token = session.validAccessToken()
            ?: return MessageApiResult.Unavailable("not authenticated")
        var response = try {
            send(token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) { // allow: broad-catch Ktor engines throw platform-specific transport exceptions with no common KMP supertype; any of them means the backend is unreachable
            return MessageApiResult.Unavailable(e.message ?: e::class.simpleName ?: "network failure")
        }
        if (response.status == HttpStatusCode.Unauthorized) {
            val refreshed = session.forceRefresh()
                ?: return MessageApiResult.Unavailable("token refresh failed")
            response = try {
                send(refreshed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) { // allow: broad-catch same transport-failure normalization as the first attempt
                return MessageApiResult.Unavailable(e.message ?: e::class.simpleName ?: "network failure")
            }
        }
        return when {
            response.status.isSuccess() -> try {
                MessageApiResult.Success(response.body())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) { // allow: broad-catch a body that does not match the expected shape surfaces as a serialization or engine exception with no common KMP supertype; all of them mean the response was unusable, and a contract drift must degrade to a failed sync rather than kill the caller's coroutine
                MessageApiResult.Unavailable("unreadable response: ${e.message ?: e::class.simpleName}")
            }
            isTransientStatus(response.status) -> MessageApiResult.Unavailable("HTTP ${response.status.value}")
            else -> MessageApiResult.Rejected(response.status.value)
        }
    }

    private fun isTransientStatus(status: HttpStatusCode): Boolean =
        status.value >= 500 ||
            status == HttpStatusCode.Unauthorized ||
            status == HttpStatusCode.RequestTimeout ||
            status == HttpStatusCode.TooManyRequests
}
