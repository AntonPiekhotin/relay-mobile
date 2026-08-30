package com.relay.call

import com.relay.network.CallApi
import com.relay.network.CallApiResult
import com.relay.network.ConnectionState
import com.relay.network.SocketClient
import com.relay.protocol.CallEndReason
import com.relay.protocol.CallSignal
import com.relay.protocol.CallStatusWire
import com.relay.protocol.Envelope
import com.relay.protocol.ErrorCode
import com.relay.protocol.InboundFrame
import com.relay.protocol.MEDIA_AUDIO
import com.relay.protocol.WireJson
import com.relay.protocol.callAcceptFrame
import com.relay.protocol.callHangupFrame
import com.relay.protocol.callIceFrame
import com.relay.protocol.callInviteFrame
import com.relay.protocol.callRejectFrame
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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject

const val DEFAULT_RING_MILLIS = 45_000L
const val INVITE_RETRY_MILLIS = 5_000L
const val ENDED_VISIBLE_MILLIS = 2_500L

private const val BUSY_HERE = "busy"
private const val CONNECTION_LOST = "connection_lost"
private const val ALREADY_IN_CALL = "You are already in a call"
private const val NOT_CONNECTED = "No connection to the server"

class CallEngine(
    private val socket: SocketClient,
    private val api: CallApi,
    private val rtcFactory: RtcClientFactory,
    private val scope: CoroutineScope,
    private val otherCallActive: () -> Boolean = { false },
    private val now: () -> Long = ::nowEpochMillis
) {
    private val mutableSession = MutableStateFlow<CallSession?>(null)
    val session: StateFlow<CallSession?> = mutableSession.asStateFlow()

    private var events: Channel<Event>? = null
    private val jobs = mutableListOf<Job>()

    private var rtc: RtcClient? = null
    private var pendingOffer: String? = null
    private var localSdp: String? = null
    private var remoteReady = false
    private var acceptWhenOfferArrives = false
    private val bufferedRemoteCandidates = mutableListOf<JsonObject>()
    private val sentFrameIds = mutableSetOf<String>()

    private var inviteRetryJob: Job? = null
    private var ringTimeoutJob: Job? = null
    private var clearEndedJob: Job? = null

    private sealed interface Event {
        data class Frame(val frame: InboundFrame) : Event
        data class Connection(val connection: ConnectionState) : Event
        data class Place(
            val peerId: String,
            val dialogId: String?,
            val result: CompletableDeferred<PlaceCallResult>
        ) : Event
        data class Ready(val callId: String, val config: RtcConfig) : Event
        data class IncomingPush(
            val callId: String,
            val callerId: String,
            val media: String,
            val ringExpiresAt: Long?
        ) : Event
        data object Accept : Event
        data object Reject : Event
        data object Hangup : Event
        data class SetMuted(val muted: Boolean) : Event
        data class SetSpeaker(val enabled: Boolean) : Event
        data class LocalDescription(val callId: String, val sdp: String) : Event
        data class LocalCandidate(val callId: String, val candidate: JsonObject) : Event
        data class RtcState(val callId: String, val state: RtcConnectionState) : Event
        data class RtcFailure(val callId: String, val reason: String) : Event
        data class InviteRetry(val callId: String) : Event
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

    suspend fun place(peerId: String, dialogId: String?): PlaceCallResult {
        val channel = events ?: return PlaceCallResult.Rejected(NOT_CONNECTED)
        val result = CompletableDeferred<PlaceCallResult>()
        channel.send(Event.Place(peerId, dialogId, result))
        return result.await()
    }

    fun accept() {
        events?.trySend(Event.Accept)
    }

    fun reject() {
        events?.trySend(Event.Reject)
    }

    fun hangup() {
        events?.trySend(Event.Hangup)
    }

    fun setMuted(muted: Boolean) {
        events?.trySend(Event.SetMuted(muted))
    }

    fun setSpeakerOn(enabled: Boolean) {
        events?.trySend(Event.SetSpeaker(enabled))
    }

    fun onIncomingCallPush(callId: String, callerId: String, media: String, ringExpiresAt: String?) {
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
            is Event.Place -> onPlace(event)
            is Event.Ready -> onReady(event.callId, event.config)
            is Event.IncomingPush -> onIncomingPush(event)
            is Event.Accept -> onAccept()
            is Event.Reject -> onReject()
            is Event.Hangup -> onHangup()
            is Event.SetMuted -> onSetMuted(event.muted)
            is Event.SetSpeaker -> onSetSpeaker(event.enabled)
            is Event.LocalDescription -> onLocalDescription(event.callId, event.sdp)
            is Event.LocalCandidate -> onLocalCandidate(event.callId, event.candidate)
            is Event.RtcState -> onRtcState(event.callId, event.state)
            is Event.RtcFailure -> onRtcFailure(event.callId, event.reason)
            is Event.InviteRetry -> onInviteRetry(event.callId)
            is Event.RingTimeout -> onRingTimeout(event.callId)
            is Event.ClearEnded -> onClearEnded(event.callId)
        }
    }

    private fun onPlace(event: Event.Place) {
        val existing = mutableSession.value
        if (existing != null && !existing.isSettled || otherCallActive()) {
            event.result.complete(PlaceCallResult.Rejected(ALREADY_IN_CALL))
            return
        }
        if (socket.state.value !is ConnectionState.Connected) {
            event.result.complete(PlaceCallResult.Rejected(NOT_CONNECTED))
            return
        }
        resetCallState()
        val callId = newFrameId()
        mutableSession.value = CallSession(
            callId = callId,
            peerId = event.peerId,
            dialogId = event.dialogId,
            media = MEDIA_AUDIO,
            direction = CallDirection.OUTGOING,
            stage = CallStage.DIALING,
            startedAt = now()
        )
        event.result.complete(PlaceCallResult.Started(callId))
        fetchIceServers(callId)
    }

    private fun fetchIceServers(callId: String) {
        val channel = events ?: return
        scope.launch {
            val servers = when (val result = api.iceServers()) {
                is CallApiResult.Success -> result.value.iceServers.map {
                    RtcIceServer(urls = it.urls, username = it.username, credential = it.credential)
                }
                is CallApiResult.Failure -> emptyList()
            }
            channel.send(Event.Ready(callId, RtcConfig(servers)))
        }
    }

    private fun onReady(callId: String, config: RtcConfig) {
        val current = mutableSession.value ?: return
        if (current.callId != callId || current.isSettled) return
        val client = rtcFactory.create()
        rtc = client
        client.start(
            config = config,
            role = if (current.isOutgoing) RtcRole.CALLER else RtcRole.CALLEE,
            listener = EngineRtcListener(callId)
        )
        if (current.isOutgoing) return
        val offer = pendingOffer ?: return
        client.setRemoteOffer(offer)
        remoteReady = true
        flushRemoteCandidates(client)
    }

    private suspend fun onFrame(frame: InboundFrame) {
        when (frame) {
            is InboundFrame.CallSignalFrame -> onSignal(frame)
            is InboundFrame.Error -> onError(frame)
            else -> Unit
        }
    }

    private suspend fun onSignal(frame: InboundFrame.CallSignalFrame) {
        when (val signal = frame.signal) {
            is CallSignal.Invite -> onInvite(frame.callId, frame.fromUserId, signal)
            is CallSignal.Accept -> onRemoteAccept(frame.callId, signal.sdp)
            is CallSignal.Ice -> onRemoteCandidate(frame.callId, signal.candidate)
            is CallSignal.Reject -> endIfCurrent(frame.callId, signal.reason ?: CallEndReason.DECLINED)
            is CallSignal.Hangup -> endIfCurrent(frame.callId, signal.reason ?: CallEndReason.HANGUP)
            is CallSignal.Cancel ->
                endIfCurrent(frame.callId, signal.reason ?: CallEndReason.SETTLED_ELSEWHERE)
            is CallSignal.Missed ->
                endIfCurrent(frame.callId, signal.reason ?: CallEndReason.RING_TIMEOUT)
            is CallSignal.State -> onRemoteState(frame.callId, signal.status)
            else -> Unit
        }
    }

    private suspend fun onInvite(callId: String, callerId: String, signal: CallSignal.Invite) {
        val current = mutableSession.value
        val ringExpiresAt = signal.ringExpiresAt?.let { isoToEpochMillisOrNull(it) }
        if (current != null && current.callId == callId && !current.isSettled) {
            pendingOffer = signal.sdp
            mutableSession.value = current.copy(
                dialogId = current.dialogId ?: signal.dialogId,
                media = signal.media,
                ringExpiresAt = ringExpiresAt ?: current.ringExpiresAt
            )
            if (acceptWhenOfferArrives) {
                acceptWhenOfferArrives = false
                fetchIceServers(callId)
            }
            return
        }
        if (current != null && !current.isSettled || otherCallActive()) {
            send(callRejectFrame(callId, BUSY_HERE))
            return
        }
        resetCallState()
        pendingOffer = signal.sdp
        mutableSession.value = CallSession(
            callId = callId,
            peerId = callerId,
            dialogId = signal.dialogId,
            media = signal.media,
            direction = CallDirection.INCOMING,
            stage = CallStage.INCOMING,
            startedAt = signal.startedAt?.let { isoToEpochMillisOrNull(it) } ?: now(),
            ringExpiresAt = ringExpiresAt
        )
        scheduleRingTimeout(callId, ringExpiresAt)
    }

    private fun onIncomingPush(event: Event.IncomingPush) {
        val current = mutableSession.value
        if (current != null && !current.isSettled || otherCallActive()) return
        resetCallState()
        mutableSession.value = CallSession(
            callId = event.callId,
            peerId = event.callerId,
            dialogId = null,
            media = event.media,
            direction = CallDirection.INCOMING,
            stage = CallStage.INCOMING,
            startedAt = now(),
            ringExpiresAt = event.ringExpiresAt
        )
        scheduleRingTimeout(event.callId, event.ringExpiresAt)
    }

    private fun onRemoteAccept(callId: String, sdp: String) {
        val current = mutableSession.value ?: return
        if (current.callId != callId || current.isSettled) return
        cancelInviteRetry()
        cancelRingTimeout()
        val client = rtc ?: return
        client.setRemoteAnswer(sdp)
        remoteReady = true
        flushRemoteCandidates(client)
        mutableSession.value = current.copy(
            stage = CallStage.CONNECTING,
            answeredAt = current.answeredAt ?: now()
        )
    }

    private fun onRemoteCandidate(callId: String, candidate: JsonObject) {
        val current = mutableSession.value ?: return
        if (current.callId != callId || current.isSettled) return
        val client = rtc
        if (client == null || !remoteReady) {
            bufferedRemoteCandidates += candidate
            return
        }
        client.addRemoteCandidate(candidate.toString())
    }

    private fun onRemoteState(callId: String, status: String?) {
        val current = mutableSession.value ?: return
        if (current.callId != callId || current.isSettled) return
        if (status == CallStatusWire.RINGING && current.stage == CallStage.DIALING) {
            mutableSession.value = current.copy(stage = CallStage.RINGING)
        }
    }

    private suspend fun onError(frame: InboundFrame.Error) {
        val refId = frame.payload.refId ?: return
        if (refId !in sentFrameIds) return
        val current = mutableSession.value ?: return
        if (current.isSettled) return
        endLocally(reason = null, failure = describe(frame.payload.code), notifyServer = false)
    }

    private suspend fun onConnection(connection: ConnectionState) {
        if (connection is ConnectionState.Connected) return
        val current = mutableSession.value ?: return
        if (current.isSettled) return
        endLocally(reason = CONNECTION_LOST, failure = null, notifyServer = false)
    }

    private fun onAccept() {
        val current = mutableSession.value ?: return
        if (current.isOutgoing || current.stage != CallStage.INCOMING) return
        cancelRingTimeout()
        mutableSession.value = current.copy(stage = CallStage.CONNECTING)
        if (pendingOffer == null) {
            acceptWhenOfferArrives = true
            return
        }
        fetchIceServers(current.callId)
    }

    private suspend fun onReject() {
        val current = mutableSession.value ?: return
        if (current.isOutgoing || current.isSettled) return
        send(callRejectFrame(current.callId, CallEndReason.DECLINED))
        endLocally(reason = CallEndReason.DECLINED, failure = null, notifyServer = false)
    }

    private suspend fun onHangup() {
        val current = mutableSession.value ?: return
        if (current.isSettled) return
        if (!current.isOutgoing && current.stage == CallStage.INCOMING) {
            onReject()
            return
        }
        endLocally(reason = CallEndReason.HANGUP, failure = null, notifyServer = true)
    }

    private fun onSetMuted(muted: Boolean) {
        val current = mutableSession.value ?: return
        rtc?.setMicrophoneMuted(muted)
        mutableSession.value = current.copy(muted = muted)
    }

    private fun onSetSpeaker(enabled: Boolean) {
        val current = mutableSession.value ?: return
        rtc?.setSpeakerphoneOn(enabled)
        mutableSession.value = current.copy(speakerOn = enabled)
    }

    private suspend fun onLocalDescription(callId: String, sdp: String) {
        val current = mutableSession.value ?: return
        if (current.callId != callId || current.isSettled) return
        localSdp = sdp
        if (!current.isOutgoing) {
            send(callAcceptFrame(current.callId, sdp))
            return
        }
        send(
            callInviteFrame(
                callId = current.callId,
                calleeId = current.peerId,
                media = current.media,
                sdp = sdp,
                dialogId = current.dialogId
            )
        )
        mutableSession.value = current.copy(stage = CallStage.RINGING)
        scheduleInviteRetry(current.callId)
        scheduleRingTimeout(current.callId, current.ringExpiresAt)
    }

    private suspend fun onLocalCandidate(callId: String, candidate: JsonObject) {
        val current = mutableSession.value ?: return
        if (current.callId != callId || current.isSettled) return
        send(callIceFrame(current.callId, candidate))
    }

    private suspend fun onRtcState(callId: String, state: RtcConnectionState) {
        val current = mutableSession.value ?: return
        if (current.callId != callId || current.isSettled) return
        when (state) {
            RtcConnectionState.CONNECTED -> {
                cancelInviteRetry()
                cancelRingTimeout()
                mutableSession.value = current.copy(
                    stage = CallStage.ACTIVE,
                    answeredAt = current.answeredAt ?: now()
                )
            }
            RtcConnectionState.FAILED -> endLocally(
                reason = CallEndReason.HANGUP,
                failure = "The media connection failed",
                notifyServer = true
            )
            else -> Unit
        }
    }

    private suspend fun onRtcFailure(callId: String, reason: String) {
        val current = mutableSession.value ?: return
        if (current.callId != callId || current.isSettled) return
        endLocally(reason = null, failure = reason, notifyServer = true)
    }

    private suspend fun onInviteRetry(callId: String) {
        val current = mutableSession.value ?: return
        if (current.callId != callId || !current.isOutgoing) return
        if (current.stage != CallStage.RINGING && current.stage != CallStage.DIALING) return
        val sdp = localSdp ?: return
        send(
            callInviteFrame(
                callId = current.callId,
                calleeId = current.peerId,
                media = current.media,
                sdp = sdp,
                dialogId = current.dialogId
            )
        )
        scheduleInviteRetry(callId)
    }

    private suspend fun onRingTimeout(callId: String) {
        val current = mutableSession.value ?: return
        if (current.callId != callId || current.isSettled) return
        if (current.stage != CallStage.RINGING && current.stage != CallStage.INCOMING) return
        endLocally(reason = CallEndReason.RING_TIMEOUT, failure = null, notifyServer = false)
    }

    private fun onClearEnded(callId: String) {
        val current = mutableSession.value ?: return
        if (current.callId == callId && current.isSettled) mutableSession.value = null
    }

    private suspend fun endIfCurrent(callId: String, reason: String) {
        val current = mutableSession.value ?: return
        if (current.callId != callId || current.isSettled) return
        endLocally(reason = reason, failure = null, notifyServer = false)
    }

    private suspend fun endLocally(reason: String?, failure: String?, notifyServer: Boolean) {
        val current = mutableSession.value ?: return
        cancelInviteRetry()
        cancelRingTimeout()
        if (notifyServer) send(callHangupFrame(current.callId, reason))
        teardownRtc()
        mutableSession.value = current.copy(
            stage = CallStage.ENDED,
            endReason = reason,
            failure = failure
        )
        scheduleClearEnded(current.callId)
    }

    private suspend fun send(frame: Envelope) {
        frame.id?.let { sentFrameIds += it }
        socket.sendFrame(frame)
    }

    private fun flushRemoteCandidates(client: RtcClient) {
        bufferedRemoteCandidates.forEach { client.addRemoteCandidate(it.toString()) }
        bufferedRemoteCandidates.clear()
    }

    private fun scheduleInviteRetry(callId: String) {
        inviteRetryJob?.cancel()
        val channel = events ?: return
        inviteRetryJob = scope.launch {
            delay(INVITE_RETRY_MILLIS)
            channel.trySend(Event.InviteRetry(callId))
        }
    }

    private fun cancelInviteRetry() {
        inviteRetryJob?.cancel()
        inviteRetryJob = null
    }

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

    private fun teardownRtc() {
        rtc?.close()
        rtc = null
        remoteReady = false
        pendingOffer = null
        localSdp = null
        bufferedRemoteCandidates.clear()
        acceptWhenOfferArrives = false
    }

    private fun resetCallState() {
        cancelInviteRetry()
        cancelRingTimeout()
        clearEndedJob?.cancel()
        clearEndedJob = null
        teardownRtc()
        sentFrameIds.clear()
    }

    private fun describe(code: String): String =
        when (code) {
            ErrorCode.USER_BUSY -> "That person is already in a call"
            ErrorCode.CALL_NOT_FOUND -> "The call no longer exists"
            ErrorCode.INVALID_CALL_STATE -> "The call was already settled"
            ErrorCode.NOT_A_PARTICIPANT -> "You are not part of that call"
            ErrorCode.CALL_SIGNAL_FAILED -> "Call signaling is unavailable"
            else -> "The call failed ($code)"
        }

    private inner class EngineRtcListener(private val callId: String) : RtcListener {
        override fun onLocalDescription(sdp: String) {
            events?.trySend(Event.LocalDescription(callId, sdp))
        }

        override fun onLocalCandidate(candidateJson: String) {
            val parsed = try {
                WireJson.parseToJsonElement(candidateJson) as? JsonObject
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            } ?: return
            events?.trySend(Event.LocalCandidate(callId, parsed))
        }

        override fun onConnectionState(state: RtcConnectionState) {
            events?.trySend(Event.RtcState(callId, state))
        }

        override fun onFailure(reason: String) {
            events?.trySend(Event.RtcFailure(callId, reason))
        }
    }
}
