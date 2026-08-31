package com.relay.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.relay.ui.calls.CallsScreen
import com.relay.ui.calls.CallsViewModel
import com.relay.ui.dialogs.DialogListScreen
import com.relay.ui.dialogs.DialogListViewModel
import com.relay.ui.groups.GroupCreateScreen
import com.relay.ui.groups.GroupCreateViewModel
import com.relay.ui.people.ContactsScreen
import com.relay.ui.people.PeopleViewModel
import com.relay.ui.profile.ProfileScreen
import com.relay.ui.profile.ProfileViewModel
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun HomeScreen(
    onOpenDialog: (String) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    var tabName by rememberSaveable { mutableStateOf(HomeTab.CHATS.name) }
    val tab = HomeTab.valueOf(tabName)
    val dialogsViewModel = koinViewModel<DialogListViewModel>()
    val dialogsState by dialogsViewModel.state.collectAsStateWithLifecycle()
    val pageStateHolder = rememberSaveableStateHolder()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            HomeBottomBar(
                selected = tab,
                chatsBadge = dialogsState.unreadTotal,
                onSelect = { tabName = it.name }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            pageStateHolder.SaveableStateProvider(tabName) {
                when (tab) {
                    HomeTab.CHATS -> DialogListScreen(
                        state = dialogsState,
                        onQueryChange = dialogsViewModel::onQueryChange,
                        onOpenDialog = onOpenDialog
                    )
                    HomeTab.GROUPS -> GroupsTab(onOpenDialog = onOpenDialog)
                    HomeTab.CALLS -> CallsTab(onOpenDialog = onOpenDialog)
                    HomeTab.CONTACTS -> ContactsTab(onOpenDialog = onOpenDialog)
                    HomeTab.SETTINGS -> SettingsTab(onLogout = onLogout)
                }
            }
        }
    }
}

@Composable
private fun ContactsTab(onOpenDialog: (String) -> Unit) {
    val viewModel = koinViewModel<PeopleViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.openedDialog.collect { onOpenDialog(it) }
    }
    ContactsScreen(
        state = state,
        onQueryChange = viewModel::onQueryChange,
        onOpenChat = viewModel::openChat,
        onAddContact = viewModel::addContact,
        onRemoveContact = viewModel::removeContact
    )
}

@Composable
private fun GroupsTab(onOpenDialog: (String) -> Unit) {
    val viewModel = koinViewModel<GroupCreateViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.created.collect { onOpenDialog(it) }
    }
    GroupCreateScreen(
        state = state,
        onTitleChange = viewModel::onTitleChange,
        onQueryChange = viewModel::onQueryChange,
        onToggle = viewModel::toggle,
        onCreate = viewModel::create
    )
}

@Composable
private fun CallsTab(onOpenDialog: (String) -> Unit) {
    val viewModel = koinViewModel<CallsViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    CallsScreen(
        state = state,
        onOpenDialog = onOpenDialog,
        onCallBack = viewModel::callBack,
        onRetry = viewModel::refresh
    )
}

@Composable
private fun SettingsTab(onLogout: () -> Unit) {
    val viewModel = koinViewModel<ProfileViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    ProfileScreen(
        state = state,
        onRetry = viewModel::refresh,
        onLogout = onLogout
    )
}
