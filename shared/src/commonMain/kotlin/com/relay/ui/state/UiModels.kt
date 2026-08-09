package com.relay.ui.state

import com.relay.model.MessageState

data class MessageUi(
    val localId: Long,
    val text: String,
    val isMine: Boolean,
    val timestamp: String,
    val status: MessageState,
    val failReason: String? = null,
    val daySeparator: String? = null
)

data class DialogUi(
    val id: String,
    val title: String,
    val preview: String,
    val timestamp: String,
    val unreadCount: Long,
    val previewIsMine: Boolean,
    val previewStatus: MessageState?
)

data class PersonUi(
    val id: String,
    val name: String,
    val email: String,
    val isContact: Boolean
)

sealed interface ConnectionUi {
    data object Unknown : ConnectionUi
    data object Live : ConnectionUi
    data object Reconnecting : ConnectionUi
}

data class ChatState(
    val dialogId: String = "",
    val title: String = "",
    val messages: List<MessageUi> = emptyList(),
    val isLoadingOlder: Boolean = false,
    val hasMoreHistory: Boolean = false,
    val draft: String = "",
    val connection: ConnectionUi = ConnectionUi.Unknown,
    val error: String? = null
)

data class DialogListState(
    val dialogs: List<DialogUi> = emptyList(),
    val isLoaded: Boolean = false,
    val connection: ConnectionUi = ConnectionUi.Unknown
)

enum class PeopleTab { CONTACTS, SEARCH }

data class PeopleState(
    val tab: PeopleTab = PeopleTab.CONTACTS,
    val query: String = "",
    val contacts: List<PersonUi> = emptyList(),
    val results: List<PersonUi> = emptyList(),
    val isSearching: Boolean = false,
    val isRefreshing: Boolean = false,
    val hasSearched: Boolean = false,
    val error: String? = null,
    val pendingIds: Set<String> = emptySet()
)
