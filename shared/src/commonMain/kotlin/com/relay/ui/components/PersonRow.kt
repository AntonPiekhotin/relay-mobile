package com.relay.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.relay.ui.state.PersonUi

@Composable
fun PersonRow(
    person: PersonUi,
    busy: Boolean,
    onOpenChat: (String) -> Unit,
    onAddContact: (String) -> Unit,
    onRemoveContact: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = !busy) { onOpenChat(person.id) }
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
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
        } else if (person.isContact) {
            TextButton(onClick = { onRemoveContact(person.id) }) { Text("Remove") }
        } else {
            TextButton(onClick = { onAddContact(person.id) }) { Text("Add") }
        }
    }
}
