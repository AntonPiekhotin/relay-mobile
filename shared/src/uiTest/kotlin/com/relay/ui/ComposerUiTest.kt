package com.relay.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.relay.ui.components.COMPOSER_FIELD_TAG
import com.relay.ui.components.COMPOSER_SEND_TAG
import com.relay.ui.components.Composer
import com.relay.ui.theme.RelayTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ComposerUiTest {

    @Test
    fun sendIsDisabledUntilTheDraftHasContent() = runComposeUiTest {
        setContent {
            RelayTheme { Composer(draft = "", onDraftChange = {}, onSend = {}) }
        }
        onNodeWithTag(COMPOSER_SEND_TAG).assertIsNotEnabled()
    }

    @Test
    fun sendIsDisabledForWhitespaceOnlyDrafts() = runComposeUiTest {
        setContent {
            RelayTheme { Composer(draft = "   ", onDraftChange = {}, onSend = {}) }
        }
        onNodeWithTag(COMPOSER_SEND_TAG).assertIsNotEnabled()
    }

    @Test
    fun sendIsAnIconRatherThanALabelledButton() = runComposeUiTest {
        setContent {
            RelayTheme { Composer(draft = "hello", onDraftChange = {}, onSend = {}) }
        }
        onNodeWithText("Send").assertDoesNotExist()
        onNodeWithContentDescription("Send").assertIsDisplayed()
    }

    @Test
    fun typingEnablesSendAndTheClickIsReported() = runComposeUiTest {
        var sends = 0
        val draft = mutableStateOf("")
        setContent {
            RelayTheme {
                Composer(
                    draft = draft.value,
                    onDraftChange = { draft.value = it },
                    onSend = {
                        sends++
                        draft.value = ""
                    }
                )
            }
        }
        onNodeWithTag(COMPOSER_FIELD_TAG).assertIsDisplayed().performTextInput("hello")
        onNodeWithTag(COMPOSER_SEND_TAG).assertIsEnabled().performClick()
        waitForIdle()
        assertEquals(1, sends)
        onNodeWithTag(COMPOSER_SEND_TAG).assertIsNotEnabled()
    }
}
