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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.relay.ui.components.ConnectionStrip
import com.relay.ui.components.DialogRow
import com.relay.ui.components.EmptyState
import com.relay.ui.components.SearchField
import com.relay.ui.state.DialogListState

const val DIALOG_SEARCH_FIELD_TAG = "dialog-search-field"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialogListScreen(
    state: DialogListState,
    onQueryChange: (String) -> Unit,
    onOpenDialog: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("Chats") }) }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ConnectionStrip(connection = state.connection)
            SearchField(
                value = state.query,
                onValueChange = onQueryChange,
                placeholder = "Search conversations",
                modifier = Modifier.testTag(DIALOG_SEARCH_FIELD_TAG)
            )
            when {
                state.dialogs.isNotEmpty() -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items = state.dialogs, key = { it.id }) { dialog ->
                        DialogRow(dialog = dialog, onClick = onOpenDialog)
                        HorizontalDivider()
                    }
                }
                state.query.isNotBlank() -> EmptyState(
                    title = "No matches",
                    detail = "Nothing here is named like that. Try fewer letters."
                )
                state.isLoaded -> EmptyState(
                    title = "No conversations",
                    detail = "Find someone on the Contacts tab and tap their name to start one."
                )
                else -> Unit
            }
        }
    }
}
