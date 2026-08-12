package com.relay.ui.components

import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

const val BACK_BUTTON_TAG = "back-button"

@Composable
fun BackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onBack, modifier = modifier.testTag(BACK_BUTTON_TAG)) {
        BackGlyph(contentDescription = "Back")
    }
}
