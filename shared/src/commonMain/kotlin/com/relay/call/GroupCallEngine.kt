package com.relay.call

import com.relay.network.ConnectionState
import com.relay.network.GroupCallApi
import com.relay.network.GroupCallApiResult
import com.relay.network.GroupCallStateResponse
import com.relay.network.SocketClient
import com.relay.protocol.CallEndReason
import com.relay.protocol.CallSignal
import com.relay.protocol.CallStatusWire
import com.relay.protocol.GroupParticipantState
import com.relay.protocol.InboundFrame
import com.relay.protocol.MEDIA_AUDIO
import com.relay.protocol.isoToEpochMillisOrNull
import com.relay.protocol.newFrameId
import com.relay.protocol.nowEpochMillis
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

const val MAX_GROUP_PARTICIPANTS = 16

private const val BUSY_HERE = "busy"
private const val MEDIA_LOST = "The media connection was lost"
private const val ALREADY_IN_CALL = "You are already in a call"
private const val NOT_CONNECTED = "No connection to the server"
private const val NOBODY_TO_CALL = "Pick at least one person to call"
private const val TOO_MANY = "A group call holds at most $MAX_GROUP_PARTICIPANTS people"

class GroupCallEngine(
    private val socket: SocketClient,
    private val api: GroupCallApi,
    private val sfuFactory: SfuClientFactory,
    private val scope: CoroutineScope,
    private val otherCallActive: () -> Boolean = { false },
    private val now: () -> Long = ::nowEpochMillis
) {
    private val mutableSession = MutableStateFlow<GroupCallSession?>(null)
    val session: StateFlow<GroupCallSession?> = mutableSession.asStateFlow()

    val hasLiveSession: Boolean get() = mutableSession.value?.isSettled == false

    private var events: Channel<Event>? = null
    private val jobs = mutableListOf<Job>()

    private var sfu: SfuClient? = null
    private var ringTimeoutJob: Job? = null
    private var clearEndedJob: Job? = null

    private sealed interface Event {
        data class Frame(val frame: InboundFrame) : Event
        data class Connection(val connection: ConnectionState) : Event
        data class Start(
            val inviteeIds: List<String>,
            val result: CompletableDeferred<PlaceCallResult>
        ) : Event

        data class Created(val callId: String, val result: GroupCallApiResult<GroupCallStateResponse>) : Event
        data class JoinResult(val callId: String, val result: GroupCallApiResult<GroupCallStateResponse>) : Event
        data class Described(val callId: String, val result: GroupCallApiResult<GroupCallStateResponse>) : Event
        data class IncomingPush(
            val callId: String,
            val callerId: String,
            val media: String,
            val ringExpiresAt: Long?
        ) : Event

        data object Accept : Event
        data object Decline : Event
        data object Leave : Event
        data class SetMuted(val muted: Boolean) : Event
        data class SetSpeaker(val enabled: Boolean) : Event
        data class SfuState(val callId: String, val state: SfuConnectionState) : Event
        data class SfuFailure(val callId: String, val reason: String) : Event
        data class RingTimeout(val callId: String) : Event
        data class ClearEnded(val callId: String) : Event
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
        resetCallState()
        events?.cancel()
        events = null
        mutableSession.value = null
    }

    suspend fun place(inviteeIds: List<String>): PlaceCallResult {
        val channel = events ?: return PlaceCallResult.Rejected(NOT_CONNECTED)
        val result = CompletableDeferred<PlaceCallResult>()
        channel.send(Event.Start(inviteeIds, result))
        return result.await()
    }

    fun accept() {
        events?.trySend(Event.Accept)
    }

    fun decline() {
        events?.trySend(Event.Decline)
    }

    fun leave() {
        events?.trySend(Event.Leave)
    }

    fun setMuted(muted: Boolean) {
        events?.trySend(Event.SetMuted(muted))
    }

    fun setSpeakerOn(enabled: Boolean) {
        events?.trySend(Event.SetSpeaker(enabled))
    }

    fun onIncomingGroupCallPush(callId: String, callerId: String, media: String, ringExpiresAt: String?) {
        events?.trySend(
            Event.IncomingPush(
                callId = callId,
                callerId = callerId,
                media = media,
                ringExpiresAt = ringExpiresAt?.let { isoToEpochMillisOrNull(it) }
            )
        )
    }

    private suspend fun handle(event: Event) {
        when (event) {
            is Event.Frame -> onFrame(event.frame)
            is Event.Connection -> onConnection(event.connection)
            is Event.Start -> onStart(event)
            is Event.Created -> onCreated(event.callId, event.result)
            is Event.JoinResult -> onJoinResult(event.callId, event.result)
            is Event.Described -> onDescribed(event.callId, event.result)
            is Event.IncomingPush -> onIncomingPush(event)
            is Event.Accept -> onAccept()
            is Event.Decline -> onDecline()
            is Event.Leave -> onLeave()
            is Event.SetMuted -> onSetMuted(event.muted)
            is Event.SetSpeaker -> onSetSpeaker(event.enabled)
            is Event.SfuState -> onSfuState(event.callId, event.state)
            is Event.SfuFailure -> onSfuFailure(event.callId, event.reason)
            is Event.RingTimeout -> onRingTimeout(event.callId)
            is Event.ClearEnded -> onClearEnded(event.callId)
        }
    }

    private fun onStart(event: Event.Start) {
        val existing = mutableSession.value
        if (existing != null && !existing.isSettled || otherCallActive()) {
            event.result.complete(PlaceCallResult.Rejected(ALREADY_IN_CALL))
            return
        }
        val connected = socket.state.value as? ConnectionState.Connected
        if (connected == null) {
            event.result.complete(PlaceCallResult.Rejected(NOT_CONNECTED))
            return
        }
        val inviteeIds = event.inviteeIds.distinct().filter { it != connected.userId }
        if (inviteeIds.isEmpty()) {
            event.result.complete(PlaceCallResult.Rejected(NOBODY_TO_CALL))
            return
        }
        if (inviteeIds.size + 1 > MAX_GROUP_PARTICIPANTS) {
            event.result.complete(PlaceCallResult.Rejected(TOO_MANY))
            return
        }
        resetCallState()
        val callId = newFrameId()
        mutableSession.value = GroupCallSession(
            callId = callId,
            selfId = connected.userId,
            initiatorId = connected.userId,
            media = MEDIA_AUDIO,
            direction = CallDirection.OUTGOING,
            stage = GroupCallStage.STARTING,
            participants = listOf(GroupParticipant(connected.userId, GroupParticipantState.JOINED)) +
                inviteeIds.map { GroupParticipant(it, GroupParticipantState.INVITED) },
            startedAt = now()
        )
        event.result.complete(PlaceCallResult.Started(callId))
        val channel = events ?: return
        scope.launch {
            val result = api.create(callId, MEDIA_AUDIO, inviteeIds, connected.sessionId)
            channel.send(Event.Created(callId, result))
        }
    }

    private suspend fun onCreated(callId: String, result: GroupCallApiResult<GroupCallStateResponse>) {
        val current = mutableSession.value ?: return
        if (current.callId != callId || current.isSettled) return
        when (result) {
            is GroupCallApiResult.Failure -> endLocally(reason = null, failure = result.reason, tellServer = false)
            is GroupCallApiResult.Success -> {
                val applied = current.applying(result.value).copy(stage = GroupCallStage.CONNECTING)
                mutableSession.value = applied
                scheduleRingTimeout(callId, applied.ringExpiresAt)
                connectSfu(callId, result.value)
            }
        }
    }

    private suspend fun onFrame(frame: InboundFrame) {
        if (frame !is InboundFrame.CallSignalFrame) return
        when (val signal = frame.signal) {
            is CallSignal.GroupInvite -> onGroupInvite(frame.callId, frame.fromUserId, signal)
            is CallSignal.ParticipantJoined ->
                updateParticipant(frame.callId, signal.userId, GroupParticipantState.JOINED)
            is CallSignal.ParticipantLeft ->
                updateParticipant(frame.callId, signal.userId, GroupParticipantState.LEFT)
            is CallSignal.ParticipantDeclined ->
                updateParticipant(frame.callId, signal.userId, GroupParticipantState.DECLINED)
            is CallSignal.ParticipantMissed -> onParticipantMissed(frame.callId, signal.userId)
            is CallSignal.GroupEnded ->
                endIfCurrent(frame.callId, signal.reason ?: CallEndReason.ALL_LEFT)
            is CallSignal.Cancel ->
                endIfCurrent(frame.callId, signal.reason ?: CallEndReason.SETTLED_ELSEWHERE)
            else -> Unit
        }
    }

    private fun onGroupInvite(callId: String, callerId: String, signal: CallSignal.GroupInvite) {
        val current = mutableSession.value
        if (current != null && current.callId == callId && !current.isSettled) {
            val ringExpiresAt = signal.ringExpiresAt?.let { isoToEpochMillisOrNull(it) }
            mutableSession.value = current.copy(
                participants = signal.participants.map { GroupParticipant(it.userId, it.state) },
                ringExpiresAt = ringExpiresAt ?: current.ringExpiresAt
            )
            return
        }
        if (current != null && !current.isSettled || otherCallActive()) {
            scope.launch { api.decline(callId, BUSY_HERE, connectedSessionId()) }
            return
        }
        val selfId = (socket.state.value as? ConnectionState.Connected)?.userId ?: return
        resetCallState()
        val ringExpiresAt = signal.ringExpiresAt?.let { isoToEpochMillisOrNull(it) }
        mutableSession.value = GroupCallSession(
            callId = callId,
            selfId = selfId,
            initiatorId = callerId,
            media = signal.media,
            direction = CallDirection.INCOMING,
            stage = GroupCallStage.INCOMING,
            participants = signal.participants.map { GroupParticipant(it.userId, it.state) },
            startedAt = signal.startedAt?.let { isoToEpochMillisOrNull(it) } ?: now(),
            ringExpiresAt = ringExpiresAt
        )
        scheduleRingTimeout(callId, ringExpiresAt)
    }

    private fun onIncomingPush(event: Event.IncomingPush) {
        val current = mutableSession.value
        if (current != null && !current.isSettled || otherCallActive()) return
        val selfId = (socket.state.value as? ConnectionState.Connected)?.userId
        resetCallState()
        mutableSession.value = GroupCallSession(
            callId = event.callId,
            selfId = selfId.orEmpty(),
            initiatorId = event.callerId,
            media = event.media,
            direction = CallDirection.INCOMING,
            stage = GroupCallStage.INCOMING,
            participants = listOf(
                GroupParticipant(event.callerId, GroupParticipantState.JOINED),
                GroupParticipant(selfId.orEmpty(), GroupParticipantState.INVITED)
            ),
            startedAt = now(),
            ringExpiresAt = event.ringExpiresAt
        )
        scheduleRingTimeout(event.callId, event.ringExpiresAt)
        refreshFromServer(event.callId)
    }

    private fun onAccept() {
        val current = mutableSession.value ?: return
        if (current.stage != GroupCallStage.INCOMING) return
        cancelRingTimeout()
        mutableSession.value = current.copy(stage = GroupCallStage.CONNECTING)
        val channel = events ?: return
        val callId = current.callId
        scope.launch {
            val result = api.join(callId, connectedSessionId())
            channel.send(Event.JoinResult(callId, result))
        }
    }

    private suspend fun onJoinResult(callId: String, result: GroupCallApiResult<GroupCallStateResponse>) {
        val current = mutableSession.value ?: return
        if (current.callId != callId || current.isSettled) return
        when (result) {
            is GroupCallApiResult.Failure -> endLocally(reason = null, failure = result.reason, tellServer = false)
            is GroupCallApiResult.Success -> {
                mutableSession.value = current.applying(result.value)
                connectSfu(callId, result.value)
            }
        }
    }

    private suspend fun onDecline() {
        val current = mutableSession.value ?: return
        if (current.stage != GroupCallStage.INCOMING) return
        scope.launch { api.decline(current.callId, CallEndReason.DECLINED, connectedSessionId()) }
        endLocally(reason = CallEndReason.DECLINED, failure = null, tellServer = false)
    }

    private suspend fun onLeave() {
        val current = mutableSession.value ?: return
        if (current.isSettled) return
        if (current.stage == GroupCallStage.INCOMING) {
            onDecline()
            return
        }
        endLocally(reason = CallEndReason.HANGUP, failure = null, tellServer = true)
    }

    private fun onSetMuted(muted: Boolean) {
        val current = mutableSession.value ?: return
        sfu?.setMicrophoneMuted(muted)
        mutableSession.value = current.copy(muted = muted)
    }

    private fun onSetSpeaker(enabled: Boolean) {
        val current = mutableSession.value ?: return
        sfu?.setSpeakerphoneOn(enabled)
        mutableSession.value = current.copy(speakerOn = enabled)
    }

    private suspend fun onSfuState(callId: String, state: SfuConnectionState) {
        val current = mutableSession.value ?: return
        if (current.callId != callId || current.isSettled) return
        when (state) {
            SfuConnectionState.CONNECTED -> mutableSession.value = current.copy(
                stage = GroupCallStage.ACTIVE,
                answeredAt = current.answeredAt ?: now()
            )
            SfuConnectionState.FAILED ->
                endLocally(reason = null, failure = MEDIA_LOST, tellServer = true)
            SfuConnectionState.DISCONNECTED ->
                endLocally(reason = null, failure = MEDIA_LOST, tellServer = true)
            else -> Unit
        }
    }

    private suspend fun onSfuFailure(callId: String, reason: String) {
        val current = mutableSession.value ?: return
        if (current.callId != callId || current.isSettled) return
        endLocally(reason = null, failure = reason, tellServer = true)
    }

    private fun updateParticipant(callId: String, userId: String, state: String) {
        val current = mutableSession.value ?: return
        if (current.callId != callId || current.isSettled) return
        mutableSession.value = current.copy(
            participants = current.participants.map {
                if (it.userId == userId) it.copy(state = state) else it
            }
        )
    }

    private suspend fun onParticipantMissed(callId: String, userId: String) {
        val current = mutableSession.value ?: return
        if (current.callId != callId || current.isSettled) return
        if (userId == current.selfId && current.stage == GroupCallStage.INCOMING) {
            endLocally(reason = CallEndReason.RING_TIMEOUT, failure = null, tellServer = false)
            return
        }
        updateParticipant(callId, userId, GroupParticipantState.MISSED)
    }

    private fun onConnection(connection: ConnectionState) {
        val current = mutableSession.value ?: return
        if (current.isSettled) return
        if (connection !is ConnectionState.Connected) return
        if (current.selfId.isEmpty()) {
            mutableSession.value = current.copy(
                selfId = connection.userId,
                participants = current.participants.map {
                    if (it.userId.isEmpty()) it.copy(userId = connection.userId) else it
                }
            )
        }
        if (current.stage != GroupCallStage.STARTING) {
            refreshFromServer(current.callId)
        }
    }

    private suspend fun onDescribed(callId: String, result: GroupCallApiResult<GroupCallStateResponse>) {
        val current = mutableSession.value ?: return
        if (current.callId != callId || current.isSettled) return
        val response = (result as? GroupCallApiResult.Success)?.value ?: return
        if (response.status.isTerminal()) {
            endLocally(
                reason = response.endReason ?: CallStatusWire.ENDED,
                failure = null,
                tellServer = false
            )
            return
        }
        mutableSession.value = current.applying(response)
    }

    private suspend fun onRingTimeout(callId: String) {
        val current = mutableSession.value ?: return
        if (current.callId != callId || current.isSettled) return
        val ringing = when (current.stage) {
            GroupCallStage.INCOMING -> true
            else -> current.direction == CallDirection.OUTGOING && current.joinedOthersCount == 0
        }
        if (!ringing) return
        endLocally(reason = CallEndReason.RING_TIMEOUT, failure = null, tellServer = current.isOutgoing())
    }

    private fun onClearEnded(callId: String) {
        val current = mutableSession.value ?: return
        if (current.callId == callId && current.isSettled) mutableSession.value = null
    }

    private suspend fun endIfCurrent(callId: String, reason: String) {
        val current = mutableSession.value ?: return
        if (current.callId != callId || current.isSettled) return
        endLocally(reason = reason, failure = null, tellServer = false)
    }

    private suspend fun endLocally(reason: String?, failure: String?, tellServer: Boolean) {
        val current = mutableSession.value ?: return
        cancelRingTimeout()
        if (tellServer) {
            scope.launch { api.leave(current.callId, connectedSessionId()) }
        }
        teardownSfu()
        mutableSession.value = current.copy(
            stage = GroupCallStage.ENDED,
            endReason = reason,
            failure = failure
        )
        scheduleClearEnded(current.callId)
    }

    private fun connectSfu(callId: String, response: GroupCallStateResponse) {
        val access = response.livekit
        if (access == null) {
            events?.trySend(Event.SfuFailure(callId, MEDIA_LOST))
            return
        }
        val client = sfuFactory.create()
        sfu = client
        client.connect(access.url, access.token, EngineSfuListener(callId))
    }

    private fun refreshFromServer(callId: String) {
        val channel = events ?: return
        scope.launch {
            val result = api.describe(callId)
            channel.send(Event.Described(callId, result))
        }
    }

    private fun connectedSessionId(): String? =
        (socket.state.value as? ConnectionState.Connected)?.sessionId

    private fun scheduleRingTimeout(callId: String, ringExpiresAt: Long?) {
        ringTimeoutJob?.cancel()
        val channel = events ?: return
        val remaining = ringExpiresAt?.let { it - now() } ?: DEFAULT_RING_MILLIS
        ringTimeoutJob = scope.launch {
            delay(if (remaining > 0) remaining else 0)
            channel.trySend(Event.RingTimeout(callId))
        }
    }

    private fun cancelRingTimeout() {
        ringTimeoutJob?.cancel()
        ringTimeoutJob = null
    }

    private fun scheduleClearEnded(callId: String) {
        clearEndedJob?.cancel()
        val channel = events ?: return
        clearEndedJob = scope.launch {
            delay(ENDED_VISIBLE_MILLIS)
            channel.trySend(Event.ClearEnded(callId))
        }
    }

    private fun teardownSfu() {
        sfu?.close()
        sfu = null
    }

    private fun resetCallState() {
        cancelRingTimeout()
        clearEndedJob?.cancel()
        clearEndedJob = null
        teardownSfu()
    }

    private fun GroupCallSession.applying(response: GroupCallStateResponse): GroupCallSession =
        copy(
            initiatorId = response.initiator,
            media = response.media,
            participants = response.participants.map { GroupParticipant(it.userId, it.state) },
            startedAt = isoToEpochMillisOrNull(response.startedAt) ?: startedAt,
            ringExpiresAt = response.ringExpiresAt?.let { isoToEpochMillisOrNull(it) } ?: ringExpiresAt
        )

    private fun GroupCallSession.isOutgoing(): Boolean = direction == CallDirection.OUTGOING

    private fun String.isTerminal(): Boolean =
        this == CallStatusWire.ENDED || this == CallStatusWire.REJECTED || this == CallStatusWire.MISSED

    private inner class EngineSfuListener(private val callId: String) : SfuListener {
        override fun onConnectionState(state: SfuConnectionState) {
            events?.trySend(Event.SfuState(callId, state))
        }

        override fun onFailure(reason: String) {
            events?.trySend(Event.SfuFailure(callId, reason))
        }
    }
}
