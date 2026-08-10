package com.relay.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.relay.ui.chat.ChatScreen
import com.relay.ui.chat.ChatViewModel
import com.relay.ui.dialogs.DialogListScreen
import com.relay.ui.dialogs.DialogListViewModel
import com.relay.ui.people.PeopleScreen
import com.relay.ui.people.PeopleViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun RelayNavHost(
    onLogout: () -> Unit,
    navController: NavHostController = rememberNavController()
) {
    NavHost(navController = navController, startDestination = Route.DialogList) {
        composable<Route.DialogList> {
            val viewModel = koinViewModel<DialogListViewModel>()
            val state by viewModel.state.collectAsStateWithLifecycle()
            DialogListScreen(
                state = state,
                onOpenDialog = { navController.navigate(Route.Chat(it)) },
                onOpenPeople = { navController.navigate(Route.People) },
                onLogout = onLogout
            )
        }

        composable<Route.Chat> { entry ->
            val dialogId = entry.toRoute<Route.Chat>().dialogId
            val viewModel = koinViewModel<ChatViewModel> { parametersOf(dialogId) }
            val state by viewModel.state.collectAsStateWithLifecycle()
            ChatScreen(
                state = state,
                onDraftChange = viewModel::onDraftChange,
                onSend = viewModel::send,
                onRetry = viewModel::retry,
                onLoadOlder = viewModel::loadOlder,
                onBack = { navController.popBackStack() },
                onDismissError = viewModel::dismissError
            )
        }

        composable<Route.People> {
            val viewModel = koinViewModel<PeopleViewModel>()
            val state by viewModel.state.collectAsStateWithLifecycle()
            LaunchedEffect(viewModel) {
                viewModel.openedDialog.collect { dialogId ->
                    navController.navigate(Route.Chat(dialogId))
                }
            }
            PeopleScreen(
                state = state,
                onTabChange = viewModel::selectTab,
                onQueryChange = viewModel::onQueryChange,
                onOpenChat = viewModel::openChat,
                onAddContact = viewModel::addContact,
                onRemoveContact = viewModel::removeContact,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
