package com.relay.call

data class RtcIceServer(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null
)

data class RtcConfig(
    val iceServers: List<RtcIceServer>
)

enum class RtcRole { CALLER, CALLEE }

enum class RtcConnectionState { IDLE, CONNECTING, CONNECTED, DISCONNECTED, FAILED, CLOSED }

interface RtcListener {
    fun onLocalDescription(sdp: String)
    fun onLocalCandidate(candidateJson: String)
    fun onConnectionState(state: RtcConnectionState)
    fun onFailure(reason: String)
}

interface RtcClient {
    fun start(config: RtcConfig, role: RtcRole, listener: RtcListener)
    fun setRemoteOffer(sdp: String)
    fun setRemoteAnswer(sdp: String)
    fun addRemoteCandidate(candidateJson: String)
    fun setMicrophoneMuted(muted: Boolean)
    fun setSpeakerphoneOn(enabled: Boolean)
    fun close()
}

interface RtcClientFactory {
    fun create(): RtcClient
}

class UnavailableRtcClient(private val reason: String) : RtcClient {
    private var listener: RtcListener? = null

    override fun start(config: RtcConfig, role: RtcRole, listener: RtcListener) {
        this.listener = listener
        listener.onFailure(reason)
    }

    override fun setRemoteOffer(sdp: String) = Unit
    override fun setRemoteAnswer(sdp: String) = Unit
    override fun addRemoteCandidate(candidateJson: String) = Unit
    override fun setMicrophoneMuted(muted: Boolean) = Unit
    override fun setSpeakerphoneOn(enabled: Boolean) = Unit

    override fun close() {
        listener = null
    }
}

const val RTC_UNAVAILABLE = "Calling is not available on this device"
