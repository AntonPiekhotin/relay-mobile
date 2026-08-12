package com.relay.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeRight
import com.relay.model.MessageState
import com.relay.ui.components.BACK_BUTTON_TAG
import com.relay.ui.components.CONNECTION_STRIP_TAG
import com.relay.ui.components.ConnectionStrip
import com.relay.ui.components.SWIPE_BACK_TAG
import com.relay.ui.components.SwipeBackBox
import com.relay.ui.chat.CHAT_ERROR_TAG
import com.relay.ui.chat.ChatScreen
import com.relay.ui.dialogs.DIALOGS_PROFILE_TAG
import com.relay.ui.dialogs.DIALOGS_SEARCH_TAG
import com.relay.ui.dialogs.DialogListScreen
import com.relay.ui.people.PeopleScreen
import com.relay.ui.profile.PROFILE_LOGOUT_TAG
import com.relay.ui.profile.ProfileScreen
import com.relay.ui.state.ChatState
import com.relay.ui.state.ConnectionUi
import com.relay.ui.state.DialogListState
import com.relay.ui.state.DialogUi
import com.relay.ui.state.PeopleState
import com.relay.ui.state.PeopleTab
import com.relay.ui.state.PersonUi
import com.relay.ui.state.ProfileState
import com.relay.ui.state.ProfileUi
import com.relay.ui.theme.RelayTheme
import kotlin.test.Test
import kotlin.test.assertEquals

private fun dialogUi(id: String, title: String, preview: String) = DialogUi(
    id = id,
    title = title,
    preview = preview,
    timestamp = "12:00",
    unreadCount = 3,
    previewIsMine = false,
    previewStatus = MessageState.SENT
)

private fun personUi(id: String, name: String, isContact: Boolean) = PersonUi(
    id = id,
    name = name,
    isContact = isContact
)

@OptIn(ExperimentalTestApi::class)
class ScreenUiTest {

    @Test
    fun theConnectionStripIsHiddenUntilTheDropIsWorthReporting() = runComposeUiTest {
        val connection = mutableStateOf<ConnectionUi>(ConnectionUi.Live)
        setContent { RelayTheme { ConnectionStrip(connection = connection.value) } }
        onNodeWithTag(CONNECTION_STRIP_TAG).assertDoesNotExist()

        connection.value = ConnectionUi.Unknown
        waitForIdle()
        onNodeWithTag(CONNECTION_STRIP_TAG).assertDoesNotExist()

        connection.value = ConnectionUi.Reconnecting
        waitForIdle()
        onNodeWithTag(CONNECTION_STRIP_TAG).assertIsDisplayed()
    }

    @Test
    fun anEmptyDialogListExplainsWhyItIsEmpty() = runComposeUiTest {
        setContent {
            RelayTheme {
                DialogListScreen(
                    state = DialogListState(isLoaded = true),
                    onOpenDialog = {},
                    onOpenSearch = {},
                    onOpenProfile = {}
                )
            }
        }
        onNodeWithText("No conversations").assertIsDisplayed()
    }

    @Test
    fun theEmptyStateIsWithheldUntilTheDatabaseHasAnswered() = runComposeUiTest {
        setContent {
            RelayTheme {
                DialogListScreen(
                    state = DialogListState(isLoaded = false),
                    onOpenDialog = {},
                    onOpenSearch = {},
                    onOpenProfile = {}
                )
            }
        }
        onNodeWithText("No conversations").assertDoesNotExist()
    }

