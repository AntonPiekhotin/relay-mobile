package com.relay.ui.people

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.relay.network.MIN_SEARCH_LENGTH
import com.relay.ui.components.EmptyState
import com.relay.ui.components.SearchField
import com.relay.ui.state.PeopleState

const val PEOPLE_SEARCH_FIELD_TAG = "people-search-field"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    state: PeopleState,
    onQueryChange: (String) -> Unit,
    onOpenChat: (String) -> Unit,
    onAddContact: (String) -> Unit,
    onRemoveContact: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val query = state.query.trim()
    val isSearch = query.length >= MIN_SEARCH_LENGTH

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("Contacts") }) }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            SearchField(
                value = state.query,
                onValueChange = onQueryChange,
                placeholder = "Find people by name or email",
                modifier = Modifier.testTag(PEOPLE_SEARCH_FIELD_TAG)
            )
            if (state.isSearching || state.isRefreshing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (state.error != null) {
                PeopleError(state.error)
            }
            when {
                query.isNotEmpty() && !isSearch -> SearchHint()
                isSearch && state.results.isEmpty() && state.hasSearched && !state.isSearching ->
                    EmptyState(
                        title = "Nobody found",
                        detail = "No account matches “$query”."
                    )
                isSearch -> PeopleList(
                    people = state.results,
                    pendingIds = state.pendingIds,
                    onOpenChat = onOpenChat,
                    onAddContact = onAddContact,
                    onRemoveContact = onRemoveContact
                )
                state.contacts.isEmpty() -> EmptyState(
                    title = "No contacts yet",
                    detail = "Search for people above and add them here."
                )
                else -> PeopleList(
                    people = state.contacts,
                    pendingIds = state.pendingIds,
                    onOpenChat = onOpenChat,
                    onAddContact = onAddContact,
                    onRemoveContact = onRemoveContact
                )
            }
        }
    }
}

@Composable
private fun SearchHint() {
    Text(
        text = "Type at least $MIN_SEARCH_LENGTH characters to search by name or email.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}
