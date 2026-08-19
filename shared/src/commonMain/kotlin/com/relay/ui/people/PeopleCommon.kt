package com.relay.ui.people

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.relay.ui.components.PersonRow
import com.relay.ui.state.PersonUi

@Composable
internal fun PeopleList(
    people: List<PersonUi>,
    pendingIds: Set<String>,
    onOpenChat: (String) -> Unit,
    onAddContact: (String) -> Unit,
    onRemoveContact: (String) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items = people, key = { it.id }) { person ->
            PersonRow(
                person = person,
                busy = person.id in pendingIds,
                onOpenChat = onOpenChat,
                onAddContact = onAddContact,
                onRemoveContact = onRemoveContact
            )
            HorizontalDivider()
        }
    }
}

@Composable
internal fun PeopleError(message: String) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}
