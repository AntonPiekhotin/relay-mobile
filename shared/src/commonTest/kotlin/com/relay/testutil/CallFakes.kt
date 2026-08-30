package com.relay.testutil

import com.relay.call.RtcClient
import com.relay.call.RtcClientFactory
import com.relay.call.RtcConfig
import com.relay.call.RtcConnectionState
import com.relay.call.RtcListener
import com.relay.call.RtcRole
import com.relay.call.CallSession
import com.relay.call.PlaceCallResult
import com.relay.call.SfuClient
import com.relay.call.SfuClientFactory
import com.relay.call.SfuConnectionState
import com.relay.call.SfuListener
import com.relay.network.CallApi
import com.relay.network.CallApiResult
import com.relay.network.CallHistoryResponse
import com.relay.network.GroupCallApi
import com.relay.network.GroupCallApiResult
import com.relay.network.GroupCallStateResponse
import com.relay.network.GroupParticipantResponse
import com.relay.network.IceServerResponse
import com.relay.network.IceServersResponse
import com.relay.network.SfuAccessResponse
import com.relay.network.SocketLifecycle
import com.relay.repository.CallRepository
import com.relay.protocol.CallSignal
import com.relay.protocol.InboundFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

const val TEST_OFFER = "v=0 offer"
const val TEST_ANSWER = "v=0 answer"

class FakeCallApi : CallApi {
    var iceHandler: suspend () -> CallApiResult<IceServersResponse> = {
        CallApiResult.Success(
            IceServersResponse(
                iceServers = listOf(IceServerResponse(urls = listOf("stun:example:3478"))),
                ttlSeconds = 60
            )
        )
    }
    var historyHandler: suspend (String?, Int) -> CallApiResult<CallHistoryResponse> =
        { _, _ -> CallApiResult.Success(CallHistoryResponse()) }

    var iceCalls = 0

    override suspend fun iceServers(): CallApiResult<IceServersResponse> {
        iceCalls++
        return iceHandler()
    }

    override suspend fun history(before: String?, limit: Int): CallApiResult<CallHistoryResponse> =
        historyHandler(before, limit)
}

class FakeRtcClient : RtcClient {
    var listener: RtcListener? = null
    var role: RtcRole? = null
    var config: RtcConfig? = null
    var offerFromPeer: String? = null
    var answerFromPeer: String? = null
    val remoteCandidates = mutableListOf<String>()
    var micMuted = false
    var speakerEnabled = false
    var closedCount = 0

    var localDescriptionOnStart: String? = TEST_OFFER
    var localDescriptionOnOffer: String? = TEST_ANSWER

    override fun start(config: RtcConfig, role: RtcRole, listener: RtcListener) {
        this.config = config
        this.role = role
        this.listener = listener
        if (role == RtcRole.CALLER) {
            localDescriptionOnStart?.let { listener.onLocalDescription(it) }
        }
    }

    override fun setRemoteOffer(sdp: String) {
        offerFromPeer = sdp
        localDescriptionOnOffer?.let { listener?.onLocalDescription(it) }
    }

    override fun setRemoteAnswer(sdp: String) {
        answerFromPeer = sdp
    }

    override fun addRemoteCandidate(candidateJson: String) {
        remoteCandidates += candidateJson
    }

    override fun setMicrophoneMuted(muted: Boolean) {
        micMuted = muted
    }

    override fun setSpeakerphoneOn(enabled: Boolean) {
        speakerEnabled = enabled
    }

    override fun close() {
        closedCount++
    }

    fun emitCandidate(candidate: String = "candidate:1 1 udp") {
        listener?.onLocalCandidate(candidateJson(candidate).toString())
    }

    fun emitConnected() {
        listener?.onConnectionState(RtcConnectionState.CONNECTED)
    }

    fun emitFailure(reason: String) {
        listener?.onFailure(reason)
    }
}

