package com.relay.ui.people

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.relay.network.MIN_SEARCH_LENGTH
import com.relay.ui.components.EmptyState
import com.relay.ui.state.PeopleState

const val PEOPLE_SEARCH_FIELD_TAG = "people-search-field"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    state: PeopleState,
    onQueryChange: (String) -> Unit,
    onOpenChat: (String) -> Unit,
    onAddContact: (String) -> Unit,
    onRemoveContact: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("Search") }) }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                label = { Text("Name or email") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .testTag(PEOPLE_SEARCH_FIELD_TAG)
            )
            if (state.isSearching) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (state.error != null) {
                PeopleError(state.error)
            }
            when {
                state.query.trim().length < MIN_SEARCH_LENGTH -> EmptyState(
                    title = "Find someone",
                    detail = "Type at least $MIN_SEARCH_LENGTH characters to search by name or email."
                )
                state.results.isEmpty() && state.hasSearched && !state.isSearching -> EmptyState(
                    title = "Nobody found",
                    detail = "No account matches “${state.query.trim()}”."
                )
                else -> PeopleList(
                    people = state.results,
                    pendingIds = state.pendingIds,
                    onOpenChat = onOpenChat,
                    onAddContact = onAddContact,
                    onRemoveContact = onRemoveContact
                )
            }
        }
    }
}
