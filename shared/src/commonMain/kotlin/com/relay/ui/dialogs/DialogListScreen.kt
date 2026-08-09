package com.relay.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.relay.ui.components.ConnectionStrip
import com.relay.ui.components.DialogRow
import com.relay.ui.components.EmptyState
import com.relay.ui.state.DialogListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialogListScreen(
    state: DialogListState,
    onOpenDialog: (String) -> Unit,
    onOpenPeople: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Relay") },
                actions = {
                    TextButton(onClick = onOpenPeople) { Text("People") }
                    TextButton(onClick = onLogout) { Text("Log out") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ConnectionStrip(connection = state.connection)
            when {
                state.dialogs.isNotEmpty() -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items = state.dialogs, key = { it.id }) { dialog ->
                        DialogRow(dialog = dialog, onClick = onOpenDialog)
                        HorizontalDivider()
                    }
                }
                state.isLoaded -> EmptyState(
                    title = "No conversations",
                    detail = "Conversations appear here once a dialog exists on the server. " +
                        "The backend does not yet expose dialog creation to clients."
                )
                else -> Unit
            }
        }
    }
}
