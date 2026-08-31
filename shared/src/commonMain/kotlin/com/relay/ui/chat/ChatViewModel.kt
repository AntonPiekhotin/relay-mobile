package com.relay.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.relay.auth.AuthState
import com.relay.auth.SessionManager
import com.relay.call.MicPermission
import com.relay.call.PlaceCallResult
import com.relay.model.Dialog
import com.relay.model.DialogSyncState
import com.relay.model.DialogType
import com.relay.model.Message
import com.relay.presence.PeerPresence
import com.relay.protocol.nowEpochMillis
import com.relay.push.AppPresence
import com.relay.push.PushPermissionRequests
import com.relay.repository.ConnectionPhase
import com.relay.repository.CallRepository
import com.relay.repository.ConnectionStatus
import com.relay.repository.GroupCallRepository
import com.relay.repository.HISTORY_PAGE_SIZE
import com.relay.repository.MessageRepository
import com.relay.repository.PresenceRepository
import com.relay.repository.UserRepository
import com.relay.repository.UserResult
import com.relay.ui.state.ChatState
import com.relay.ui.state.chatSubtitleOf
import com.relay.ui.state.dialogTitleOf
import com.relay.ui.state.toConnectionUi
import com.relay.ui.state.toMessageUi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

const val INITIAL_VISIBLE_MESSAGES = 100L

