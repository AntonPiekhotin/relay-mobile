package com.relay.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeRight
import com.relay.ui.components.BACK_BUTTON_TAG
import com.relay.ui.components.CONNECTION_STRIP_TAG
import com.relay.ui.components.ConnectionStrip
import com.relay.ui.components.SWIPE_BACK_TAG
import com.relay.ui.components.SwipeBackBox
import com.relay.ui.calls.CALLS_RETRY_TAG
import com.relay.ui.calls.CallsScreen
import com.relay.ui.chat.CHAT_ERROR_TAG
import com.relay.ui.chat.CHAT_SUBTITLE_TAG
import com.relay.ui.chat.ChatScreen
import com.relay.ui.dialogs.DialogListScreen
import com.relay.ui.home.HomeBottomBar
import com.relay.ui.home.HomeTab
import com.relay.ui.home.homeTabTag
import com.relay.ui.people.ContactsScreen
import com.relay.ui.people.SearchScreen
import com.relay.ui.profile.PROFILE_LOGOUT_TAG
import com.relay.ui.profile.ProfileScreen
import com.relay.ui.state.MessageStatusUi
import com.relay.ui.state.CallLogUi
import com.relay.ui.state.CallsState
import com.relay.ui.state.ChatState
import com.relay.ui.state.ConnectionUi
import com.relay.ui.state.DialogListState
import com.relay.ui.state.DialogUi
import com.relay.ui.state.PeopleState
import com.relay.ui.state.PersonUi
import com.relay.ui.state.ProfileState
import com.relay.ui.state.ProfileUi
import com.relay.ui.call.CALL_ACCEPT_TAG
import com.relay.ui.call.CALL_DECLINE_TAG
import com.relay.ui.call.CALL_HANGUP_TAG
import com.relay.ui.call.CallScreen
import com.relay.ui.state.CallActionsUi
import com.relay.ui.state.CallUiState
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
    previewStatus = MessageStatusUi.SENT
)

private fun personUi(id: String, name: String, isContact: Boolean) = PersonUi(
    id = id,
    name = name,
    isContact = isContact
)