class FakeRtcClientFactory : RtcClientFactory {
    val created = mutableListOf<FakeRtcClient>()
    var next: FakeRtcClient? = null

    override fun create(): RtcClient {
        val client = next ?: FakeRtcClient()
        next = null
        created += client
        return client
    }

    fun last(): FakeRtcClient = created.last()
}

class FakeSocketLifecycle : SocketLifecycle {
    var starts = 0
    var stops = 0

    override fun start() {
        starts++
    }

    override fun stop() {
        stops++
    }
}

class FakeCallRepository : CallRepository {
    private val mutableSession = MutableStateFlow<CallSession?>(null)
    override val session: StateFlow<CallSession?> = mutableSession.asStateFlow()

    var placeHandler: suspend (String, String?) -> PlaceCallResult =
        { _, _ -> PlaceCallResult.Started("call-test") }
    var nameHandler: suspend (String) -> String? = { it }

    val placed = mutableListOf<Pair<String, String?>>()
    var accepts = 0
    var rejects = 0
    var hangups = 0
    var micMuted = false
    var speakerEnabled = false

    override suspend fun call(peerId: String, dialogId: String?): PlaceCallResult {
        placed += peerId to dialogId
        return placeHandler(peerId, dialogId)
    }

    override suspend fun peerName(userId: String): String? = nameHandler(userId)

    override fun accept() {
        accepts++
    }

    override fun reject() {
        rejects++
    }

    override fun hangup() {
        hangups++
    }

    override fun setMuted(muted: Boolean) {
        micMuted = muted
    }

    override fun setSpeakerOn(enabled: Boolean) {
        speakerEnabled = enabled
    }

    fun emit(session: CallSession?) {
        mutableSession.value = session
    }
}

class FakeGroupCallApi : GroupCallApi {
    var createHandler: suspend (String, String, List<String>, String?) -> GroupCallApiResult<GroupCallStateResponse> =
        { callId, media, inviteeIds, _ ->
            GroupCallApiResult.Success(
                groupCallResponse(
                    callId = callId,
                    media = media,
                    status = "ringing",
                    initiator = "me",
                    participants = listOf("me" to "joined") + inviteeIds.map { it to "invited" },
                    withToken = true
                )
            )
        }
    var joinHandler: suspend (String, String?) -> GroupCallApiResult<GroupCallStateResponse> =
        { callId, _ ->
            GroupCallApiResult.Success(
                groupCallResponse(
                    callId = callId,
                    status = "answered",
                    initiator = "caller",
                    participants = listOf("caller" to "joined", "me" to "joined"),
                    withToken = true
                )
            )
        }
    var declineHandler: suspend (String, String?, String?) -> GroupCallApiResult<GroupCallStateResponse> =
        { callId, _, _ ->
            GroupCallApiResult.Success(
                groupCallResponse(callId = callId, status = "ringing", initiator = "caller")
            )
        }
    var leaveHandler: suspend (String, String?) -> GroupCallApiResult<GroupCallStateResponse> =
        { callId, _ ->
            GroupCallApiResult.Success(
                groupCallResponse(callId = callId, status = "answered", initiator = "caller")
            )
        }
    var describeHandler: suspend (String) -> GroupCallApiResult<GroupCallStateResponse> =
        { callId ->
            GroupCallApiResult.Success(
                groupCallResponse(
                    callId = callId,
                    status = "ringing",
                    initiator = "caller",
                    participants = listOf("caller" to "joined", "me" to "invited")
                )
            )
        }

    val creates = mutableListOf<Pair<String, List<String>>>()
    val joins = mutableListOf<String>()
    val declines = mutableListOf<Pair<String, String?>>()
    val leaves = mutableListOf<String>()
    var describes = 0

    override suspend fun create(
        callId: String,
        media: String,
        inviteeIds: List<String>,
        sessionId: String?
    ): GroupCallApiResult<GroupCallStateResponse> {
        creates += callId to inviteeIds
        return createHandler(callId, media, inviteeIds, sessionId)
    }

