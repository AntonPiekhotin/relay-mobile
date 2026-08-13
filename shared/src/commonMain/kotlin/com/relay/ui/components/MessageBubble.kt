package com.relay.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.relay.ui.state.MessageStatusUi
import com.relay.ui.state.MessageUi

private val BUBBLE_MAX_WIDTH = 300.dp

@Composable
fun MessageBubble(
    message: MessageUi,
    onRetry: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (message.daySeparator != null) {
            DaySeparator(label = message.daySeparator)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = if (message.isMine) Arrangement.End else Arrangement.Start
        ) {
            Column(horizontalAlignment = if (message.isMine) Alignment.End else Alignment.Start) {
                Surface(
                    color = bubbleColor(message.isMine),
                    contentColor = bubbleContentColor(message.isMine),
                    shape = bubbleShape(message.isMine)
                ) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .widthIn(max = BUBBLE_MAX_WIDTH)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
                MessageMeta(message = message, onRetry = onRetry)
            }
        }
    }
}

@Composable
private fun MessageMeta(message: MessageUi, onRetry: (Long) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Text(
            text = message.timestamp,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (message.isMine) {
            Text(
                text = statusGlyph(message.status),
                style = MaterialTheme.typography.labelSmall,
                color = statusColor(message.status),
                modifier = Modifier.semantics { contentDescription = statusDescription(message.status) }
            )
        }
        if (message.status == MessageStatusUi.FAILED) {
            RetryChip(localId = message.localId, reason = message.failReason, onRetry = onRetry)
        }
    }
}

@Composable
private fun DaySeparator(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
    )
}

@Composable
private fun bubbleColor(isMine: Boolean) =
    if (isMine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant

@Composable
private fun bubbleContentColor(isMine: Boolean) =
    if (isMine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

private fun bubbleShape(isMine: Boolean) =
    if (isMine) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp)
    }

fun statusGlyph(status: MessageStatusUi): String = when (status) {
    MessageStatusUi.SENDING -> "○"
    MessageStatusUi.SENT -> "✓"
    MessageStatusUi.READ -> "✓✓"
    MessageStatusUi.FAILED -> "!"
}

fun statusDescription(status: MessageStatusUi): String = when (status) {
    MessageStatusUi.SENDING -> "Sending"
    MessageStatusUi.SENT -> "Sent"
    MessageStatusUi.READ -> "Read"
    MessageStatusUi.FAILED -> "Failed"
}

@Composable
private fun statusColor(status: MessageStatusUi) = when (status) {
    MessageStatusUi.FAILED -> MaterialTheme.colorScheme.error
    MessageStatusUi.READ -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
