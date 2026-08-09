package com.relay.repository

import com.relay.db.ContactStore
import com.relay.network.ContactResponse
import com.relay.network.PagedResponse
import com.relay.network.UserApi
import com.relay.network.UserApiResult
import com.relay.network.UserSearchResultResponse
import com.relay.network.UserSummaryResponse
import com.relay.testutil.createTestDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

private const val TEST_ISO = "2026-07-26T10:00:00Z"

private class StubUserApi : UserApi {
    var contactPages: (Int) -> UserApiResult<PagedResponse<ContactResponse>> = { page ->
        UserApiResult.Success(pageOf(emptyList(), page = page, hasNext = false))
    }
    var contactCalls = 0

    override suspend fun search(
        query: String,
        page: Int,
        size: Int
    ): UserApiResult<PagedResponse<UserSearchResultResponse>> =
        UserApiResult.Failure("not stubbed")

    override suspend fun contacts(page: Int, size: Int): UserApiResult<PagedResponse<ContactResponse>> {
        contactCalls++
        return contactPages(page)
    }

    override suspend fun addContact(userId: String): UserApiResult<ContactResponse> =
        UserApiResult.Failure("not stubbed")

    override suspend fun removeContact(userId: String): UserApiResult<Unit> =
        UserApiResult.Failure("not stubbed")
}

private fun contactResponse(id: String) = ContactResponse(
    user = UserSummaryResponse(
        id = id,
        email = "$id@relay.dev",
        firstName = "First$id",
        lastName = "Last$id",
        avatarUrl = null
    ),
    addedAt = TEST_ISO
)

private fun pageOf(items: List<ContactResponse>, page: Int, hasNext: Boolean) = PagedResponse(
    items = items,
    page = page,
    size = PEOPLE_PAGE_SIZE,
    totalElements = items.size.toLong(),
    totalPages = 1,
    hasNext = hasNext
)

class UserRepositoryTest {

    private fun repository(api: UserApi): Pair<UserRepositoryImpl, ContactStore> {
        val store = ContactStore(createTestDb(), Dispatchers.Unconfined)
        return UserRepositoryImpl(api, store) to store
    }

    @Test
    fun contactsArePagedUntilTheServerStops() = runTest {
        val api = StubUserApi()
        api.contactPages = { page ->
            UserApiResult.Success(
                pageOf(listOf(contactResponse("u$page")), page = page, hasNext = page < 2)
            )
        }
        val (repo, store) = repository(api)

        assertIs<UserResult.Success<Unit>>(repo.refreshContacts())

        assertEquals(3, api.contactCalls)
        assertEquals(listOf("u0", "u1", "u2"), store.observeContacts().first().map { it.user.id })
    }

    @Test
    fun aServerThatAlwaysReportsMoreDoesNotPageForever() = runTest {
        val api = StubUserApi()
        api.contactPages = { page ->
            UserApiResult.Success(
                pageOf(listOf(contactResponse("stuck")), page = page, hasNext = true)
            )
        }
        val (repo, _) = repository(api)

        assertIs<UserResult.Success<Unit>>(repo.refreshContacts())

        assertTrue(api.contactCalls in 1..50, "paged ${api.contactCalls} times, expected a cap")
    }

    @Test
    fun anEmptyPageEndsPagingEvenWhenTheServerClaimsMore() = runTest {
        val api = StubUserApi()
        api.contactPages = { page ->
            UserApiResult.Success(pageOf(emptyList(), page = page, hasNext = true))
        }
        val (repo, _) = repository(api)

        assertIs<UserResult.Success<Unit>>(repo.refreshContacts())

        assertEquals(1, api.contactCalls)
    }

    @Test
    fun aFailedPageLeavesTheCachedContactsAlone() = runTest {
        val api = StubUserApi()
        api.contactPages = { UserApiResult.Failure("Cannot reach the server") }
        val (repo, store) = repository(api)
        store.upsert(com.relay.model.Contact(com.relay.model.UserSummary("kept", "k@relay.dev", "K", "K", null), 0L))

        val result = repo.refreshContacts()

        assertIs<UserResult.Failure>(result)
        assertEquals(listOf("kept"), store.observeContacts().first().map { it.user.id })
    }
}