    @Test
    fun tappingADialogRowReportsItsId() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setContent {
            RelayTheme {
                DialogListScreen(
                    state = DialogListState(
                        dialogs = listOf(dialogUi("d1", "Ada", "see you then")),
                        isLoaded = true
                    ),
                    onOpenDialog = { opened += it },
                    onOpenSearch = {},
                    onOpenProfile = {}
                )
            }
        }
        onNodeWithText("Ada").assertIsDisplayed()
        onNodeWithText("see you then").assertIsDisplayed().performClick()
        waitForIdle()
        assertEquals(listOf("d1"), opened)
    }

    @Test
    fun theSearchTabAsksForAMinimumQueryBeforeSearching() = runComposeUiTest {
        setContent {
            RelayTheme {
                PeopleScreen(
                    state = PeopleState(tab = PeopleTab.SEARCH, query = "a"),
                    onTabChange = {},
                    onQueryChange = {},
                    onOpenChat = {},
                    onAddContact = {},
                    onRemoveContact = {},
                    onBack = {}
                )
            }
        }
        onNodeWithText("Find someone").assertIsDisplayed()
    }

    @Test
    fun contactsShowRemoveAndStrangersShowAdd() = runComposeUiTest {
        val added = mutableListOf<String>()
        val removed = mutableListOf<String>()
        setContent {
            RelayTheme {
                PeopleScreen(
                    state = PeopleState(
                        tab = PeopleTab.CONTACTS,
                        contacts = listOf(
                            personUi("a", "Ada Lovelace", isContact = true),
                            personUi("b", "Grace Hopper", isContact = false)
                        )
                    ),
                    onTabChange = {},
                    onQueryChange = {},
                    onOpenChat = {},
                    onAddContact = { added += it },
                    onRemoveContact = { removed += it },
                    onBack = {}
                )
            }
        }
        onNodeWithText("Remove").assertIsDisplayed().performClick()
        onNodeWithText("Add").assertIsDisplayed().performClick()
        waitForIdle()
        assertEquals(listOf("a"), removed)
        assertEquals(listOf("b"), added)
    }

    @Test
    fun tappingAFoundPersonOpensTheChatAndNoEmailIsListed() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setContent {
            RelayTheme {
                PeopleScreen(
                    state = PeopleState(
                        tab = PeopleTab.SEARCH,
                        query = "ada",
                        hasSearched = true,
                        results = listOf(personUi("a", "Ada Lovelace", isContact = false))
                    ),
                    onTabChange = {},
                    onQueryChange = {},
                    onOpenChat = { opened += it },
                    onAddContact = {},
                    onRemoveContact = {},
                    onBack = {}
                )
            }
        }
        onNodeWithText("Message").assertDoesNotExist()
        onNodeWithText("a@relay.dev").assertDoesNotExist()
        onNodeWithText("Ada Lovelace").assertIsDisplayed().performClick()
        waitForIdle()
        assertEquals(listOf("a"), opened)
    }

    @Test
    fun aSendFailureIsShownAboveTheComposerAndCanBeDismissed() = runComposeUiTest {
        val dismissed = mutableListOf<Unit>()
        setContent {
            RelayTheme {
                ChatScreen(
                    state = ChatState(
                        dialogId = "d1",
                        title = "Ada",
                        error = "Not signed in — your message was not sent"
                    ),
                    onDraftChange = {},
                    onSend = {},
                    onRetry = {},
                    onLoadOlder = {},
                    onBack = {},
                    onDismissError = { dismissed += Unit }
                )
            }
        }
        onNodeWithTag(CHAT_ERROR_TAG).assertIsDisplayed()
        onNodeWithText("Dismiss").assertIsDisplayed().performClick()
        waitForIdle()
        assertEquals(1, dismissed.size)
    }

    @Test
    fun aChatWithoutAnErrorShowsNoBanner() = runComposeUiTest {
        setContent {
            RelayTheme {
                ChatScreen(
                    state = ChatState(dialogId = "d1", title = "Ada"),
                    onDraftChange = {},
                    onSend = {},
                    onRetry = {},
                    onLoadOlder = {},
                    onBack = {},
                    onDismissError = {}
                )
            }
        }
        onNodeWithTag(CHAT_ERROR_TAG).assertDoesNotExist()
    }

    @Test
    fun theDialogListOffersSearchAndProfileInsteadOfALogoutButton() = runComposeUiTest {
        val searched = mutableListOf<Unit>()
        val profiled = mutableListOf<Unit>()
        setContent {
            RelayTheme {
                DialogListScreen(
                    state = DialogListState(isLoaded = true),
                    onOpenDialog = {},
                    onOpenSearch = { searched += Unit },
                    onOpenProfile = { profiled += Unit }
                )
            }
        }
        onNodeWithText("Log out").assertDoesNotExist()
        onNodeWithTag(DIALOGS_SEARCH_TAG).assertIsDisplayed().performClick()
        onNodeWithTag(DIALOGS_PROFILE_TAG).assertIsDisplayed().performClick()
        waitForIdle()
        assertEquals(1, searched.size)
        assertEquals(1, profiled.size)
    }

    @Test
    fun theProfileScreenShowsTheAccountAndOffersLogout() = runComposeUiTest {
        val loggedOut = mutableListOf<Unit>()
        setContent {
            RelayTheme {
                ProfileScreen(
                    state = ProfileState(
                        profile = ProfileUi(
                            name = "Ada Lovelace",
                            email = "ada@relay.dev",
                            memberSince = "26 Jul 2026"
                        )
                    ),
                    onBack = {},
                    onRetry = {},
                    onLogout = { loggedOut += Unit }
                )
            }
        }
        onNodeWithText("Ada Lovelace").assertIsDisplayed()
        onNodeWithText("ada@relay.dev").assertIsDisplayed()
        onNodeWithText("26 Jul 2026").assertIsDisplayed()
        onNodeWithTag(PROFILE_LOGOUT_TAG).assertIsDisplayed().performClick()
        waitForIdle()
        assertEquals(1, loggedOut.size)
    }

    @Test
    fun anUnreachableProfileStillOffersLogoutAndARetry() = runComposeUiTest {
        val retried = mutableListOf<Unit>()
        setContent {
            RelayTheme {
                ProfileScreen(
                    state = ProfileState(error = "Cannot reach the server"),
                    onBack = {},
                    onRetry = { retried += Unit },
                    onLogout = {}
                )
            }
        }
        onNodeWithText("Cannot reach the server").assertIsDisplayed()
        onNodeWithTag(PROFILE_LOGOUT_TAG).assertIsDisplayed()
        onNodeWithText("Try again").assertIsDisplayed().performClick()
        waitForIdle()
        assertEquals(1, retried.size)
    }

    @Test
    fun theBackAffordanceIsAnIconAndReportsTheTap() = runComposeUiTest {
        val back = mutableListOf<Unit>()
        setContent {
            RelayTheme {
                ProfileScreen(
                    state = ProfileState(error = "offline"),
                    onBack = { back += Unit },
                    onRetry = {},
                    onLogout = {}
                )
            }
        }
        onNodeWithText("Back").assertDoesNotExist()
        onNodeWithTag(BACK_BUTTON_TAG).assertIsDisplayed().performClick()
        waitForIdle()
        assertEquals(1, back.size)
    }

    @Test
    fun swipingInFromTheLeftEdgeGoesBack() = runComposeUiTest {
        val back = mutableListOf<Unit>()
        setContent {
            RelayTheme {
                SwipeBackBox(onBack = { back += Unit }) { Text("a screen") }
            }
        }
        onNodeWithTag(SWIPE_BACK_TAG).performTouchInput { swipeRight() }
        waitUntil { back.isNotEmpty() }
        assertEquals(1, back.size)
    }

    @Test
    fun aSwipeThatDoesNotStartAtTheEdgeIsIgnored() = runComposeUiTest {
        val back = mutableListOf<Unit>()
        setContent {
            RelayTheme {
                SwipeBackBox(onBack = { back += Unit }) { Text("a screen") }
            }
        }
        onNodeWithTag(SWIPE_BACK_TAG).performTouchInput {
            swipeRight(startX = centerX, endX = right)
        }
        waitForIdle()
        assertEquals(emptyList(), back)
    }

    @Test
    fun anEmptyContactListPointsAtSearch() = runComposeUiTest {
        setContent {
            RelayTheme {
                PeopleScreen(
                    state = PeopleState(tab = PeopleTab.CONTACTS),
                    onTabChange = {},
                    onQueryChange = {},
                    onOpenChat = {},
                    onAddContact = {},
                    onRemoveContact = {},
                    onBack = {}
                )
            }
        }
        onNodeWithText("No contacts yet").assertIsDisplayed()
    }
}
