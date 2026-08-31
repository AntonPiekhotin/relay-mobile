package com.relay.ui.state

enum class MessageStatusUi { SENDING, SENT, READ, FAILED }

data class MessageUi(
    val localId: Long,
    val text: String,
    val isMine: Boolean,
    val timestamp: String,
    val status: MessageStatusUi,
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
    val previewStatus: MessageStatusUi?
)

data class PersonUi(
    val id: String,
    val name: String,
    val isContact: Boolean
)

data class ProfileUi(
    val name: String,
    val email: String,
    val memberSince: String?
)

data class ProfileState(
    val profile: ProfileUi? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

sealed interface ConnectionUi {
    data object Unknown : ConnectionUi
    data object Live : ConnectionUi
    data object Reconnecting : ConnectionUi
}

data class ChatState(
    val dialogId: String = "",
    val title: String = "",
    val subtitle: String? = null,
    val isPeerTyping: Boolean = false,
    val peerId: String? = null,
    val messages: List<MessageUi> = emptyList(),
    val isLoadingOlder: Boolean = false,
    val hasMoreHistory: Boolean = false,
    val draft: String = "",
    val connection: ConnectionUi = ConnectionUi.Unknown,
    val error: String? = null
)

enum class CallActionsUi { INCOMING, IN_PROGRESS, ENDED }

data class CallUiState(
    val visible: Boolean = false,
    val peerName: String = "",
    val status: String = "",
    val answeredAt: Long? = null,
    val actions: CallActionsUi = CallActionsUi.IN_PROGRESS,
    val muted: Boolean = false,
    val speakerOn: Boolean = false,
    val failure: String? = null
)

data class GroupCallParticipantUi(
    val userId: String,
    val name: String,
    val stateLabel: String,
    val isJoined: Boolean
)

data class GroupCallUiState(
    val visible: Boolean = false,
    val title: String = "",
    val status: String = "",
    val answeredAt: Long? = null,
    val actions: CallActionsUi = CallActionsUi.IN_PROGRESS,
    val participants: List<GroupCallParticipantUi> = emptyList(),
    val muted: Boolean = false,
    val speakerOn: Boolean = false,
    val failure: String? = null
)

data class GroupCallPickerState(
    val contacts: List<PersonUi> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val isStarting: Boolean = false,
    val error: String? = null
) {
    val canStart: Boolean get() = selectedIds.isNotEmpty() && !isStarting
}

data class DialogListState(
    val dialogs: List<DialogUi> = emptyList(),
    val query: String = "",
    val unreadTotal: Long = 0,
    val isLoaded: Boolean = false,
    val connection: ConnectionUi = ConnectionUi.Unknown
)

data class PeopleState(
    val query: String = "",
    val contacts: List<PersonUi> = emptyList(),
    val results: List<PersonUi> = emptyList(),
    val isSearching: Boolean = false,
    val isRefreshing: Boolean = false,
    val hasSearched: Boolean = false,
    val error: String? = null,
    val pendingIds: Set<String> = emptySet()
)

data class CallLogUi(
    val id: String,
    val peerId: String?,
    val dialogId: String?,
    val peerName: String,
    val isMissed: Boolean,
    val subtitle: String,
    val timestamp: String
)

data class CallsState(
    val calls: List<CallLogUi> = emptyList(),
    val isLoading: Boolean = false,
    val isLoaded: Boolean = false,
    val error: String? = null
)
