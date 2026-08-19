package com.relay.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.relay.auth.AuthState
import com.relay.auth.SessionManager
import com.relay.call.MicPermission
import com.relay.call.PlaceCallResult
import com.relay.model.Dialog
import com.relay.model.DialogSyncState
import com.relay.model.Message
import com.relay.presence.PeerPresence
import com.relay.protocol.nowEpochMillis
import com.relay.push.AppPresence
import com.relay.push.PushPermissionRequests
import com.relay.repository.ConnectionPhase
import com.relay.repository.CallRepository
import com.relay.repository.ConnectionStatus
import com.relay.repository.HISTORY_PAGE_SIZE
import com.relay.repository.MessageRepository
import com.relay.repository.PresenceRepository
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

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val dialogId: String,
    private val messages: MessageRepository,
    private val session: SessionManager,
    private val connection: ConnectionStatus,
    private val presence: AppPresence,
    private val permissionRequests: PushPermissionRequests,
    private val calls: CallRepository,
    private val peerPresence: PresenceRepository,
    private val mic: MicPermission,
    private val now: () -> Long = ::nowEpochMillis
) : ViewModel() {

    private val draft = MutableStateFlow("")
    private val loadingOlder = MutableStateFlow(false)
    private val visibleLimit = MutableStateFlow(INITIAL_VISIBLE_MESSAGES)
    private val sendError = MutableStateFlow<String?>(null)
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

    init {
        presence.onDialogOpened(dialogId)
        peerPresence.dialogOpened(dialogId)
        viewModelScope.launch {
            combine(stored, connection.phase, transient, livePresence) { chat, phase, extras, live ->
                buildState(chat, phase, extras, live)
            }.collect { built -> mutableState.value = built }
        }
        viewModelScope.launch {
            stored
                .map { chat -> chat.rows.firstOrNull()?.serverId }
                .distinctUntilChanged()
                .collect { messages.markRead(dialogId) }
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
        val peerId = mutableState.value.peerId ?: return
        viewModelScope.launch {
            if (!mic.ensureGranted()) {
                sendError.value = MIC_DENIED
                return@launch
            }
            val result = calls.call(peerId, dialogId)
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
        live: LivePresence
    ): ChatState {
        val selfId = (extras.auth as? AuthState.LoggedIn)?.userId
        val peerId = chat.dialog?.peerId
        val peerTyping = peerId != null && peerId in live.typingByDialog[dialogId].orEmpty()
        return ChatState(
            dialogId = dialogId,
            title = dialogTitleOf(chat.dialog?.title),
            subtitle = chatSubtitleOf(peerId?.let { live.presenceByUser[it] }, peerTyping, now()),
            isPeerTyping = peerTyping,
            peerId = peerId,
            messages = chat.rows.toMessageUi(selfId, now(), chat.dialog?.peerReadAt),
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
