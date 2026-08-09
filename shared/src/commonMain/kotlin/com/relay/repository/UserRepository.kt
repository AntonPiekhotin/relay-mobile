package com.relay.repository

import com.relay.db.ContactStore
import com.relay.model.Contact
import com.relay.model.SearchPage
import com.relay.model.UserSearchResult
import com.relay.model.UserSummary
import com.relay.network.ContactResponse
import com.relay.network.PagedResponse
import com.relay.network.UserApi
import com.relay.network.UserApiResult
import com.relay.network.UserSearchResultResponse
import com.relay.network.UserSummaryResponse
import com.relay.protocol.isoToEpochMillisOrNull
import kotlinx.coroutines.flow.Flow

const val PEOPLE_PAGE_SIZE = 20

private const val MAX_CONTACT_PAGES = 50

sealed interface UserResult<out T> {
    data class Success<T>(val value: T) : UserResult<T>
    data class Failure(val message: String) : UserResult<Nothing>
}

interface UserRepository {
    fun observeContacts(): Flow<List<Contact>>
    suspend fun refreshContacts(): UserResult<Unit>
    suspend fun search(query: String, page: Int): UserResult<SearchPage>
    suspend fun addContact(user: UserSummary): UserResult<Unit>
    suspend fun removeContact(userId: String): UserResult<Unit>
}

class UserRepositoryImpl(
    private val api: UserApi,
    private val contacts: ContactStore
) : UserRepository {

    override fun observeContacts(): Flow<List<Contact>> = contacts.observeContacts()

    override suspend fun refreshContacts(): UserResult<Unit> {
        val collected = mutableListOf<Contact>()
        var page = 0
        while (page < MAX_CONTACT_PAGES) {
            val response = when (val result = api.contacts(page, PEOPLE_PAGE_SIZE)) {
                is UserApiResult.Success -> result.value
                is UserApiResult.Failure -> return UserResult.Failure(result.reason)
            }
            collected += response.items.map { it.toDomain() }
            if (!response.hasNext || response.items.isEmpty()) break
            page++
        }
        contacts.replaceAll(collected)
        return UserResult.Success(Unit)
    }

    override suspend fun search(query: String, page: Int): UserResult<SearchPage> =
        when (val result = api.search(query, page, PEOPLE_PAGE_SIZE)) {
            is UserApiResult.Success -> UserResult.Success(result.value.toSearchPage())
            is UserApiResult.Failure -> UserResult.Failure(result.reason)
        }

    override suspend fun addContact(user: UserSummary): UserResult<Unit> =
        when (val result = api.addContact(user.id)) {
            is UserApiResult.Success -> {
                contacts.upsert(result.value.toDomain())
                UserResult.Success(Unit)
            }
            is UserApiResult.Failure -> UserResult.Failure(result.reason)
        }

    override suspend fun removeContact(userId: String): UserResult<Unit> =
        when (val result = api.removeContact(userId)) {
            is UserApiResult.Success -> {
                contacts.remove(userId)
                UserResult.Success(Unit)
            }
            is UserApiResult.Failure -> UserResult.Failure(result.reason)
        }
}

private fun UserSummaryResponse.toDomain(): UserSummary =
    UserSummary(id = id, email = email, firstName = firstName, lastName = lastName, avatarUrl = avatarUrl)

private fun ContactResponse.toDomain(): Contact =
    Contact(user = user.toDomain(), addedAtMillis = isoToEpochMillisOrNull(addedAt))

private fun PagedResponse<UserSearchResultResponse>.toSearchPage(): SearchPage =
    SearchPage(
        results = items.map { UserSearchResult(user = it.user.toDomain(), isContact = it.contact) },
        page = page,
        hasNext = hasNext
    )
