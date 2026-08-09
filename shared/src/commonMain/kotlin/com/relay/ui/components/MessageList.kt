package com.relay.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.relay.ui.state.MessageUi

private const val LOAD_OLDER_THRESHOLD = 5

@Composable
fun MessageList(
    messages: List<MessageUi>,
    hasMoreHistory: Boolean,
    isLoadingOlder: Boolean,
    onRetry: (Long) -> Unit,
    onLoadOlder: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState()
) {
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    val nearTop by remember(messages.size) {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= messages.size - LOAD_OLDER_THRESHOLD
        }
    }

    val newest = messages.firstOrNull()
    LaunchedEffect(newest?.localId) {
        if (newest != null && (atBottom || newest.isMine)) listState.animateScrollToItem(0)
    }

    LaunchedEffect(nearTop, hasMoreHistory, messages.size) {
        if (nearTop && hasMoreHistory && messages.isNotEmpty()) onLoadOlder()
    }

    LazyColumn(
        reverseLayout = true,
        state = listState,
        modifier = modifier.fillMaxSize()
    ) {
        items(
            items = messages,
            key = { it.localId }
        ) { message ->
            MessageBubble(message = message, onRetry = onRetry)
        }

        if (isLoadingOlder) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}
