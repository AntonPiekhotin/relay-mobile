package com.relay.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.relay.ui.components.Composer
import com.relay.ui.components.ConnectionStrip
import com.relay.ui.components.EmptyState
import com.relay.ui.components.MessageList
import com.relay.ui.state.ChatState

const val CHAT_ERROR_TAG = "chat-error"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onRetry: (Long) -> Unit,
    onLoadOlder: () -> Unit,
    onBack: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState()
) {
    Scaffold(
        modifier = modifier.fillMaxSize().imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(state.title) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ConnectionStrip(connection = state.connection)
            if (state.messages.isEmpty()) {
                Column(modifier = Modifier.weight(1f)) {
                    EmptyState(
                        title = "No messages yet",
                        detail = "Anything you send is stored locally first and delivered when the socket allows."
                    )
                }
            } else {
                MessageList(
                    messages = state.messages,
                    hasMoreHistory = state.hasMoreHistory,
                    isLoadingOlder = state.isLoadingOlder,
                    onRetry = onRetry,
                    onLoadOlder = onLoadOlder,
                    listState = listState,
                    modifier = Modifier.weight(1f)
                )
            }
            if (state.error != null) {
                SendErrorBanner(message = state.error, onDismiss = onDismissError)
            }
            Composer(draft = state.draft, onDraftChange = onDraftChange, onSend = onSend)
        }
    }
}

@Composable
private fun SendErrorBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.error,
        contentColor = MaterialTheme.colorScheme.onError,
        modifier = Modifier.fillMaxWidth().testTag(CHAT_ERROR_TAG)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}
