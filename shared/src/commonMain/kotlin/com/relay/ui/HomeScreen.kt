package com.relay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.relay.network.ConnectionState

@Composable
fun HomeScreen(
    connectionState: ConnectionState,
    onLogout: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(connectionState.indicatorColor(), CircleShape)
            )
            Text(
                text = connectionState.label(),
                style = MaterialTheme.typography.titleMedium
            )
        }
        if (connectionState is ConnectionState.Connected) {
            Text(
                text = "user: ${connectionState.userId}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "session: ${connectionState.sessionId}",
                style = MaterialTheme.typography.bodySmall
            )
        }
        TextButton(onClick = onLogout) {
            Text("Log out")
        }
    }
}

private fun ConnectionState.label(): String = when (this) {
    is ConnectionState.Connected -> "Connected"
    is ConnectionState.Connecting -> "Connecting…"
    is ConnectionState.Disconnected -> "Offline"
}

private fun ConnectionState.indicatorColor(): Color = when (this) {
    is ConnectionState.Connected -> Color(0xFF2E7D32)
    is ConnectionState.Connecting -> Color(0xFFF9A825)
    is ConnectionState.Disconnected -> Color(0xFF9E9E9E)
}
