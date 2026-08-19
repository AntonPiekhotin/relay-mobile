package com.relay.ui.calls

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.relay.call.PlaceCallResult
import com.relay.network.CallApi
import com.relay.network.CallApiResult
import com.relay.protocol.nowEpochMillis
import com.relay.repository.CallRepository
import com.relay.repository.MessageRepository
import com.relay.repository.UserRepository
import com.relay.ui.state.CallLogUi
import com.relay.ui.state.CallsState
import com.relay.ui.state.toCallLogUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val CALL_HISTORY_PAGE_SIZE = 50

class CallsViewModel(
    private val callApi: CallApi,
    private val calls: CallRepository,
    private val messages: MessageRepository,
    private val users: UserRepository,
    private val now: () -> Long = ::nowEpochMillis
) : ViewModel() {

    private val mutableState = MutableStateFlow(CallsState())
    val state: StateFlow<CallsState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (mutableState.value.isLoading) return
        mutableState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val result = callApi.history(before = null, limit = CALL_HISTORY_PAGE_SIZE)) {
                is CallApiResult.Success -> {
                    val names = peerNames()
                    val nowMillis = now()
                    mutableState.update {
                        it.copy(
                            calls = result.value.calls.map { entry -> entry.toCallLogUi(names, nowMillis) },
                            isLoading = false,
                            isLoaded = true
                        )
                    }
                }
                is CallApiResult.Failure -> mutableState.update {
                    it.copy(isLoading = false, isLoaded = true, error = result.reason)
                }
            }
        }
    }

    fun callBack(call: CallLogUi) {
        val peerId = call.peerId ?: return
        viewModelScope.launch {
            val result = calls.call(peerId, call.dialogId)
            if (result is PlaceCallResult.Rejected) {
                mutableState.update { it.copy(error = result.reason) }
            }
        }
    }

    private suspend fun peerNames(): Map<String, String> {
        val fromDialogs = messages.observeDialogs().first()
            .mapNotNull { dialog ->
                val peerId = dialog.peerId ?: return@mapNotNull null
                val title = dialog.title?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                peerId to title
            }
            .toMap()
        val fromContacts = users.observeContacts().first()
            .associate { it.user.id to it.user.displayName }
        return fromDialogs + fromContacts
    }
}
