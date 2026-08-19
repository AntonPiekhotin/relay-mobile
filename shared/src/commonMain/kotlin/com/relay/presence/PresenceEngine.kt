package com.relay.presence

import com.relay.network.ConnectionState
import com.relay.network.SocketClient
import com.relay.protocol.ErrorCode
import com.relay.protocol.InboundFrame
import com.relay.protocol.PresenceStatusWire
import com.relay.protocol.isoToEpochMillisOrNull
import com.relay.protocol.newFrameId
import com.relay.protocol.nowEpochMillis
import com.relay.protocol.presenceSubscribeFrame
import com.relay.protocol.presenceUnsubscribeFrame
import com.relay.protocol.typingStartFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

const val TYPING_EXPIRY_MILLIS = 5_000L
const val TYPING_THROTTLE_MILLIS = 3_000L
const val SUBSCRIBE_RETRY_MILLIS = 3_000L

data class PeerPresence(
    val online: Boolean,
    val lastSeenAt: Long?
)

class PresenceEngine(
    private val socket: SocketClient,
    private val scope: CoroutineScope,
    private val now: () -> Long = ::nowEpochMillis
) {
    private val mutablePresence = MutableStateFlow<Map<String, PeerPresence>>(emptyMap())
    val presence: StateFlow<Map<String, PeerPresence>> = mutablePresence.asStateFlow()

    private val mutableTyping = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val typing: StateFlow<Map<String, Set<String>>> = mutableTyping.asStateFlow()

    private var events: Channel<Event>? = null
    private val jobs = mutableListOf<Job>()

    private var openDialogId: String? = null
    private var pendingSubscribeId: String? = null
    private var subscribeRetryJob: Job? = null
    private var lastTypingSentAt: Long? = null
    private val typingExpiryJobs = mutableMapOf<Pair<String, String>, Job>()

    private sealed interface Event {
        data class Frame(val frame: InboundFrame) : Event
        data class Connection(val connection: ConnectionState) : Event
        data class DialogOpened(val dialogId: String) : Event
        data class DialogClosed(val dialogId: String) : Event
        data class TypingActivity(val dialogId: String) : Event
        data class TypingExpired(val dialogId: String, val userId: String) : Event
        data class RetrySubscribe(val dialogId: String) : Event
    }

    fun start() {
        if (jobs.any { it.isActive }) return
        jobs.clear()
        val channel = Channel<Event>(Channel.UNLIMITED)
        events = channel
        jobs += scope.launch { socket.frames.collect { channel.send(Event.Frame(it)) } }
        jobs += scope.launch { socket.state.collect { channel.send(Event.Connection(it)) } }
        jobs += scope.launch { for (event in channel) handle(event) }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        events?.cancel()
        events = null
        openDialogId = null
        pendingSubscribeId = null
        lastTypingSentAt = null
        cancelSubscribeRetry()
        clearPeerState()
    }

    fun dialogOpened(dialogId: String) {
        events?.trySend(Event.DialogOpened(dialogId))
    }

    fun dialogClosed(dialogId: String) {
        events?.trySend(Event.DialogClosed(dialogId))
    }

    fun typingActivity(dialogId: String) {
        events?.trySend(Event.TypingActivity(dialogId))
    }

    private suspend fun handle(event: Event) {
        when (event) {
            is Event.Frame -> onFrame(event.frame)
            is Event.Connection -> onConnection(event.connection)
            is Event.DialogOpened -> onDialogOpened(event.dialogId)
            is Event.DialogClosed -> onDialogClosed(event.dialogId)
            is Event.TypingActivity -> onTypingActivity(event.dialogId)
            is Event.TypingExpired -> onTypingExpired(event.dialogId, event.userId)
            is Event.RetrySubscribe -> onRetrySubscribe(event.dialogId)
        }
    }

    private suspend fun onFrame(frame: InboundFrame) {
        when (frame) {
            is InboundFrame.PresenceUpdate -> onPresenceUpdate(frame)
            is InboundFrame.TypingStart -> onTypingStart(frame)
            is InboundFrame.Error -> onError(frame)
            else -> Unit
        }
    }

    private fun onPresenceUpdate(frame: InboundFrame.PresenceUpdate) {
        if (openDialogId == null) return
        val online = frame.payload.status == PresenceStatusWire.ONLINE
        mutablePresence.value = mutablePresence.value + (
            frame.payload.userId to PeerPresence(
                online = online,
                lastSeenAt = if (online) null else frame.payload.lastSeen?.let { isoToEpochMillisOrNull(it) }
            )
            )
    }

    private fun onTypingStart(frame: InboundFrame.TypingStart) {
        val dialogId = frame.payload.dialogId
        if (dialogId != openDialogId) return
        val userId = frame.payload.userId
        val current = mutableTyping.value[dialogId].orEmpty()
        mutableTyping.value = mutableTyping.value + (dialogId to (current + userId))
        scheduleTypingExpiry(dialogId, userId)
    }

    private fun onError(frame: InboundFrame.Error) {
        val refId = frame.payload.refId ?: return
        if (refId != pendingSubscribeId) return
        pendingSubscribeId = null
        val dialogId = openDialogId ?: return
        if (frame.payload.code in ErrorCode.retryable) scheduleSubscribeRetry(dialogId)
    }

    private suspend fun onConnection(connection: ConnectionState) {
        if (connection is ConnectionState.Connected) {
            openDialogId?.let { subscribe(it) }
            return
        }
        cancelSubscribeRetry()
        pendingSubscribeId = null
        clearPeerState()
    }

    private suspend fun onDialogOpened(dialogId: String) {
        if (openDialogId != dialogId) {
            clearPeerState()
            lastTypingSentAt = null
        }
        openDialogId = dialogId
        cancelSubscribeRetry()
        if (socket.state.value is ConnectionState.Connected) subscribe(dialogId)
    }

    private suspend fun onDialogClosed(dialogId: String) {
        if (openDialogId != dialogId) return
        openDialogId = null
        pendingSubscribeId = null
        lastTypingSentAt = null
        cancelSubscribeRetry()
        clearPeerState()
        if (socket.state.value is ConnectionState.Connected) {
            socket.sendFrame(presenceUnsubscribeFrame(dialogId))
        }
    }

    private suspend fun onTypingActivity(dialogId: String) {
        if (dialogId != openDialogId) return
        if (socket.state.value !is ConnectionState.Connected) return
        val lastSent = lastTypingSentAt
        if (lastSent != null && now() - lastSent < TYPING_THROTTLE_MILLIS) return
        if (socket.sendFrame(typingStartFrame(dialogId))) lastTypingSentAt = now()
    }

    private fun onTypingExpired(dialogId: String, userId: String) {
        typingExpiryJobs.remove(dialogId to userId)
        val remaining = mutableTyping.value[dialogId].orEmpty() - userId
        mutableTyping.value =
            if (remaining.isEmpty()) {
                mutableTyping.value - dialogId
            } else {
                mutableTyping.value + (dialogId to remaining)
            }
    }

    private suspend fun onRetrySubscribe(dialogId: String) {
        if (openDialogId != dialogId) return
        if (socket.state.value !is ConnectionState.Connected) return
        subscribe(dialogId)
    }

    private suspend fun subscribe(dialogId: String) {
        val frameId = newFrameId()
        pendingSubscribeId = frameId
        socket.sendFrame(presenceSubscribeFrame(dialogId, frameId))
    }

    private fun scheduleTypingExpiry(dialogId: String, userId: String) {
        val channel = events ?: return
        val key = dialogId to userId
        typingExpiryJobs[key]?.cancel()
        typingExpiryJobs[key] = scope.launch {
            delay(TYPING_EXPIRY_MILLIS)
            channel.trySend(Event.TypingExpired(dialogId, userId))
        }
    }

    private fun scheduleSubscribeRetry(dialogId: String) {
        val channel = events ?: return
        subscribeRetryJob?.cancel()
        subscribeRetryJob = scope.launch {
            delay(SUBSCRIBE_RETRY_MILLIS)
            channel.trySend(Event.RetrySubscribe(dialogId))
        }
    }

    private fun cancelSubscribeRetry() {
        subscribeRetryJob?.cancel()
        subscribeRetryJob = null
    }

    private fun clearPeerState() {
        typingExpiryJobs.values.forEach { it.cancel() }
        typingExpiryJobs.clear()
        mutablePresence.value = emptyMap()
        mutableTyping.value = emptyMap()
    }
}
