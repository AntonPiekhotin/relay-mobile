package com.relay.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.relay.ui.state.ConnectionUi

const val CONNECTION_STRIP_TAG = "connection-strip"

@Composable
fun ConnectionStrip(connection: ConnectionUi, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = connection is ConnectionUi.Reconnecting, modifier = modifier) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().testTag(CONNECTION_STRIP_TAG)
        ) {
            Text(
                text = "Connecting…",
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
        }
    }
}
