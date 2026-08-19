package com.relay.ui.calls

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.relay.ui.components.Avatar
import com.relay.ui.components.EmptyState
import com.relay.ui.components.PhoneGlyph
import com.relay.ui.state.CallLogUi
import com.relay.ui.state.CallsState

const val CALLS_RETRY_TAG = "calls-retry"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallsScreen(
    state: CallsState,
    onOpenDialog: (String) -> Unit,
    onCallBack: (CallLogUi) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("Calls") }) }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            when {
                state.calls.isNotEmpty() -> {
                    if (state.error != null) {
                        CallsError(state.error)
                    }
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(items = state.calls, key = { it.id }) { call ->
                            CallLogRow(call = call, onOpenDialog = onOpenDialog, onCallBack = onCallBack)
                            HorizontalDivider()
                        }
                    }
                }
                state.error != null -> CallsUnavailable(message = state.error, onRetry = onRetry)
                state.isLoaded -> EmptyState(
                    title = "No calls yet",
                    detail = "Calls you make and receive will appear here."
                )
                else -> Unit
            }
        }
    }
}

@Composable
private fun CallLogRow(
    call: CallLogUi,
    onOpenDialog: (String) -> Unit,
    onCallBack: (CallLogUi) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = call.dialogId != null) { call.dialogId?.let(onOpenDialog) }
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(label = call.peerName)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = call.peerName,
                style = MaterialTheme.typography.titleMedium,
                color = if (call.isMissed) MaterialTheme.colorScheme.error else Color.Unspecified,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = call.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = call.timestamp,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (call.peerId != null) {
            IconButton(onClick = { onCallBack(call) }) {
                PhoneGlyph(contentDescription = "Call ${call.peerName}")
            }
        }
    }
}

@Composable
private fun CallsError(message: String) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun CallsUnavailable(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium
        )
        TextButton(onClick = onRetry, modifier = Modifier.testTag(CALLS_RETRY_TAG)) {
            Text("Try again")
        }
    }
}
