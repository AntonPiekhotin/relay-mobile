package com.relay.ui.people

import app.cash.turbine.test
import com.relay.model.SearchPage
import com.relay.repository.OpenDialogResult
import com.relay.repository.UserResult
import com.relay.testutil.FakeMessageRepository
import com.relay.testutil.FakeUserRepository
import com.relay.testutil.contact
import com.relay.testutil.searchResult
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

private val TEST_DEBOUNCE = 100.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class PeopleViewModelTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun contactsComeFromTheObservedStore() = runTest {
        val users = FakeUserRepository()
        val messages = FakeMessageRepository()
        users.setContacts(listOf(contact("a"), contact("b")))
        val viewModel = PeopleViewModel(users, messages, TEST_DEBOUNCE)
        advanceUntilIdle()

        assertEquals(listOf("a", "b"), viewModel.state.value.contacts.map { it.id })
        assertTrue(viewModel.state.value.contacts.all { it.isContact })
        assertEquals(1, users.refreshCalls)
    }

    @Test
    fun aQueryShorterThanTheMinimumNeverReachesTheServer() = runTest {
        val users = FakeUserRepository()
        val messages = FakeMessageRepository()
        val viewModel = PeopleViewModel(users, messages, TEST_DEBOUNCE)
        advanceUntilIdle()

        viewModel.onQueryChange("a")
        advanceUntilIdle()

        assertEquals(0, users.searchCalls)
        assertTrue(viewModel.state.value.results.isEmpty())
        assertFalse(viewModel.state.value.hasSearched)
    }

    @Test
    fun typingIsDebouncedIntoASingleSearch() = runTest {
        val users = FakeUserRepository()
        val messages = FakeMessageRepository()
        users.searchHandler = { query, _ ->
            UserResult.Success(SearchPage(listOf(searchResult("z")), page = 0, hasNext = false))
        }
        val viewModel = PeopleViewModel(users, messages, TEST_DEBOUNCE)
        advanceUntilIdle()

        viewModel.onQueryChange("an")
        advanceTimeBy(50)
        viewModel.onQueryChange("ann")
        advanceTimeBy(50)
        viewModel.onQueryChange("anna")
        advanceUntilIdle()

        assertEquals(1, users.searchCalls)
        assertEquals(listOf("anna"), users.searchQueries)
        assertEquals(listOf("z"), viewModel.state.value.results.map { it.id })
        assertTrue(viewModel.state.value.hasSearched)
    }

    @Test
    fun searchResultsAreMarkedAsContactsWhenTheyAreAlreadyStored() = runTest {
        val users = FakeUserRepository()
        val messages = FakeMessageRepository()
        users.setContacts(listOf(contact("z")))
        users.searchHandler = { _, _ ->
            UserResult.Success(SearchPage(listOf(searchResult("z"), searchResult("y")), 0, false))
        }
        val viewModel = PeopleViewModel(users, messages, TEST_DEBOUNCE)
        advanceUntilIdle()

        viewModel.onQueryChange("zz")
        advanceUntilIdle()

        val results = viewModel.state.value.results.associate { it.id to it.isContact }
        assertEquals(mapOf("z" to true, "y" to false), results)
    }

    @Test
    fun addingAContactMarksItPendingThenSurfacesItInContacts() = runTest {
        val users = FakeUserRepository()
        val messages = FakeMessageRepository()
        users.searchHandler = { _, _ ->
            UserResult.Success(SearchPage(listOf(searchResult("y")), 0, false))
        }
        val viewModel = PeopleViewModel(users, messages, TEST_DEBOUNCE)
        advanceUntilIdle()
        viewModel.onQueryChange("yy")
        advanceUntilIdle()

        viewModel.addContact("y")
        assertTrue("y" in viewModel.state.value.pendingIds)

        advanceUntilIdle()
        assertFalse("y" in viewModel.state.value.pendingIds)
        assertEquals(listOf("y"), viewModel.state.value.contacts.map { it.id })
        assertTrue(viewModel.state.value.results.single().isContact)
    }

    @Test
    fun removingFromTheSearchTabFlipsTheRowBackToAdd() = runTest {
        val users = FakeUserRepository()
        val messages = FakeMessageRepository()
        users.setContacts(listOf(contact("z")))
        users.searchHandler = { _, _ ->
            UserResult.Success(SearchPage(listOf(searchResult("z", isContact = true)), 0, false))
        }
        val viewModel = PeopleViewModel(users, messages, TEST_DEBOUNCE)
        advanceUntilIdle()
        viewModel.onQueryChange("zz")
        advanceUntilIdle()
        assertTrue(viewModel.state.value.results.single().isContact)

        viewModel.removeContact("z")
        advanceUntilIdle()

        assertFalse(viewModel.state.value.results.single().isContact)
        assertTrue(viewModel.state.value.contacts.isEmpty())
    }

    @Test
    fun removingAContactDropsItFromTheList() = runTest {
        val users = FakeUserRepository()
        val messages = FakeMessageRepository()
        users.setContacts(listOf(contact("a")))
        val viewModel = PeopleViewModel(users, messages, TEST_DEBOUNCE)
        advanceUntilIdle()

        viewModel.removeContact("a")
        advanceUntilIdle()

        assertTrue(viewModel.state.value.contacts.isEmpty())
    }

    @Test
    fun aFailedSearchIsSurfacedAsAnError() = runTest {
        val users = FakeUserRepository()
        val messages = FakeMessageRepository()
        users.searchHandler = { _, _ -> UserResult.Failure("Cannot reach the server") }
        val viewModel = PeopleViewModel(users, messages, TEST_DEBOUNCE)
        advanceUntilIdle()

        viewModel.onQueryChange("anna")
        advanceUntilIdle()

        assertEquals("Cannot reach the server", viewModel.state.value.error)
        assertFalse(viewModel.state.value.isSearching)
    }

    @Test
    fun aFailedAddIsSurfacedAndLeavesContactsUnchanged() = runTest {
        val users = FakeUserRepository()
        val messages = FakeMessageRepository()
        users.searchHandler = { _, _ ->
            UserResult.Success(SearchPage(listOf(searchResult("y")), 0, false))
        }
        users.addHandler = { UserResult.Failure("Not permitted") }
        val viewModel = PeopleViewModel(users, messages, TEST_DEBOUNCE)
        advanceUntilIdle()
        viewModel.onQueryChange("yy")
        advanceUntilIdle()

        viewModel.addContact("y")
        advanceUntilIdle()

        assertEquals("Not permitted", viewModel.state.value.error)
        assertTrue(viewModel.state.value.contacts.isEmpty())
    }

    @Test
    fun openingAChatEmitsTheDialogIdToNavigateTo() = runTest {
        val users = FakeUserRepository()
        val messages = FakeMessageRepository()
        messages.openHandler = { OpenDialogResult.Opened("dialog-7") }
        users.setContacts(listOf(contact("peer-1")))
        val viewModel = PeopleViewModel(users, messages, TEST_DEBOUNCE)
        advanceUntilIdle()

        viewModel.openedDialog.test {
            viewModel.openChat("peer-1")
            advanceUntilIdle()
            assertEquals("dialog-7", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("peer-1"), messages.openedPeers)
        assertFalse("peer-1" in viewModel.state.value.pendingIds)
    }

    @Test
    fun aFailedOpenIsSurfacedAndNavigatesNowhere() = runTest {
        val users = FakeUserRepository()
        val messages = FakeMessageRepository()
        messages.openHandler = { OpenDialogResult.Failed("Cannot reach the server") }
        users.setContacts(listOf(contact("peer-1")))
        val viewModel = PeopleViewModel(users, messages, TEST_DEBOUNCE)
        advanceUntilIdle()

        viewModel.openedDialog.test {
            viewModel.openChat("peer-1")
            advanceUntilIdle()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("Cannot reach the server", viewModel.state.value.error)
        assertFalse("peer-1" in viewModel.state.value.pendingIds)
    }

    @Test
    fun tappingMessageTwiceOpensOnlyOneDialog() = runTest {
        val users = FakeUserRepository()
        val messages = FakeMessageRepository()
        users.setContacts(listOf(contact("peer-1")))
        val viewModel = PeopleViewModel(users, messages, TEST_DEBOUNCE)
        advanceUntilIdle()

        viewModel.openChat("peer-1")
        viewModel.openChat("peer-1")
        advanceUntilIdle()

        assertEquals(listOf("peer-1"), messages.openedPeers)
    }

}
