package com.relay.ui.call

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun CallHost() {
    val viewModel = koinViewModel<CallViewModel>()
    val groupViewModel = koinViewModel<GroupCallViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val groupState by groupViewModel.state.collectAsStateWithLifecycle()
    if (!state.visible && !groupState.visible) return
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }
    if (state.visible) {
        CallScreen(
            state = state,
            onAccept = viewModel::accept,
            onDecline = viewModel::decline,
            onHangup = viewModel::hangup,
            onToggleMute = viewModel::toggleMute,
            onToggleSpeaker = viewModel::toggleSpeaker
        )
    } else {
        GroupCallScreen(
            state = groupState,
            onAccept = groupViewModel::accept,
            onDecline = groupViewModel::decline,
            onHangup = groupViewModel::hangup,
            onToggleMute = groupViewModel::toggleMute,
            onToggleSpeaker = groupViewModel::toggleSpeaker
        )
    }
}
