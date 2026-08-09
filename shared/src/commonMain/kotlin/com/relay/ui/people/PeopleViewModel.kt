package com.relay.ui.people

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.relay.model.UserSearchResult
import com.relay.model.UserSummary
import com.relay.network.MIN_SEARCH_LENGTH
import com.relay.repository.UserRepository
import com.relay.repository.UserResult
import com.relay.ui.state.PeopleState
import com.relay.ui.state.PeopleTab
import com.relay.ui.state.searchResultsToPersonUi
import com.relay.ui.state.toPersonUi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

val SEARCH_DEBOUNCE: Duration = 300.milliseconds

class PeopleViewModel(
    private val users: UserRepository,
    private val debounce: Duration = SEARCH_DEBOUNCE
) : ViewModel() {

    private val mutableState = MutableStateFlow(PeopleState())
    val state: StateFlow<PeopleState> = mutableState.asStateFlow()

    private var searchJob: Job? = null
    private var contactIds: Set<String> = emptySet()
    private var lastFound: List<UserSearchResult> = emptyList()

    init {
        viewModelScope.launch {
            users.observeContacts().collect { contacts ->
                contactIds = contacts.map { it.user.id }.toSet()
                mutableState.update {
                    it.copy(
                        contacts = contacts.toPersonUi(),
                        results = lastFound.searchResultsToPersonUi(contactIds)
                    )
                }
            }
        }
        refreshContacts()
    }

    fun selectTab(tab: PeopleTab) {
        mutableState.update { it.copy(tab = tab, error = null) }
    }

    fun refreshContacts() {
        mutableState.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            val result = users.refreshContacts()
            mutableState.update {
                it.copy(
                    isRefreshing = false,
                    error = (result as? UserResult.Failure)?.message
                )
            }
        }
    }

    fun onQueryChange(value: String) {
        mutableState.update { it.copy(query = value, error = null) }
        searchJob?.cancel()
        if (value.trim().length < MIN_SEARCH_LENGTH) {
            lastFound = emptyList()
            mutableState.update { it.copy(results = emptyList(), isSearching = false, hasSearched = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(debounce)
            runSearch(value.trim())
        }
    }

    fun addContact(userId: String) {
        val person = findUser(userId) ?: return
        mutate(userId) { users.addContact(person) }
    }

    fun removeContact(userId: String) {
        mutate(userId) { users.removeContact(userId) }
    }

    private fun mutate(userId: String, action: suspend () -> UserResult<Unit>) {
        if (userId in mutableState.value.pendingIds) return
        mutableState.update { it.copy(pendingIds = it.pendingIds + userId, error = null) }
        viewModelScope.launch {
            val result = action()
            mutableState.update {
                it.copy(
                    pendingIds = it.pendingIds - userId,
                    error = (result as? UserResult.Failure)?.message
                )
            }
        }
    }

    private suspend fun runSearch(query: String) {
        mutableState.update { it.copy(isSearching = true) }
        when (val result = users.search(query, page = 0)) {
            is UserResult.Success -> {
                lastFound = result.value.results
                mutableState.update {
                    it.copy(
                        results = lastFound.searchResultsToPersonUi(contactIds),
                        isSearching = false,
                        hasSearched = true,
                        error = null
                    )
                }
            }
            is UserResult.Failure -> mutableState.update {
                it.copy(isSearching = false, hasSearched = true, error = result.message)
            }
        }
    }

    private fun findUser(userId: String): UserSummary? =
        lastFound.firstOrNull { it.user.id == userId }?.user
}
