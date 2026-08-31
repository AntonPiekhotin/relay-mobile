package com.relay.ui.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.relay.network.MIN_SEARCH_LENGTH
import com.relay.ui.components.Avatar
import com.relay.ui.components.EmptyState
import com.relay.ui.components.SearchField
import com.relay.ui.state.GroupCreateState
import com.relay.ui.state.PersonUi

const val GROUP_TITLE_TAG = "group-title"
const val GROUP_MEMBER_SEARCH_TAG = "group-member-search"
const val GROUP_CREATE_TAG = "group-create"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupCreateScreen(
    state: GroupCreateState,
    onTitleChange: (String) -> Unit,
    onQueryChange: (String) -> Unit,
    onToggle: (PersonUi) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val query = state.query.trim()
    val isSearch = query.length >= MIN_SEARCH_LENGTH

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("New group") }) },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                if (state.error != null) {
                    Text(
                        text = state.error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                Button(
                    onClick = onCreate,
                    enabled = state.canCreate,
                    modifier = Modifier.fillMaxWidth().testTag(GROUP_CREATE_TAG)
                ) {
                    Text(createLabel(state.selected.size))
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.title,
                onValueChange = onTitleChange,
                placeholder = { Text("Group name (optional)") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag(GROUP_TITLE_TAG)
            )
            SearchField(
                value = state.query,
                onValueChange = onQueryChange,
                placeholder = "Add people by name or email",
                modifier = Modifier.testTag(GROUP_MEMBER_SEARCH_TAG)
            )
            if (state.isSearching) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (state.selected.isNotEmpty()) {
                Text(
                    text = "Members: ${state.selected.joinToString { it.name }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            val people = if (isSearch) state.results else state.contacts
            when {
                isSearch && people.isEmpty() && state.hasSearched && !state.isSearching ->
                    EmptyState(
                        title = "Nobody found",
                        detail = "No account matches “$query”."
                    )
                !isSearch && people.isEmpty() ->
                    EmptyState(
                        title = "No contacts yet",
                        detail = "Search for people above to add them to the group."
                    )
                else -> {
                    val selectedIds = state.selected.map { it.id }.toSet()
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(items = people, key = { it.id }) { person ->
                            SelectableMemberRow(
                                person = person,
                                selected = person.id in selectedIds,
                                onToggle = onToggle
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun createLabel(count: Int): String =
    if (count == 0) "Create group" else "Create group ($count)"

@Composable
private fun SelectableMemberRow(
    person: PersonUi,
    selected: Boolean,
    onToggle: (PersonUi) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(person) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(label = person.name)
        Text(
            text = person.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Checkbox(checked = selected, onCheckedChange = { onToggle(person) })
    }
}
