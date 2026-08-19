package com.relay.ui.people

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.relay.ui.components.EmptyState
import com.relay.ui.state.PeopleState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    state: PeopleState,
    onOpenChat: (String) -> Unit,
    onAddContact: (String) -> Unit,
    onRemoveContact: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("Contacts") }) }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.isRefreshing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (state.error != null) {
                PeopleError(state.error)
            }
            if (state.contacts.isEmpty()) {
                EmptyState(
                    title = "No contacts yet",
                    detail = "Find people on the Search tab and add them here."
                )
            } else {
                PeopleList(
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
