package com.relay.sync

import com.relay.db.MessageStore
import com.relay.network.ConnectionState
import com.relay.network.MessageApi
import com.relay.network.MessageApiResult
import com.relay.network.SocketClient
import com.relay.network.backoffDelayMillis
import com.relay.protocol.ErrorCode
import com.relay.protocol.ErrorPayload
import com.relay.protocol.InboundFrame
import com.relay.protocol.MessageReadReceiptPayload
import com.relay.protocol.isoToEpochMillisOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val CATCHUP_PAGE_SIZE = 100

sealed interface SyncEngineState {
    data object Disconnected : SyncEngineState
    data object Connecting : SyncEngineState
    data object CatchingUp : SyncEngineState
    data object Live : SyncEngineState
}

class SyncEngine(
    private val store: MessageStore,
    private val socket: SocketClient,
    private val api: MessageApi,
    private val outbox: Outbox,
    private val readReceipts: ReadReceipts,
    private val scope: CoroutineScope,
    private val authenticatedUserId: () -> String? = { null }
) {
    private val mutableState = MutableStateFlow<SyncEngineState>(SyncEngineState.Disconnected)
    val state: StateFlow<SyncEngineState> = mutableState.asStateFlow()

    private var events: Channel<Event>? = null
    private val jobs = mutableListOf<Job>()
    private var catchUpRetryJob: Job? = null
    private var catchUpRetries = 0

    private sealed interface Event {
        data class Frame(val frame: InboundFrame) : Event
        data class Connection(val connection: ConnectionState) : Event
        data object CatchUpRetry : Event
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
        cancelCatchUpRetry()
        events?.cancel()
        events = null
        mutableState.value = SyncEngineState.Disconnected
    }

    private suspend fun handle(event: Event) {
        when (event) {
            is Event.Connection -> onConnectionChange(event.connection)
            is Event.Frame -> onFrame(event.frame)
            is Event.CatchUpRetry -> onCatchUpRetry()
        }
    }

    private suspend fun onConnectionChange(connection: ConnectionState) {
        when (connection) {
            is ConnectionState.Connected -> {
                cancelCatchUpRetry()
                mutableState.value = SyncEngineState.CatchingUp
                val complete = catchUp()
                mutableState.value =
                    if (socket.state.value is ConnectionState.Connected) {
                        SyncEngineState.Live
                    } else {
                        SyncEngineState.Disconnected
                    }
                outbox.wake()
                readReceipts.flush()
                if (!complete) scheduleCatchUpRetry()
            }
            is ConnectionState.Connecting -> mutableState.value = SyncEngineState.Connecting
            is ConnectionState.Disconnected -> {
                cancelCatchUpRetry()
                mutableState.value = SyncEngineState.Disconnected
            }
        }
    }

    private suspend fun onCatchUpRetry() {
        if (socket.state.value !is ConnectionState.Connected) return
        val complete = catchUp()
        outbox.wake()
        if (!complete) scheduleCatchUpRetry()
    }

    private fun scheduleCatchUpRetry() {
        val delayMillis = backoffDelayMillis(catchUpRetries)
        catchUpRetries++
        val channel = events ?: return
        catchUpRetryJob = scope.launch {
            delay(delayMillis)
            channel.trySend(Event.CatchUpRetry)
        }
    }

    private fun cancelCatchUpRetry() {
        catchUpRetryJob?.cancel()
        catchUpRetryJob = null
        catchUpRetries = 0
    }

    private suspend fun onFrame(frame: InboundFrame) {
        when (frame) {
            is InboundFrame.Ack -> store.applyAck(
                clientMsgId = frame.payload.clientMsgId,
                serverId = frame.payload.messageId,
                createdAt = isoToEpochMillisOrNull(frame.payload.createdAt)
            )
            is InboundFrame.MessageNew -> store.applyWireMessage(frame.payload.toWireMessage(), selfId())
            is InboundFrame.MessageRead -> onReadReceipt(frame.payload)
            is InboundFrame.Error -> onError(frame.payload)
            else -> Unit
        }
    }

    private suspend fun onReadReceipt(payload: MessageReadReceiptPayload) {
        val readAt = isoToEpochMillisOrNull(payload.readAt) ?: return
        store.applyReadReceipt(
            dialogId = payload.dialogId,
            userId = payload.userId,
            upToMessageId = payload.upToMessageId,
            readAt = readAt,
            selfId = selfId()
        )
    }

    private suspend fun onError(payload: ErrorPayload) {
        val refId = payload.refId ?: return
        if (payload.code in ErrorCode.retryable) return
        store.failPendingByClientMsgId(refId, payload.code)
    }

    suspend fun wakeAndCatchUp(dialogId: String? = null): Boolean =
        if (dialogId == null) catchUp() else catchUpDialog(dialogId)

    private fun selfId(): String? =
        (socket.state.value as? ConnectionState.Connected)?.userId ?: authenticatedUserId()

    private suspend fun catchUp(): Boolean {
        var complete = true
        val selfId = selfId()
        when (val result = api.dialogs()) {
            is MessageApiResult.Success -> result.value.forEach { dialog ->
                store.upsertDialog(
                    id = dialog.dialogId,
                    type = dialog.type,
                    title = null,
                    lastMessageAt = dialog.lastMessageAt?.let { isoToEpochMillisOrNull(it) },
                    peerId = selfId?.let { self -> dialog.participantIds.firstOrNull { it != self } }
                )
            }
            else -> complete = false
        }
        for (dialogId in store.dialogIds()) {
            if (!catchUpDialog(dialogId)) complete = false
        }
        return complete
    }

    private suspend fun catchUpDialog(dialogId: String): Boolean {
        store.ensureSyncState(dialogId)
        val cursor = store.syncState(dialogId)?.newestSyncedId
        return if (cursor == null) {
            loadInitialPage(dialogId)
        } else {
            pageForward(dialogId, cursor)
        }
    }

    private suspend fun loadInitialPage(dialogId: String): Boolean {
        val result = api.messagesBefore(dialogId, before = null, limit = CATCHUP_PAGE_SIZE)
        if (result !is MessageApiResult.Success) return false
        val page = result.value
        page.forEach { store.applyWireMessage(it, selfId()) }
        store.updateNewestSynced(dialogId, page.firstOrNull()?.messageId)
        store.updateOldestLoaded(
            dialogId = dialogId,
            oldestLoadedId = page.lastOrNull()?.messageId,
            hasMore = page.size == CATCHUP_PAGE_SIZE
        )
        return true
    }

    private suspend fun pageForward(dialogId: String, cursor: String): Boolean {
        var after = cursor
        while (true) {
            val result = api.messagesAfter(dialogId, after, CATCHUP_PAGE_SIZE)
            if (result !is MessageApiResult.Success) return false
            val page = result.value
            page.forEach { store.applyWireMessage(it, selfId()) }
            after = page.lastOrNull()?.messageId ?: return true
            store.updateNewestSynced(dialogId, after)
            if (page.size < CATCHUP_PAGE_SIZE) return true
        }
    }
}
