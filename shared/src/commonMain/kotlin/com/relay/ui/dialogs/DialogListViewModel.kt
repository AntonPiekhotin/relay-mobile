package com.relay.ui.dialogs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.relay.auth.AuthState
import com.relay.auth.SessionManager
import com.relay.protocol.nowEpochMillis
import com.relay.repository.ConnectionStatus
import com.relay.repository.MessageRepository
import com.relay.ui.state.DialogListState
import com.relay.ui.state.toConnectionUi
import com.relay.ui.state.toDialogUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class DialogListViewModel(
    private val dialogs: MessageRepository,
    private val session: SessionManager,
    private val connection: ConnectionStatus,
    private val now: () -> Long = ::nowEpochMillis
) : ViewModel() {

    private val mutableState = MutableStateFlow(DialogListState())
    val state: StateFlow<DialogListState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                dialogs.observeDialogSummaries(),
                connection.phase,
                session.state
            ) { rows, phase, auth ->
                DialogListState(
                    dialogs = rows.toDialogUi((auth as? AuthState.LoggedIn)?.userId, now()),
                    isLoaded = true,
                    connection = phase.toConnectionUi()
                )
            }.collect { built -> mutableState.value = built }
        }
    }
}
