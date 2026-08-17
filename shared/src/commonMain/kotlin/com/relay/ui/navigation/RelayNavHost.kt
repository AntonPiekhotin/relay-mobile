package com.relay.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.relay.push.PushNavigator
import com.relay.ui.chat.ChatScreen
import com.relay.ui.chat.ChatViewModel
import com.relay.ui.components.SwipeBackBox
import com.relay.ui.dialogs.DialogListScreen
import com.relay.ui.dialogs.DialogListViewModel
import com.relay.ui.people.PeopleScreen
import com.relay.ui.people.PeopleViewModel
import com.relay.ui.profile.ProfileScreen
import com.relay.ui.profile.ProfileViewModel
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun RelayNavHost(
    onLogout: () -> Unit,
    navController: NavHostController = rememberNavController()
) {
    val pushNavigator = koinInject<PushNavigator>()
    LaunchedEffect(navController) {
        pushNavigator.targets.collect { dialogId ->
            navController.navigate(Route.Chat(dialogId)) {
                popUpTo<Route.DialogList>()
            }
        }
    }

    NavHost(navController = navController, startDestination = Route.DialogList) {
        composable<Route.DialogList> { entry ->
            val viewModel = koinViewModel<DialogListViewModel>()
            val state by viewModel.state.collectAsStateWithLifecycle()
            DialogListScreen(
                state = state,
                onOpenDialog = { entry.ifResumed { navController.navigate(Route.Chat(it)) } },
                onOpenSearch = { entry.ifResumed { navController.navigate(Route.People) } },
                onOpenProfile = { entry.ifResumed { navController.navigate(Route.Profile) } }
            )
        }

        composable<Route.Chat> { entry ->
            val dialogId = entry.toRoute<Route.Chat>().dialogId
            val viewModel = koinViewModel<ChatViewModel> { parametersOf(dialogId) }
            val state by viewModel.state.collectAsStateWithLifecycle()
            val back = { entry.ifResumed { navController.popBackStack() } }
            SwipeBackBox(onBack = back) {
                ChatScreen(
                    state = state,
                    onDraftChange = viewModel::onDraftChange,
                    onSend = viewModel::send,
                    onRetry = viewModel::retry,
                    onLoadOlder = viewModel::loadOlder,
                    onBack = back,
                    onDismissError = viewModel::dismissError,
                    onCall = viewModel::call
                )
            }
        }

        composable<Route.People> { entry ->
            val viewModel = koinViewModel<PeopleViewModel>()
            val state by viewModel.state.collectAsStateWithLifecycle()
            val back = { entry.ifResumed { navController.popBackStack() } }
            LaunchedEffect(viewModel) {
                viewModel.openedDialog.collect { dialogId ->
                    entry.ifResumed { navController.navigate(Route.Chat(dialogId)) }
                }
            }
            SwipeBackBox(onBack = back) {
                PeopleScreen(
                    state = state,
                    onTabChange = viewModel::selectTab,
                    onQueryChange = viewModel::onQueryChange,
                    onOpenChat = viewModel::openChat,
                    onAddContact = viewModel::addContact,
                    onRemoveContact = viewModel::removeContact,
                    onBack = back
                )
            }
        }

        composable<Route.Profile> { entry ->
            val viewModel = koinViewModel<ProfileViewModel>()
            val state by viewModel.state.collectAsStateWithLifecycle()
            val back = { entry.ifResumed { navController.popBackStack() } }
            SwipeBackBox(onBack = back) {
                ProfileScreen(
                    state = state,
                    onBack = back,
                    onRetry = viewModel::refresh,
                    onLogout = onLogout
                )
            }
        }
    }
}

private inline fun NavBackStackEntry.ifResumed(block: () -> Unit) {
    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) block()
}
