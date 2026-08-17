package com.relay.call

import android.content.Context
import android.media.AudioManager
import androidx.core.content.getSystemService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule

private const val AUDIO_TRACK_ID = "relay-audio"
private const val STREAM_ID = "relay-stream"
private const val CANDIDATE = "candidate"
private const val SDP_MID = "sdpMid"
private const val SDP_M_LINE_INDEX = "sdpMLineIndex"
private const val NO_PEER_CONNECTION = "The call could not be set up on this device"

private val CandidateJson = Json { ignoreUnknownKeys = true }

class WebRtcAudioClient(private val context: Context) : RtcClient {

    private val audioManager = context.getSystemService<AudioManager>()

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var listener: RtcListener? = null
    private var previousMode: Int? = null
    private var previousSpeakerphone: Boolean? = null
    private var closed = false

    override fun start(config: RtcConfig, role: RtcRole, listener: RtcListener) {
        this.listener = listener
        val built = buildFactory()
        factory = built
        val connection = built.createPeerConnection(rtcConfigurationOf(config), PeerObserver())
        if (connection == null) {
            listener.onFailure(NO_PEER_CONNECTION)
            return
        }
        peerConnection = connection
        val source = built.createAudioSource(MediaConstraints())
        audioSource = source
        val track = built.createAudioTrack(AUDIO_TRACK_ID, source)
        audioTrack = track
        connection.addTrack(track, listOf(STREAM_ID))
        claimAudioRoute()
        if (role == RtcRole.CALLER) createOffer(connection)
    }

    override fun setRemoteOffer(sdp: String) {
        val connection = peerConnection ?: return
        connection.setRemoteDescription(
            observer(onSet = { createAnswer(connection) }),
            SessionDescription(SessionDescription.Type.OFFER, sdp)
        )
    }

    override fun setRemoteAnswer(sdp: String) {
        val connection = peerConnection ?: return
        connection.setRemoteDescription(
            observer(),
            SessionDescription(SessionDescription.Type.ANSWER, sdp)
        )
    }

    override fun addRemoteCandidate(candidateJson: String) {
        val connection = peerConnection ?: return
        val parsed = decodeCandidate(candidateJson) ?: return
        connection.addIceCandidate(parsed)
    }

    override fun setMicrophoneMuted(muted: Boolean) {
        audioTrack?.setEnabled(!muted)
    }

    override fun setSpeakerphoneOn(enabled: Boolean) {
        audioManager?.isSpeakerphoneOn = enabled
    }

    override fun close() {
        if (closed) return
        closed = true
        listener = null
        peerConnection?.dispose()
        peerConnection = null
        audioTrack?.dispose()
        audioTrack = null
        audioSource?.dispose()
        audioSource = null
        factory?.dispose()
        factory = null
        releaseAudioRoute()
    }

    private fun buildFactory(): PeerConnectionFactory {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions()
        )
        val eglContext = EglBase.create().eglBaseContext
        return PeerConnectionFactory.builder()
            .setAudioDeviceModule(
                JavaAudioDeviceModule.builder(context.applicationContext)
                    .createAudioDeviceModule()
            )
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
            .createPeerConnectionFactory()
    }

    private fun createOffer(connection: PeerConnection) {
        connection.createOffer(
            observer(onCreated = { description -> setLocal(connection, description) }),
            MediaConstraints()
        )
    }

    private fun createAnswer(connection: PeerConnection) {
        connection.createAnswer(
            observer(onCreated = { description -> setLocal(connection, description) }),
            MediaConstraints()
        )
    }

    private fun setLocal(connection: PeerConnection, description: SessionDescription) {
        connection.setLocalDescription(
            observer(onSet = { listener?.onLocalDescription(description.description) }),
            description
        )
    }

    private fun claimAudioRoute() {
        val manager = audioManager ?: return
        previousMode = manager.mode
        previousSpeakerphone = manager.isSpeakerphoneOn
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        manager.isSpeakerphoneOn = false
    }

    private fun releaseAudioRoute() {
        val manager = audioManager ?: return
        previousMode?.let { manager.mode = it }
        previousSpeakerphone?.let { manager.isSpeakerphoneOn = it }
        previousMode = null
        previousSpeakerphone = null
    }

    private fun rtcConfigurationOf(config: RtcConfig): PeerConnection.RTCConfiguration {
        val servers = config.iceServers.map { server ->
            PeerConnection.IceServer.builder(server.urls)
                .setUsername(server.username.orEmpty())
                .setPassword(server.credential.orEmpty())
                .createIceServer()
        }
        return PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
    }

    private fun observer(
        onCreated: (SessionDescription) -> Unit = {},
        onSet: () -> Unit = {}
    ): SdpObserver = object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = onCreated(description)
        override fun onSetSuccess() = onSet()
        override fun onCreateFailure(error: String?) {
            listener?.onFailure(error ?: NO_PEER_CONNECTION)
        }

        override fun onSetFailure(error: String?) {
            listener?.onFailure(error ?: NO_PEER_CONNECTION)
        }
    }

    private inner class PeerObserver : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            listener?.onLocalCandidate(encodeCandidate(candidate))
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            listener?.onConnectionState(newState.toRtcState())
        }

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
            if (newState == PeerConnection.IceConnectionState.FAILED) {
                listener?.onConnectionState(RtcConnectionState.FAILED)
            }
        }

        override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: org.webrtc.DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit
    }
}

private fun PeerConnection.PeerConnectionState.toRtcState(): RtcConnectionState =
    when (this) {
        PeerConnection.PeerConnectionState.NEW -> RtcConnectionState.IDLE
        PeerConnection.PeerConnectionState.CONNECTING -> RtcConnectionState.CONNECTING
        PeerConnection.PeerConnectionState.CONNECTED -> RtcConnectionState.CONNECTED
        PeerConnection.PeerConnectionState.DISCONNECTED -> RtcConnectionState.DISCONNECTED
        PeerConnection.PeerConnectionState.FAILED -> RtcConnectionState.FAILED
        PeerConnection.PeerConnectionState.CLOSED -> RtcConnectionState.CLOSED
    }

private fun encodeCandidate(candidate: IceCandidate): String =
    buildJsonObject {
        put(CANDIDATE, JsonPrimitive(candidate.sdp))
        put(SDP_MID, JsonPrimitive(candidate.sdpMid))
        put(SDP_M_LINE_INDEX, JsonPrimitive(candidate.sdpMLineIndex))
    }.toString()

private fun decodeCandidate(json: String): IceCandidate? {
    val parsed = runCatching { CandidateJson.parseToJsonElement(json) as? JsonObject }.getOrNull()
        ?: return null
    val sdp = (parsed[CANDIDATE] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val sdpMid = (parsed[SDP_MID] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val index = (parsed[SDP_M_LINE_INDEX] as? JsonPrimitive)?.let {
        runCatching { it.int }.getOrNull()
    } ?: 0
    return IceCandidate(sdpMid, index, sdp)
}

class WebRtcClientFactory(private val context: Context) : RtcClientFactory {
    override fun create(): RtcClient = WebRtcAudioClient(context)
}
