package com.relay.ui.groups

import app.cash.turbine.test
import com.relay.model.SearchPage
import com.relay.repository.OpenDialogResult
import com.relay.repository.UserResult
import com.relay.testutil.FakeMessageRepository
import com.relay.testutil.FakeUserRepository
import com.relay.testutil.contact
import com.relay.testutil.searchResult
import com.relay.ui.state.PersonUi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

private val TEST_DEBOUNCE = 100.milliseconds

private fun person(id: String): PersonUi = PersonUi(id = id, name = "Name $id", isContact = true)

@OptIn(ExperimentalCoroutinesApi::class)
class GroupCreateViewModelTest {

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
        val viewModel = GroupCreateViewModel(users, messages, TEST_DEBOUNCE)
        advanceUntilIdle()

        assertEquals(listOf("a", "b"), viewModel.state.value.contacts.map { it.id })
    }

    @Test
    fun createNeedsAtLeastOneMemberButNoTitle() = runTest {
        val users = FakeUserRepository()
        val messages = FakeMessageRepository()
        val viewModel = GroupCreateViewModel(users, messages, TEST_DEBOUNCE)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.canCreate)
        viewModel.toggle(person("a"))
        assertTrue(viewModel.state.value.canCreate)
        viewModel.toggle(person("a"))
        assertFalse(viewModel.state.value.canCreate)
    }

    @Test
    fun aBlankTitleIsDefaultedFromTheMemberNames() = runTest {
        val users = FakeUserRepository()
        val messages = FakeMessageRepository()
        val viewModel = GroupCreateViewModel(users, messages, TEST_DEBOUNCE)
        advanceUntilIdle()

        viewModel.toggle(person("a"))
        viewModel.toggle(person("b"))
        viewModel.create()
        advanceUntilIdle()

        assertEquals("Name a, Name b", messages.createdGroups.single().second)
    }

    @Test
    fun aDefaultedTitleSummarisesALongMemberList() {
        val members = listOf(person("a"), person("b"), person("c"), person("d"), person("e"))
        assertEquals("Name a, Name b, Name c +2", defaultGroupTitle(members))
    }

    @Test
    fun searchResultsFeedThePicker() = runTest {
        val users = FakeUserRepository()
        val messages = FakeMessageRepository()
        users.searchHandler = { _, _ ->
            UserResult.Success(SearchPage(listOf(searchResult("z")), page = 0, hasNext = false))
        }
        val viewModel = GroupCreateViewModel(users, messages, TEST_DEBOUNCE)
        advanceUntilIdle()

        viewModel.onQueryChange("anna")
        advanceUntilIdle()

        assertEquals(listOf("z"), viewModel.state.value.results.map { it.id })
    }

    @Test
    fun creatingAGroupEmitsTheDialogIdAndResetsTheForm() = runTest {
        val users = FakeUserRepository()
        val messages = FakeMessageRepository()
        val viewModel = GroupCreateViewModel(users, messages, TEST_DEBOUNCE)
        advanceUntilIdle()

        viewModel.onTitleChange("  team  ")
        viewModel.toggle(person("a"))
        viewModel.toggle(person("b"))

        viewModel.created.test {
            viewModel.create()
            advanceUntilIdle()
            val (dialogId, title, memberIds) = messages.createdGroups.single()
            assertEquals(dialogId, awaitItem())
            assertEquals("team", title)
            assertEquals(listOf("a", "b"), memberIds)
        }
        assertEquals("", viewModel.state.value.title)
        assertTrue(viewModel.state.value.selected.isEmpty())
        assertFalse(viewModel.state.value.isCreating)
    }

    @Test
    fun aRetryAfterAFailureReusesTheSameDialogId() = runTest {
        val users = FakeUserRepository()
        val messages = FakeMessageRepository()
        messages.createGroupHandler = { _, _, _ -> OpenDialogResult.Failed("offline") }
        val viewModel = GroupCreateViewModel(users, messages, TEST_DEBOUNCE)
        advanceUntilIdle()

        viewModel.onTitleChange("team")
        viewModel.toggle(person("a"))
        viewModel.create()
        advanceUntilIdle()
        assertNotNull(viewModel.state.value.error)
        viewModel.create()
        advanceUntilIdle()

        assertEquals(2, messages.createdGroups.size)
        assertEquals(messages.createdGroups[0].first, messages.createdGroups[1].first)
    }

    @Test
    fun aFreshFormAfterSuccessMintsANewDialogId() = runTest {
        val users = FakeUserRepository()
        val messages = FakeMessageRepository()
        val viewModel = GroupCreateViewModel(users, messages, TEST_DEBOUNCE)
        advanceUntilIdle()

        viewModel.onTitleChange("first")
        viewModel.toggle(person("a"))
        viewModel.create()
        advanceUntilIdle()

        viewModel.onTitleChange("second")
        viewModel.toggle(person("b"))
        viewModel.create()
        advanceUntilIdle()

        assertEquals(2, messages.createdGroups.size)
        assertNotEquals(messages.createdGroups[0].first, messages.createdGroups[1].first)
    }
}