private fun callLogUi(id: String, peerName: String, missed: Boolean = false) = CallLogUi(
    id = id,
    peerId = "peer-$id",
    dialogId = "dialog-$id",
    peerName = peerName,
    isMissed = missed,
    subtitle = if (missed) "Missed" else "Outgoing · 03:24",
    timestamp = "12:00"
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
                    onOpenDialog = {}
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
                    onOpenDialog = {}
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
                    onOpenDialog = { opened += it }
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
                SearchScreen(
                    state = PeopleState(query = "a"),
                    onQueryChange = {},
                    onOpenChat = {},
                    onAddContact = {},
                    onRemoveContact = {}
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
                ContactsScreen(
                    state = PeopleState(
                        contacts = listOf(
                            personUi("a", "Ada Lovelace", isContact = true),
                            personUi("b", "Grace Hopper", isContact = false)
                        )
                    ),
                    onOpenChat = {},
                    onAddContact = { added += it },
                    onRemoveContact = { removed += it }
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
                SearchScreen(
                    state = PeopleState(
                        query = "ada",
                        hasSearched = true,
                        results = listOf(personUi("a", "Ada Lovelace", isContact = false))
                    ),
                    onQueryChange = {},
                    onOpenChat = { opened += it },
                    onAddContact = {},
                    onRemoveContact = {}
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
                    onDismissError = { dismissed += Unit },
                    onCall = {}
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
                    onDismissError = {},
                    onCall = {}
                )
            }
        }
        onNodeWithTag(CHAT_ERROR_TAG).assertDoesNotExist()
    }

    @Test
    fun theChatHeaderShowsThePresenceSubtitleOnlyWhenKnown() = runComposeUiTest {
        setContent {
            RelayTheme {
                ChatScreen(
                    state = ChatState(dialogId = "d1", title = "Ada", subtitle = "online"),
                    onDraftChange = {},
                    onSend = {},
                    onRetry = {},
                    onLoadOlder = {},
                    onBack = {},
                    onDismissError = {},
                    onCall = {}
                )
            }
        }
        onNodeWithTag(CHAT_SUBTITLE_TAG).assertIsDisplayed()
        onNodeWithText("online").assertIsDisplayed()
    }

    @Test
    fun aChatWithoutPresenceShowsNoSubtitle() = runComposeUiTest {
        setContent {
            RelayTheme {
                ChatScreen(
                    state = ChatState(dialogId = "d1", title = "Ada"),
                    onDraftChange = {},
                    onSend = {},
                    onRetry = {},
                    onLoadOlder = {},
                    onBack = {},
                    onDismissError = {},
                    onCall = {}
                )
            }
        }
        onNodeWithTag(CHAT_SUBTITLE_TAG).assertDoesNotExist()
    }

    @Test
    fun theBottomDockListsEveryPageAndReportsTaps() = runComposeUiTest {
        val selected = mutableListOf<HomeTab>()
        setContent {
            RelayTheme {
                HomeBottomBar(
                    selected = HomeTab.CHATS,
                    chatsBadge = 0,
                    onSelect = { selected += it }
                )
            }
        }
        HomeTab.entries.forEach { tab ->
            onNodeWithTag(homeTabTag(tab)).assertIsDisplayed()
        }
        onNodeWithTag(homeTabTag(HomeTab.CONTACTS)).performClick()
        onNodeWithTag(homeTabTag(HomeTab.SETTINGS)).performClick()
        waitForIdle()
        assertEquals(listOf(HomeTab.CONTACTS, HomeTab.SETTINGS), selected)
    }

    @Test
    fun unreadMessagesBadgeTheChatsTab() = runComposeUiTest {
        setContent {
            RelayTheme {
                HomeBottomBar(selected = HomeTab.CHATS, chatsBadge = 7, onSelect = {})
            }
        }
        onNodeWithText("7", useUnmergedTree = true).assertIsDisplayed()
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
                ChatScreen(
                    state = ChatState(dialogId = "d1", title = "Ada"),
                    onDraftChange = {},
                    onSend = {},
                    onRetry = {},
                    onLoadOlder = {},
                    onBack = { back += Unit },
                    onDismissError = {},
                    onCall = {}
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
                ContactsScreen(
                    state = PeopleState(),
                    onOpenChat = {},
                    onAddContact = {},
                    onRemoveContact = {}
                )
            }
        }
        onNodeWithText("No contacts yet").assertIsDisplayed()
    }

    @Test
    fun aMissedCallIsMarkedAndTappingARowOpensItsChat() = runComposeUiTest {
        val opened = mutableListOf<String>()
        val calledBack = mutableListOf<String>()
        setContent {
            RelayTheme {
                CallsScreen(
                    state = CallsState(
                        calls = listOf(
                            callLogUi("c1", "Ada Lovelace", missed = true),
                            callLogUi("c2", "Grace Hopper")
                        ),
                        isLoaded = true
                    ),
                    onOpenDialog = { opened += it },
                    onCallBack = { calledBack += it.id },
                    onRetry = {},
                    onNewGroupCall = {}
                )
            }
        }
        onNodeWithText("Missed").assertIsDisplayed()
        onNodeWithText("Ada Lovelace").assertIsDisplayed().performClick()
        onNodeWithContentDescription("Call Grace Hopper").assertIsDisplayed().performClick()
        waitForIdle()
        assertEquals(listOf("dialog-c1"), opened)
        assertEquals(listOf("c2"), calledBack)
    }

    @Test
    fun anUnreachableCallHistoryOffersARetry() = runComposeUiTest {
        val retried = mutableListOf<Unit>()
        setContent {
            RelayTheme {
                CallsScreen(
                    state = CallsState(isLoaded = true, error = "Cannot reach the server"),
                    onOpenDialog = {},
                    onCallBack = {},
                    onRetry = { retried += Unit },
                    onNewGroupCall = {}
                )
            }
        }
        onNodeWithText("Cannot reach the server").assertIsDisplayed()
        onNodeWithTag(CALLS_RETRY_TAG).assertIsDisplayed().performClick()
        waitForIdle()
        assertEquals(1, retried.size)
    }

    @Test
    fun anEmptyCallHistoryExplainsItself() = runComposeUiTest {
        setContent {
            RelayTheme {
                CallsScreen(
                    state = CallsState(isLoaded = true),
                    onOpenDialog = {},
                    onCallBack = {},
                    onRetry = {},
                    onNewGroupCall = {}
                )
            }
        }
        onNodeWithText("No calls yet").assertIsDisplayed()
    }

    @Test
    fun anIncomingCallOffersAnswerAndDecline() = runComposeUiTest {
        val answered = mutableListOf<Unit>()
        val declined = mutableListOf<Unit>()
        setContent {
            RelayTheme {
                CallScreen(
                    state = CallUiState(
                        visible = true,
                        peerName = "Ada Lovelace",
                        status = "Incoming call",
                        actions = CallActionsUi.INCOMING
                    ),
                    onAccept = { answered += Unit },
                    onDecline = { declined += Unit },
                    onHangup = {},
                    onToggleMute = {},
                    onToggleSpeaker = {}
                )
            }
        }
        onNodeWithText("Ada Lovelace").assertIsDisplayed()
        onNodeWithText("Incoming call").assertIsDisplayed()
        onNodeWithTag(CALL_HANGUP_TAG).assertDoesNotExist()
        onNodeWithTag(CALL_ACCEPT_TAG).performClick()
        onNodeWithTag(CALL_DECLINE_TAG).performClick()
        waitForIdle()
        assertEquals(1, answered.size)
        assertEquals(1, declined.size)
    }

    @Test
    fun aCallInProgressOffersOnlyHangUpAndTheAudioToggles() = runComposeUiTest {
        val hungUp = mutableListOf<Unit>()
        setContent {
            RelayTheme {
                CallScreen(
                    state = CallUiState(
                        visible = true,
                        peerName = "Ada Lovelace",
                        status = "Connected",
                        actions = CallActionsUi.IN_PROGRESS
                    ),
                    onAccept = {},
                    onDecline = {},
                    onHangup = { hungUp += Unit },
                    onToggleMute = {},
                    onToggleSpeaker = {}
                )
            }
        }
        onNodeWithTag(CALL_ACCEPT_TAG).assertDoesNotExist()
        onNodeWithContentDescription("Mute microphone").assertIsDisplayed()
        onNodeWithTag(CALL_HANGUP_TAG).performClick()
        waitForIdle()
        assertEquals(1, hungUp.size)
    }

    @Test
    fun aFailedCallExplainsItselfAndDropsEveryAction() = runComposeUiTest {
        setContent {
            RelayTheme {
                CallScreen(
                    state = CallUiState(
                        visible = true,
                        peerName = "Ada Lovelace",
                        status = "Call ended",
                        actions = CallActionsUi.ENDED,
                        failure = "That person is already in a call"
                    ),
                    onAccept = {},
                    onDecline = {},
                    onHangup = {},
                    onToggleMute = {},
                    onToggleSpeaker = {}
                )
            }
        }
        onNodeWithText("That person is already in a call").assertIsDisplayed()
        onNodeWithTag(CALL_HANGUP_TAG).assertDoesNotExist()
        onNodeWithTag(CALL_ACCEPT_TAG).assertDoesNotExist()
    }
}
