package com.relay.ui.people

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.relay.network.MIN_SEARCH_LENGTH
import com.relay.ui.components.EmptyState
import com.relay.ui.components.PersonRow
import com.relay.ui.state.PeopleState
import com.relay.ui.state.PeopleTab
import com.relay.ui.state.PersonUi

const val PEOPLE_SEARCH_FIELD_TAG = "people-search-field"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(
    state: PeopleState,
    onTabChange: (PeopleTab) -> Unit,
    onQueryChange: (String) -> Unit,
    onAddContact: (String) -> Unit,
    onRemoveContact: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("People") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = state.tab.ordinal) {
                Tab(
                    selected = state.tab == PeopleTab.CONTACTS,
                    onClick = { onTabChange(PeopleTab.CONTACTS) },
                    text = { Text("Contacts") }
                )
                Tab(
                    selected = state.tab == PeopleTab.SEARCH,
                    onClick = { onTabChange(PeopleTab.SEARCH) },
                    text = { Text("Search") }
                )
            }
            if (state.isRefreshing || state.isSearching) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (state.error != null) {
                Text(
                    text = state.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            when (state.tab) {
                PeopleTab.CONTACTS -> ContactsTab(state, onAddContact, onRemoveContact)
                PeopleTab.SEARCH -> SearchTab(state, onQueryChange, onAddContact, onRemoveContact)
            }
        }
    }
}

@Composable
private fun ContactsTab(
    state: PeopleState,
    onAddContact: (String) -> Unit,
    onRemoveContact: (String) -> Unit
) {
    if (state.contacts.isEmpty()) {
        EmptyState(
            title = "No contacts yet",
            detail = "Find people on the Search tab and add them here."
        )
    } else {
        PeopleList(state.contacts, state, onAddContact, onRemoveContact)
    }
}

@Composable
private fun SearchTab(
    state: PeopleState,
    onQueryChange: (String) -> Unit,
    onAddContact: (String) -> Unit,
    onRemoveContact: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
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
        when {
            state.query.trim().length < MIN_SEARCH_LENGTH -> EmptyState(
                title = "Find someone",
                detail = "Type at least $MIN_SEARCH_LENGTH characters to search by name or email."
            )
            state.results.isEmpty() && state.hasSearched && !state.isSearching -> EmptyState(
                title = "Nobody found",
                detail = "No account matches “${state.query.trim()}”."
            )
            else -> PeopleList(state.results, state, onAddContact, onRemoveContact)
        }
    }
}

@Composable
private fun PeopleList(
    people: List<PersonUi>,
    state: PeopleState,
    onAddContact: (String) -> Unit,
    onRemoveContact: (String) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items = people, key = { it.id }) { person ->
            PersonRow(
                person = person,
                busy = person.id in state.pendingIds,
                onAddContact = onAddContact,
                onRemoveContact = onRemoveContact
            )
            HorizontalDivider()
        }
        item {
            Text(
                text = "Starting a conversation is not possible yet — the backend exposes dialog " +
                    "creation only on its internal API, which the gateway does not route.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}
