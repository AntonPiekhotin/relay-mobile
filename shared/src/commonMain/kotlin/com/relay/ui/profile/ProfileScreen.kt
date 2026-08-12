package com.relay.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.relay.ui.components.Avatar
import com.relay.ui.components.LARGE_AVATAR_SIZE
import com.relay.ui.state.ProfileState
import com.relay.ui.state.ProfileUi

const val PROFILE_LOGOUT_TAG = "profile-logout"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    state: ProfileState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Profile") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when {
                state.profile != null -> ProfileDetails(state.profile)
                state.isLoading -> CircularProgressIndicator()
                else -> ProfileUnavailable(message = state.error, onRetry = onRetry)
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            OutlinedButton(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth().testTag(PROFILE_LOGOUT_TAG)
            ) {
                Text("Log out")
            }
        }
    }
}

@Composable
private fun ProfileDetails(profile: ProfileUi) {
    Avatar(
        label = profile.name,
        size = LARGE_AVATAR_SIZE,
        textStyle = MaterialTheme.typography.headlineMedium
    )
    Text(text = profile.name, style = MaterialTheme.typography.headlineSmall)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DetailRow(label = "Email", value = profile.email)
        if (profile.memberSince != null) {
            DetailRow(label = "Member since", value = profile.memberSince)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ProfileUnavailable(message: String?, onRetry: () -> Unit) {
    Text(
        text = message ?: "Your profile could not be loaded",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center
    )
    TextButton(onClick = onRetry) { Text("Try again") }
}