private const val SEND_FAILED = "Not signed in — your message was not sent"
private const val MIC_DENIED = "Microphone access is needed for calls"
private const val MEMBERS_UNAVAILABLE = "Could not load the group members for this call"

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val dialogId: String,
    private val messages: MessageRepository,
    private val session: SessionManager,
    private val connection: ConnectionStatus,
    private val presence: AppPresence,
    private val permissionRequests: PushPermissionRequests,
    private val calls: CallRepository,
    private val groupCalls: GroupCallRepository,
    private val users: UserRepository,
    private val peerPresence: PresenceRepository,
    private val mic: MicPermission,
    private val now: () -> Long = ::nowEpochMillis
) : ViewModel() {

    private val draft = MutableStateFlow("")
    private val loadingOlder = MutableStateFlow(false)
    private val visibleLimit = MutableStateFlow(INITIAL_VISIBLE_MESSAGES)
    private val sendError = MutableStateFlow<String?>(null)
    private val memberIds = MutableStateFlow<List<String>?>(null)
    private val memberNames = MutableStateFlow<Map<String, String>>(emptyMap())
    private val resolvingNames = mutableSetOf<String>()
    private var membersRequested = false
    private val mutableState = MutableStateFlow(ChatState(dialogId = dialogId))
    val state: StateFlow<ChatState> = mutableState.asStateFlow()

    private val stored = combine(
        visibleLimit.flatMapLatest { limit -> messages.observeMessages(dialogId, limit) },
        messages.observeDialog(dialogId),
        messages.observeSyncState(dialogId)
    ) { rows, dialog, syncState -> StoredChat(rows, dialog, syncState) }

    private val transient = combine(
        draft,
        loadingOlder,
        sendError,
        session.state
    ) { currentDraft, loading, error, auth -> Transient(currentDraft, loading, error, auth) }

    private val livePresence = combine(
        peerPresence.presence,
        peerPresence.typing
    ) { presenceByUser, typingByDialog -> LivePresence(presenceByUser, typingByDialog) }

    private val groupInfo = combine(
        memberIds,
        memberNames
    ) { ids, names -> GroupInfo(ids, names) }

    init {
        presence.onDialogOpened(dialogId)
        peerPresence.dialogOpened(dialogId)
        viewModelScope.launch {
            combine(
                stored,
                connection.phase,
                transient,
                livePresence,
                groupInfo
            ) { chat, phase, extras, live, group ->
                buildState(chat, phase, extras, live, group)
            }.collect { built -> mutableState.value = built }
        }
        viewModelScope.launch {
            stored
                .map { chat -> chat.rows.firstOrNull()?.serverId }
                .distinctUntilChanged()
                .collect { messages.markRead(dialogId) }
        }
        viewModelScope.launch {
            stored.collect { chat ->
                if (chat.dialog?.type == DialogType.GROUP) {
                    fetchMembersOnce()
                    resolveNames(chat.rows)
                }
            }
        }
    }

    private fun fetchMembersOnce() {
        if (membersRequested) return
        membersRequested = true
        viewModelScope.launch {
            val members = messages.dialogMembers(dialogId)
            if (members != null) {
                memberIds.value = members
            } else {
                membersRequested = false
            }
        }
    }

    private fun resolveNames(rows: List<Message>) {
        val selfId = (session.state.value as? AuthState.LoggedIn)?.userId
        val wanted = rows
            .flatMap { listOfNotNull(it.senderId, it.targetUserId) }
            .toSet()
            .filter { it != selfId && it !in memberNames.value && it !in resolvingNames }
        if (wanted.isEmpty()) return
        resolvingNames += wanted
        wanted.forEach { userId ->
            viewModelScope.launch {
                when (val result = users.lookup(userId)) {
                    is UserResult.Success ->
                        memberNames.value = memberNames.value + (userId to result.value.displayName)
                    is UserResult.Failure -> resolvingNames -= userId
                }
            }
        }
    }

    fun onDraftChange(value: String) {
        if (value.isNotBlank() && value != draft.value) peerPresence.typingActivity(dialogId)
        draft.value = value
        sendError.value = null
    }

    fun dismissError() {
        sendError.value = null
    }

    fun send() {
        val text = draft.value
        if (text.isBlank()) return
        viewModelScope.launch {
            if (messages.send(dialogId, text)) {
                sendError.value = null
                if (draft.value == text) draft.value = ""
                permissionRequests.request()
            } else {
                sendError.value = SEND_FAILED
            }
        }
    }

    fun call() {
        val current = mutableState.value
        when {
            current.peerId != null -> callPeer(current.peerId)
            current.isGroup -> callGroup()
        }
    }

    private fun callPeer(peerId: String) {
        viewModelScope.launch {
            if (!mic.ensureGranted()) {
                sendError.value = MIC_DENIED
                return@launch
            }
            val result = calls.call(peerId, dialogId)
            if (result is PlaceCallResult.Rejected) sendError.value = result.reason
        }
    }

    private fun callGroup() {
        viewModelScope.launch {
            if (!mic.ensureGranted()) {
                sendError.value = MIC_DENIED
                return@launch
            }
            val selfId = (session.state.value as? AuthState.LoggedIn)?.userId
            val members = memberIds.value ?: messages.dialogMembers(dialogId)?.also {
                memberIds.value = it
            }
            val invitees = members?.filter { it != selfId }.orEmpty()
            if (invitees.isEmpty()) {
                sendError.value = MEMBERS_UNAVAILABLE
                return@launch
            }
            val result = groupCalls.start(invitees)
            if (result is PlaceCallResult.Rejected) sendError.value = result.reason
        }
    }

    override fun onCleared() {
        presence.onDialogClosed(dialogId)
        peerPresence.dialogClosed(dialogId)
        super.onCleared()
    }

    fun retry(localId: Long) {
        viewModelScope.launch { messages.retry(localId) }
    }

    fun loadOlder() {
        if (loadingOlder.value) return
        loadingOlder.value = true
        viewModelScope.launch {
            try {
                if (messages.storedMessageCount(dialogId) <= mutableState.value.messages.size) {
                    messages.loadOlder(dialogId)
                }
                visibleLimit.value += HISTORY_PAGE_SIZE
            } finally {
                loadingOlder.value = false
            }
        }
    }

    private fun buildState(
        chat: StoredChat,
        phase: ConnectionPhase,
        extras: Transient,
        live: LivePresence,
        group: GroupInfo
    ): ChatState {
        val selfId = (extras.auth as? AuthState.LoggedIn)?.userId
        val isGroup = chat.dialog?.type == DialogType.GROUP
        val peerId = chat.dialog?.peerId?.takeUnless { isGroup }
        val peerTyping = peerId != null && peerId in live.typingByDialog[dialogId].orEmpty()
        val subtitle = if (isGroup) {
            group.memberIds?.size?.let { count -> "$count members" }
        } else {
            chatSubtitleOf(peerId?.let { live.presenceByUser[it] }, peerTyping, now())
        }
        return ChatState(
            dialogId = dialogId,
            title = dialogTitleOf(chat.dialog?.title),
            subtitle = subtitle,
            isPeerTyping = peerTyping,
            peerId = peerId,
            isGroup = isGroup,
            messages = chat.rows.toMessageUi(
                selfId,
                now(),
                chat.dialog?.peerReadAt,
                isGroup,
                group.names
            ),
            isLoadingOlder = extras.loading,
            hasMoreHistory = chat.syncState?.hasMoreHistory ?: false,
            draft = extras.draft,
            connection = phase.toConnectionUi(),
            error = extras.error
        )
    }
}

private data class StoredChat(
    val rows: List<Message>,
    val dialog: Dialog?,
    val syncState: DialogSyncState?
)

private data class Transient(
    val draft: String,
    val loading: Boolean,
    val error: String?,
    val auth: AuthState
)

private data class LivePresence(
    val presenceByUser: Map<String, PeerPresence>,
    val typingByDialog: Map<String, Set<String>>
)

private data class GroupInfo(
    val memberIds: List<String>?,
    val names: Map<String, String>
)
