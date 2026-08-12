package com.relay.network

import com.relay.auth.SessionManager
import com.relay.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
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

const val MIN_SEARCH_LENGTH = 2

private const val TRANSPORT_FAILURE = "Cannot reach the server"

@Serializable
data class UserSummaryResponse(
    val id: String,
    val email: String,
    val firstName: String,
    val lastName: String,
    val avatarUrl: String? = null
)

@Serializable
data class UserProfileResponse(
    val id: String,
    val email: String,
    val firstName: String,
    val lastName: String,
    val avatarUrl: String? = null,
    val createdAt: String? = null
)

@Serializable
data class UserSearchResultResponse(
    val user: UserSummaryResponse,
    val contact: Boolean
)

@Serializable
data class ContactResponse(
    val user: UserSummaryResponse,
    val addedAt: String
)

@Serializable
data class PagedResponse<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
    val hasNext: Boolean
)

@Serializable
data class AddContactRequest(val userId: String)

@Serializable
data class ApiErrorResponse(
    val statusCode: Int = 0,
    val errorMessage: List<String> = emptyList()
)

sealed interface UserApiResult<out T> {
    data class Success<T>(val value: T) : UserApiResult<T>
    data class Failure(val reason: String) : UserApiResult<Nothing>
}

interface UserApi {
    suspend fun me(): UserApiResult<UserProfileResponse>
    suspend fun userById(userId: String): UserApiResult<UserSummaryResponse>
    suspend fun search(query: String, page: Int, size: Int): UserApiResult<PagedResponse<UserSearchResultResponse>>
    suspend fun contacts(page: Int, size: Int): UserApiResult<PagedResponse<ContactResponse>>
    suspend fun addContact(userId: String): UserApiResult<ContactResponse>
    suspend fun removeContact(userId: String): UserApiResult<Unit>
}

class KtorUserApi(
    private val http: HttpClient,
    private val config: AppConfig,
    private val session: SessionManager
) : UserApi {
    private val base: String get() = "${config.apiBaseUrl}/api/v1/user"

    override suspend fun me(): UserApiResult<UserProfileResponse> =
        fetch { token -> http.get("$base/me") { bearerAuth(token) } }

    override suspend fun userById(userId: String): UserApiResult<UserSummaryResponse> =
        fetch { token -> http.get("$base/$userId") { bearerAuth(token) } }

    override suspend fun search(
        query: String,
        page: Int,
        size: Int
    ): UserApiResult<PagedResponse<UserSearchResultResponse>> =
        fetch { token ->
            http.get("$base/search") {
                bearerAuth(token)
                parameter("query", query)
                parameter("page", page)
                parameter("size", size)
            }
        }

    override suspend fun contacts(page: Int, size: Int): UserApiResult<PagedResponse<ContactResponse>> =
        fetch { token ->
            http.get("$base/me/contacts") {
                bearerAuth(token)
                parameter("page", page)
                parameter("size", size)
            }
        }

    override suspend fun addContact(userId: String): UserApiResult<ContactResponse> =
        fetch { token ->
            http.post("$base/me/contacts") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(AddContactRequest(userId))
            }
        }

    override suspend fun removeContact(userId: String): UserApiResult<Unit> =
        when (val result = call { token -> http.delete("$base/me/contacts/$userId") { bearerAuth(token) } }) {
            is UserApiResult.Success -> UserApiResult.Success(Unit)
            is UserApiResult.Failure -> result
        }

    private suspend inline fun <reified T> fetch(
        crossinline send: suspend (String) -> HttpResponse
    ): UserApiResult<T> =
        when (val result = call { token -> send(token) }) {
            is UserApiResult.Failure -> result
            is UserApiResult.Success -> try {
                UserApiResult.Success(result.value.body())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) { // allow: broad-catch a malformed or unexpected body surfaces as a serialization or engine exception with no common KMP supertype; all of them mean the response was unusable
                UserApiResult.Failure("Unreadable response from server")
            }
        }

    private suspend fun call(send: suspend (String) -> HttpResponse): UserApiResult<HttpResponse> {
        val token = session.validAccessToken() ?: return UserApiResult.Failure("Not signed in")
        var response = attempt(send, token) ?: return UserApiResult.Failure(TRANSPORT_FAILURE)
        if (response.status == HttpStatusCode.Unauthorized) {
            val refreshed = session.forceRefresh() ?: return UserApiResult.Failure("Session expired")
            response = attempt(send, refreshed) ?: return UserApiResult.Failure(TRANSPORT_FAILURE)
        }
        return if (response.status.isSuccess()) {
            UserApiResult.Success(response)
        } else {
            UserApiResult.Failure(reportedMessage(response) ?: describe(response.status))
        }
    }

    private suspend fun reportedMessage(response: HttpResponse): String? =
        try {
            response.body<ApiErrorResponse>().errorMessage.firstOrNull()?.takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) { // allow: broad-catch an error body that is absent, empty, or not the documented envelope decodes into engine- and format-specific exceptions with no common KMP supertype; all of them just mean "no server-supplied message", so fall back to the status
            null
        }

    private suspend fun attempt(send: suspend (String) -> HttpResponse, token: String): HttpResponse? =
        try {
            send(token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) { // allow: broad-catch Ktor engines throw platform-specific transport exceptions with no common KMP supertype; any of them means the backend is unreachable
            null
        }

    private fun describe(status: HttpStatusCode): String = when (status) {
        HttpStatusCode.BadRequest -> "The server rejected that request"
        HttpStatusCode.NotFound -> "Not found"
        HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> "Not permitted"
        else -> "Server error (HTTP ${status.value})"
    }
}
