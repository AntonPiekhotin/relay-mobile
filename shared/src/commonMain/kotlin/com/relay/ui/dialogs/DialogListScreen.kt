package com.relay.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.relay.ui.components.AccountGlyph
import com.relay.ui.components.ConnectionStrip
import com.relay.ui.components.DialogRow
import com.relay.ui.components.EmptyState
import com.relay.ui.components.SearchGlyph
import com.relay.ui.state.DialogListState

const val DIALOGS_SEARCH_TAG = "dialogs-search"
const val DIALOGS_PROFILE_TAG = "dialogs-profile"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialogListScreen(
    state: DialogListState,
    onOpenDialog: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenProfile: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Relay") },
                actions = {
                    IconButton(onClick = onOpenSearch, modifier = Modifier.testTag(DIALOGS_SEARCH_TAG)) {
                        SearchGlyph(contentDescription = "Search people")
                    }
                    IconButton(onClick = onOpenProfile, modifier = Modifier.testTag(DIALOGS_PROFILE_TAG)) {
                        AccountGlyph(contentDescription = "Your profile")
                    }
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
                    detail = "Tap the search icon, find someone, and tap their name to start one."
                )
                else -> Unit
            }
        }
    }
}
