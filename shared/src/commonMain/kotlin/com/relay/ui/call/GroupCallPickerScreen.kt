package com.relay.ui.call

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.relay.ui.components.Avatar
import com.relay.ui.components.BackButton
import com.relay.ui.components.EmptyState
import com.relay.ui.state.GroupCallPickerState
import com.relay.ui.state.PersonUi

const val GROUP_CALL_START_TAG = "group-call-start"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupCallPickerScreen(
    state: GroupCallPickerState,
    onToggle: (String) -> Unit,
    onStart: () -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Groups") },
                navigationIcon = { if (onBack != null) BackButton(onBack = onBack) }
            )
        },
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
                    onClick = onStart,
                    enabled = state.canStart,
                    modifier = Modifier.fillMaxWidth().testTag(GROUP_CALL_START_TAG)
                ) {
                    Text(startLabel(state.selectedIds.size))
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.contacts.isEmpty()) {
                EmptyState(
                    title = "No contacts yet",
                    detail = "Add contacts to start a group call."
                )
                return@Column
            }
            Text(
                text = "Pick the people to call together.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items = state.contacts, key = { it.id }) { person ->
                    SelectablePersonRow(
                        person = person,
                        selected = person.id in state.selectedIds,
                        onToggle = onToggle
                    )
                }
            }
        }
    }
}

private fun startLabel(count: Int): String =
    if (count == 0) "Start call" else "Start call ($count)"

@Composable
private fun SelectablePersonRow(
    person: PersonUi,
    selected: Boolean,
    onToggle: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(person.id) }
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
        Checkbox(checked = selected, onCheckedChange = { onToggle(person.id) })
    }
}
