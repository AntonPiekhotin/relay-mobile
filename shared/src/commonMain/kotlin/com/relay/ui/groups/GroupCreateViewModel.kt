package com.relay.ui.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.relay.network.MIN_SEARCH_LENGTH
import com.relay.protocol.newFrameId
import com.relay.repository.MessageRepository
import com.relay.repository.OpenDialogResult
import com.relay.repository.UserRepository
import com.relay.repository.UserResult
import com.relay.ui.people.SEARCH_DEBOUNCE
import com.relay.ui.state.GroupCreateState
import com.relay.ui.state.PersonUi
import com.relay.ui.state.searchResultsToPersonUi
import com.relay.ui.state.toPersonUi
import kotlin.time.Duration
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val MAX_GROUP_MEMBERS = 50

private const val GROUP_FULL = "A group holds at most $MAX_GROUP_MEMBERS people"
private const val MAX_TITLE_LENGTH = 128
private const val NAMES_IN_DEFAULT_TITLE = 3

fun defaultGroupTitle(members: List<PersonUi>): String {
    val named = members.take(NAMES_IN_DEFAULT_TITLE).joinToString { it.name }
    val rest = members.size - NAMES_IN_DEFAULT_TITLE
    val full = if (rest > 0) "$named +$rest" else named
    return full.take(MAX_TITLE_LENGTH)
}

class GroupCreateViewModel(
    private val users: UserRepository,
    private val messages: MessageRepository,
    private val debounce: Duration = SEARCH_DEBOUNCE
) : ViewModel() {

    private val mutableState = MutableStateFlow(GroupCreateState())
    val state: StateFlow<GroupCreateState> = mutableState.asStateFlow()

    private val createdDialogs = Channel<String>(Channel.BUFFERED)
    val created: Flow<String> = createdDialogs.receiveAsFlow()

    private var dialogId = newFrameId()
    private var searchJob: Job? = null
    private var contactIds: Set<String> = emptySet()

    init {
        viewModelScope.launch {
            users.observeContacts().collect { contacts ->
                contactIds = contacts.map { it.user.id }.toSet()
                mutableState.update { it.copy(contacts = contacts.toPersonUi()) }
            }
        }
        viewModelScope.launch { users.refreshContacts() }
    }

    fun onTitleChange(value: String) {
        mutableState.update { it.copy(title = value, error = null) }
    }

    fun onQueryChange(value: String) {
        mutableState.update { it.copy(query = value, error = null) }
        searchJob?.cancel()
        if (value.trim().length < MIN_SEARCH_LENGTH) {
            mutableState.update { it.copy(results = emptyList(), isSearching = false, hasSearched = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(debounce)
            runSearch(value.trim())
        }
    }

    fun toggle(person: PersonUi) {
        mutableState.update { current ->
            when {
                current.selected.any { it.id == person.id } ->
                    current.copy(selected = current.selected.filterNot { it.id == person.id }, error = null)
                current.selected.size + 2 > MAX_GROUP_MEMBERS ->
                    current.copy(error = GROUP_FULL)
                else -> current.copy(selected = current.selected + person, error = null)
            }
        }
    }

    fun create() {
        val current = mutableState.value
        if (!current.canCreate) return
        mutableState.update { it.copy(isCreating = true, error = null) }
        viewModelScope.launch {
            val result = messages.createGroupDialog(
                dialogId = dialogId,
                title = current.title.trim().take(MAX_TITLE_LENGTH)
                    .ifEmpty { defaultGroupTitle(current.selected) },
                memberIds = current.selected.map { it.id }
            )
            when (result) {
                is OpenDialogResult.Opened -> {
                    dialogId = newFrameId()
                    mutableState.value = GroupCreateState(contacts = mutableState.value.contacts)
                    createdDialogs.send(result.dialogId)
                }
                is OpenDialogResult.Failed ->
                    mutableState.update { it.copy(isCreating = false, error = result.message) }
            }
        }
    }

    private suspend fun runSearch(query: String) {
        mutableState.update { it.copy(isSearching = true) }
        when (val result = users.search(query, page = 0)) {
            is UserResult.Success -> mutableState.update {
                it.copy(
                    results = result.value.results.searchResultsToPersonUi(contactIds),
                    isSearching = false,
                    hasSearched = true,
                    error = null
                )
            }
            is UserResult.Failure -> mutableState.update {
                it.copy(isSearching = false, hasSearched = true, error = result.message)
            }
        }
    }
}
