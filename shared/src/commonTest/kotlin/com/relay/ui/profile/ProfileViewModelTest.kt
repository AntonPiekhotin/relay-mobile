package com.relay.ui.profile

import com.relay.model.UserProfile
import com.relay.repository.UserResult
import com.relay.testutil.FakeUserRepository
import com.relay.testutil.userSummary
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

private const val CREATED_AT = 1_785_000_000_000L

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun theProfileIsLoadedOnceTheScreenOpens() = runTest {
        val users = FakeUserRepository()
        users.profileHandler = {
            UserResult.Success(
                UserProfile(userSummary("me", "Ada", "Lovelace", "ada@relay.dev"), CREATED_AT)
            )
        }
        val viewModel = ProfileViewModel(users)
        advanceUntilIdle()

        val profile = viewModel.state.value.profile
        assertEquals("Ada Lovelace", profile?.name)
        assertEquals("ada@relay.dev", profile?.email)
        assertEquals(false, viewModel.state.value.isLoading)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun aProfileWithoutACreationDateOmitsMemberSince() = runTest {
        val users = FakeUserRepository()
        users.profileHandler = {
            UserResult.Success(UserProfile(userSummary("me"), createdAtMillis = null))
        }
        val viewModel = ProfileViewModel(users)
        advanceUntilIdle()

        assertNull(viewModel.state.value.profile?.memberSince)
    }

    @Test
    fun anUnreachableServerIsSurfacedAndRetryClearsTheError() = runTest {
        val users = FakeUserRepository()
        users.profileHandler = { UserResult.Failure("Cannot reach the server") }
        val viewModel = ProfileViewModel(users)
        advanceUntilIdle()

        assertEquals("Cannot reach the server", viewModel.state.value.error)
        assertNull(viewModel.state.value.profile)

        users.profileHandler = {
            UserResult.Success(UserProfile(userSummary("me", "Ada", "Lovelace"), null))
        }
        viewModel.refresh()
        advanceUntilIdle()

        assertNull(viewModel.state.value.error)
        assertEquals("Ada Lovelace", viewModel.state.value.profile?.name)
    }
}
