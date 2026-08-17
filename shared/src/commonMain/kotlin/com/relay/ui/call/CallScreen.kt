package com.relay.ui.call

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.relay.protocol.nowEpochMillis
import com.relay.ui.components.DECLINE_ROTATION
import com.relay.ui.components.MicrophoneGlyph
import com.relay.ui.components.PhoneGlyph
import com.relay.ui.components.SpeakerGlyph
import com.relay.ui.format.formatDuration
import com.relay.ui.state.CallActionsUi
import com.relay.ui.state.CallUiState
import kotlinx.coroutines.delay

const val CALL_SCREEN_TAG = "call-screen"
const val CALL_ACCEPT_TAG = "call-accept"
const val CALL_DECLINE_TAG = "call-decline"
const val CALL_HANGUP_TAG = "call-hangup"

private val ACCEPT_GREEN = Color(0xFF1F8A4C)
private val DECLINE_RED = Color(0xFFC5372C)
private val ACTION_SIZE = 68.dp
private const val TIMER_TICK_MILLIS = 500L

@Composable
fun CallScreen(
    state: CallUiState,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onHangup: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxSize().testTag(CALL_SCREEN_TAG)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(72.dp))
            Text(
                text = state.peerName,
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
            Spacer(modifier = Modifier.weight(1f))
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
private fun rememberElapsed(answeredAt: Long): String {
    val elapsed by produceState(initialValue = formatDuration(nowEpochMillis() - answeredAt), answeredAt) {
        while (true) {
            value = formatDuration(nowEpochMillis() - answeredAt)
            delay(TIMER_TICK_MILLIS)
        }
    }
    return elapsed
}

@Composable
private fun CallActions(
    actions: CallActionsUi,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onHangup: () -> Unit
) {
    when (actions) {
        CallActionsUi.INCOMING -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            RoundAction(
                color = DECLINE_RED,
                description = "Decline call",
                testTag = CALL_DECLINE_TAG,
                rotation = DECLINE_ROTATION,
                onClick = onDecline
            )
            RoundAction(
                color = ACCEPT_GREEN,
                description = "Answer call",
                testTag = CALL_ACCEPT_TAG,
                rotation = 0f,
                onClick = onAccept
            )
        }
        CallActionsUi.IN_PROGRESS -> RoundAction(
            color = DECLINE_RED,
            description = "End call",
            testTag = CALL_HANGUP_TAG,
            rotation = DECLINE_ROTATION,
            onClick = onHangup
        )
        CallActionsUi.ENDED -> Spacer(modifier = Modifier.height(ACTION_SIZE))
    }
}

@Composable
private fun RoundAction(
    color: Color,
    description: String,
    testTag: String,
    rotation: Float,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(ACTION_SIZE).testTag(testTag)
    ) {
        PhoneGlyph(contentDescription = description, rotationDegrees = rotation)
    }
}

@Composable
private fun InCallToggles(
    muted: Boolean,
    speakerOn: Boolean,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        ToggleAction(active = muted, onClick = onToggleMute) {
            MicrophoneGlyph(
                contentDescription = if (muted) "Unmute microphone" else "Mute microphone",
                muted = muted
            )
        }
        ToggleAction(active = speakerOn, onClick = onToggleSpeaker) {
            SpeakerGlyph(
                contentDescription = if (speakerOn) "Turn speaker off" else "Turn speaker on",
                enabled = speakerOn
            )
        }
    }
}

@Composable
private fun ToggleAction(
    active: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val container = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surface
    }
    val tint = if (active) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Button(
        onClick = onClick,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = tint),
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(56.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            content()
        }
    }
}
