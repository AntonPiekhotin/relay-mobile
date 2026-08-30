package com.relay.ui.call

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.relay.call.MAX_GROUP_PARTICIPANTS
import com.relay.call.MicPermission
import com.relay.call.PlaceCallResult
import com.relay.repository.GroupCallRepository
import com.relay.repository.UserRepository
import com.relay.ui.state.GroupCallPickerState
import com.relay.ui.state.toPersonUi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val MIC_DENIED = "Microphone access is needed for calls"
private const val SELECTION_FULL = "A group call holds at most $MAX_GROUP_PARTICIPANTS people"

class GroupCallPickerViewModel(
    private val users: UserRepository,
    private val calls: GroupCallRepository,
    private val mic: MicPermission
) : ViewModel() {

    private val mutableState = MutableStateFlow(GroupCallPickerState())
    val state: StateFlow<GroupCallPickerState> = mutableState.asStateFlow()

    private val startedCalls = Channel<String>(Channel.BUFFERED)
    val started: Flow<String> = startedCalls.receiveAsFlow()

    init {
        viewModelScope.launch {
            users.observeContacts().collect { contacts ->
                mutableState.update { current ->
                    val ids = contacts.map { it.user.id }.toSet()
                    current.copy(
                        contacts = contacts.toPersonUi(),
                        selectedIds = current.selectedIds.intersect(ids)
                    )
                }
            }
        }
        viewModelScope.launch { users.refreshContacts() }
    }

    fun toggle(userId: String) {
        mutableState.update { current ->
            when {
                userId in current.selectedIds ->
                    current.copy(selectedIds = current.selectedIds - userId, error = null)
                current.selectedIds.size + 2 > MAX_GROUP_PARTICIPANTS ->
                    current.copy(error = SELECTION_FULL)
                else -> current.copy(selectedIds = current.selectedIds + userId, error = null)
            }
        }
    }

    fun start() {
        val selected = mutableState.value.selectedIds.toList()
        if (selected.isEmpty() || mutableState.value.isStarting) return
        mutableState.update { it.copy(isStarting = true, error = null) }
        viewModelScope.launch {
            if (!mic.ensureGranted()) {
                mutableState.update { it.copy(isStarting = false, error = MIC_DENIED) }
                return@launch
            }
            when (val result = calls.start(selected)) {
                is PlaceCallResult.Started -> {
                    mutableState.update { it.copy(isStarting = false, selectedIds = emptySet()) }
                    startedCalls.send(result.callId)
                }
                is PlaceCallResult.Rejected ->
                    mutableState.update { it.copy(isStarting = false, error = result.reason) }
            }
        }
    }
}
