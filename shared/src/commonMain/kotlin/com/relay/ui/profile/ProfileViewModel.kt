package com.relay.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.relay.repository.UserRepository
import com.relay.repository.UserResult
import com.relay.ui.state.ProfileState
import com.relay.ui.state.toProfileUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ProfileViewModel(
    private val users: UserRepository
) : ViewModel() {

    private val mutableState = MutableStateFlow(ProfileState())
    val state: StateFlow<ProfileState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (mutableState.value.isLoading) return
        mutableState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val result = users.profile()) {
                is UserResult.Success -> mutableState.update {
                    it.copy(profile = result.value.toProfileUi(), isLoading = false, error = null)
                }
                is UserResult.Failure -> mutableState.update {
                    it.copy(isLoading = false, error = result.message)
                }
            }
        }
    }
}
