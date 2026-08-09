package com.relay.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.relay.model.MessageState
import com.relay.ui.components.MessageList
import com.relay.ui.state.MessageUi
import com.relay.ui.theme.RelayTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun message(
    localId: Long,
    text: String = "message-$localId",
    isMine: Boolean = true,
    status: MessageState = MessageState.SENT,
    failReason: String? = null,
    daySeparator: String? = null
) = MessageUi(
    localId = localId,
    text = text,
    isMine = isMine,
    timestamp = "12:00",
    status = status,
    failReason = failReason,
    daySeparator = daySeparator
)

@OptIn(ExperimentalTestApi::class)
class MessageListUiTest {

    @Test
    fun rendersNewestFirstDataWithBothSidesOfTheConversation() = runComposeUiTest {
        setContent {
            RelayTheme {
                MessageList(
                    messages = listOf(
                        message(2, "mine", isMine = true),
                        message(1, "theirs", isMine = false)
                    ),
                    hasMoreHistory = false,
                    isLoadingOlder = false,
                    onRetry = {},
                    onLoadOlder = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        onNodeWithText("mine").assertIsDisplayed()
        onNodeWithText("theirs").assertIsDisplayed()
    }

    @Test
    fun aPendingMessageIsAnnouncedAsSending() = runComposeUiTest {
        setContent {
            RelayTheme {
                MessageList(
                    messages = listOf(message(1, status = MessageState.PENDING)),
                    hasMoreHistory = false,
                    isLoadingOlder = false,
                    onRetry = {},
                    onLoadOlder = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        onNodeWithContentDescription("Sending").assertIsDisplayed()
    }

    @Test
    fun promotingPendingToSentKeepsTheRowAndSwapsTheStatus() = runComposeUiTest {
        val status = mutableStateOf(MessageState.PENDING)
        setContent {
            RelayTheme {
                MessageList(
                    messages = listOf(message(1, "hello", status = status.value)),
                    hasMoreHistory = false,
                    isLoadingOlder = false,
                    onRetry = {},
                    onLoadOlder = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        onNodeWithContentDescription("Sending").assertIsDisplayed()

        status.value = MessageState.SENT
        waitForIdle()

        onNodeWithText("hello").assertIsDisplayed()
        onNodeWithContentDescription("Sent").assertIsDisplayed()
    }

    @Test
    fun aFailedMessageOffersRetryAndReportsTheTappedRow() = runComposeUiTest {
        val retried = mutableListOf<Long>()
        setContent {
            RelayTheme {
                MessageList(
                    messages = listOf(
                        message(7, status = MessageState.FAILED, failReason = "PAYLOAD_TOO_LARGE")
                    ),
                    hasMoreHistory = false,
                    isLoadingOlder = false,
                    onRetry = { retried += it },
                    onLoadOlder = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        onNodeWithText("Retry · PAYLOAD_TOO_LARGE").assertIsDisplayed().performClick()
        waitForIdle()
        assertEquals(listOf(7L), retried)
    }

    @Test
    fun daySeparatorsRenderAboveTheirMessage() = runComposeUiTest {
        setContent {
            RelayTheme {
                MessageList(
                    messages = listOf(
                        message(2, "today"),
                        message(1, "earlier", daySeparator = "Yesterday")
                    ),
                    hasMoreHistory = false,
                    isLoadingOlder = false,
                    onRetry = {},
                    onLoadOlder = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        onNodeWithText("Yesterday").assertIsDisplayed()
    }

    @Test
    fun reachingTheTopWithMoreHistoryTriggersASingleLoad() = runComposeUiTest {
        var loads = 0
        setContent {
            RelayTheme {
                MessageList(
                    messages = List(3) { message(it.toLong() + 1) },
                    hasMoreHistory = true,
                    isLoadingOlder = false,
                    onRetry = {},
                    onLoadOlder = { loads++ },
                    listState = LazyListState(),
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        waitForIdle()
        assertEquals(1, loads)
    }

    @Test
    fun noLoadIsRequestedWhenHistoryIsExhausted() = runComposeUiTest {
        var loads = 0
        setContent {
            RelayTheme {
                MessageList(
                    messages = List(3) { message(it.toLong() + 1) },
                    hasMoreHistory = false,
                    isLoadingOlder = false,
                    onRetry = {},
                    onLoadOlder = { loads++ },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        waitForIdle()
        assertTrue(loads == 0)
    }

    @Test
    fun theUsersOwnSendIsScrolledIntoViewEvenWhileScrolledUp() = runComposeUiTest {
        val messages = mutableStateOf(List(30) { message(30L - it, isMine = false) })
        val listState = LazyListState(firstVisibleItemIndex = 6)
        setContent {
            RelayTheme {
                MessageList(
                    messages = messages.value,
                    hasMoreHistory = false,
                    isLoadingOlder = false,
                    onRetry = {},
                    onLoadOlder = {},
                    listState = listState,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        waitForIdle()
        assertTrue(listState.firstVisibleItemIndex > 0)

        messages.value = listOf(message(99, "just sent", isMine = true)) + messages.value
        waitUntil { listState.firstVisibleItemIndex == 0 }

        assertEquals(0, listState.firstVisibleItemIndex)
    }

    @Test
    fun anIncomingMessageDoesNotYankTheViewWhileScrolledUp() = runComposeUiTest {
        val messages = mutableStateOf(List(30) { message(30L - it, isMine = false) })
        val listState = LazyListState(firstVisibleItemIndex = 6)
        setContent {
            RelayTheme {
                MessageList(
                    messages = messages.value,
                    hasMoreHistory = false,
                    isLoadingOlder = false,
                    onRetry = {},
                    onLoadOlder = {},
                    listState = listState,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        waitForIdle()
        val before = listState.firstVisibleItemIndex
        assertTrue(before > 0)

        messages.value = listOf(message(99, "from them", isMine = false)) + messages.value
        waitForIdle()

        assertTrue(listState.firstVisibleItemIndex > 0)
    }

    @Test
    fun aVeryLongUnbrokenStringStillRenders() = runComposeUiTest {
        val wall = "x".repeat(500)
        setContent {
            RelayTheme {
                MessageList(
                    messages = listOf(message(1, wall)),
                    hasMoreHistory = false,
                    isLoadingOlder = false,
                    onRetry = {},
                    onLoadOlder = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        onNodeWithText(wall).assertIsDisplayed()
    }
}
