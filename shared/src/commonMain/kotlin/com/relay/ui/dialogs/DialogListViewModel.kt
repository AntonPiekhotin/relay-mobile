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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
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
            session.state
                .map { auth -> (auth as? AuthState.LoggedIn)?.userId }
                .distinctUntilChanged()
                .flatMapLatest { selfId ->
                    combine(
                        dialogs.observeDialogSummaries(selfId),
                        connection.phase
                    ) { rows, phase ->
                        DialogListState(
                            dialogs = rows.toDialogUi(selfId, now()),
                            isLoaded = true,
                            connection = phase.toConnectionUi()
                        )
                    }
                }
                .collect { built -> mutableState.value = built }
        }
    }
}
