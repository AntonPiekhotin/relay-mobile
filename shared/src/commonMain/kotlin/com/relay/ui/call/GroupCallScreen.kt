package com.relay.ui.call

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.relay.ui.components.Avatar
import com.relay.ui.state.CallActionsUi
import com.relay.ui.state.GroupCallParticipantUi
import com.relay.ui.state.GroupCallUiState

const val GROUP_CALL_SCREEN_TAG = "group-call-screen"

@Composable
fun GroupCallScreen(
    state: GroupCallUiState,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onHangup: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxSize().testTag(GROUP_CALL_SCREEN_TAG)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))
            Text(
                text = state.title,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = state.answeredAt?.let { rememberElapsed(it) } ?: state.status,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.failure != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = state.failure,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(items = state.participants, key = { it.userId }) { participant ->
                    ParticipantRow(participant)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            if (state.actions == CallActionsUi.IN_PROGRESS) {
                InCallToggles(
                    muted = state.muted,
                    speakerOn = state.speakerOn,
                    onToggleMute = onToggleMute,
                    onToggleSpeaker = onToggleSpeaker
                )
                Spacer(modifier = Modifier.height(32.dp))
            }
            CallActions(
                actions = state.actions,
                onAccept = onAccept,
                onDecline = onDecline,
                onHangup = onHangup
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ParticipantRow(participant: GroupCallParticipantUi) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(label = participant.name)
        Text(
            text = participant.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = participant.stateLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = if (participant.isJoined) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}