    override suspend fun join(callId: String, sessionId: String?): GroupCallApiResult<GroupCallStateResponse> {
        joins += callId
        return joinHandler(callId, sessionId)
    }

    override suspend fun decline(
        callId: String,
        reason: String?,
        sessionId: String?
    ): GroupCallApiResult<GroupCallStateResponse> {
        declines += callId to reason
        return declineHandler(callId, reason, sessionId)
    }

    override suspend fun leave(callId: String, sessionId: String?): GroupCallApiResult<GroupCallStateResponse> {
        leaves += callId
        return leaveHandler(callId, sessionId)
    }

    override suspend fun describe(callId: String): GroupCallApiResult<GroupCallStateResponse> {
        describes++
        return describeHandler(callId)
    }
}

fun groupCallResponse(
    callId: String,
    status: String,
    initiator: String,
    media: String = "audio",
    participants: List<Pair<String, String>> = emptyList(),
    endReason: String? = null,
    withToken: Boolean = false
): GroupCallStateResponse =
    GroupCallStateResponse(
        callId = callId,
        kind = "group",
        media = media,
        status = status,
        initiator = initiator,
        startedAt = "2026-01-01T00:00:00Z",
        ringExpiresAt = null,
        endReason = endReason,
        participants = participants.map { GroupParticipantResponse(userId = it.first, state = it.second) },
        livekit = if (withToken) {
            SfuAccessResponse(url = "ws://sfu:7880", token = "token-$callId")
        } else {
            null
        }
    )

class FakeSfuClient : SfuClient {
    var listener: SfuListener? = null
    var url: String? = null
    var token: String? = null
    var micMuted = false
    var speakerEnabled = false
    var closedCount = 0
    var connectBehaviour: (SfuListener) -> Unit = { it.onConnectionState(SfuConnectionState.CONNECTED) }

    override fun connect(url: String, token: String, listener: SfuListener) {
        this.url = url
        this.token = token
        this.listener = listener
        connectBehaviour(listener)
    }

    override fun setMicrophoneMuted(muted: Boolean) {
        micMuted = muted
    }

    override fun setSpeakerphoneOn(enabled: Boolean) {
        speakerEnabled = enabled
    }

    override fun close() {
        closedCount++
    }

    fun emitState(state: SfuConnectionState) {
        listener?.onConnectionState(state)
    }

    fun emitFailure(reason: String) {
        listener?.onFailure(reason)
    }
}

class FakeSfuClientFactory : SfuClientFactory {
    val created = mutableListOf<FakeSfuClient>()
    var next: FakeSfuClient? = null

    override fun create(): SfuClient {
        val client = next ?: FakeSfuClient()
        next = null
        created += client
        return client
    }

    fun last(): FakeSfuClient = created.last()
}

fun candidateJson(candidate: String = "candidate:1 1 udp"): JsonObject =
    buildJsonObject {
        put("candidate", JsonPrimitive(candidate))
        put("sdpMid", JsonPrimitive("0"))
        put("sdpMLineIndex", JsonPrimitive(0))
    }

fun inviteSignal(
    callId: String,
    fromUserId: String,
    sdp: String = TEST_OFFER,
    dialogId: String? = null,
    ringExpiresAt: String? = null
): InboundFrame.CallSignalFrame =
    InboundFrame.CallSignalFrame(
        callId = callId,
        fromUserId = fromUserId,
        signal = CallSignal.Invite(
            media = "audio",
            sdp = sdp,
            dialogId = dialogId,
            startedAt = null,
            ringExpiresAt = ringExpiresAt
        )
    )

fun signalFrame(
    callId: String,
    fromUserId: String,
    signal: CallSignal
): InboundFrame.CallSignalFrame =
    InboundFrame.CallSignalFrame(callId = callId, fromUserId = fromUserId, signal = signal)
