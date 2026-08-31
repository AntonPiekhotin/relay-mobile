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
import com.relay.ui.home.HomeScreen
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
                popUpTo<Route.Home>()
            }
        }
    }

    NavHost(navController = navController, startDestination = Route.Home) {
        composable<Route.Home> { entry ->
            HomeScreen(
                onOpenDialog = { entry.ifResumed { navController.navigate(Route.Chat(it)) } },
                onLogout = onLogout
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
    }
}

private inline fun NavBackStackEntry.ifResumed(block: () -> Unit) {
    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) block()
}
