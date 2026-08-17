package com.relay.testutil

import com.relay.call.RtcClient
import com.relay.call.RtcClientFactory
import com.relay.call.RtcConfig
import com.relay.call.RtcConnectionState
import com.relay.call.RtcListener
import com.relay.call.RtcRole
import com.relay.network.CallApi
import com.relay.network.CallApiResult
import com.relay.network.CallHistoryResponse
import com.relay.network.IceServerResponse
import com.relay.network.IceServersResponse
import com.relay.call.CallSession
import com.relay.call.PlaceCallResult
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
