package com.relay.call

enum class SfuConnectionState { CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED, FAILED }

interface SfuListener {
    fun onConnectionState(state: SfuConnectionState)
    fun onFailure(reason: String)
}

interface SfuClient {
    fun connect(url: String, token: String, listener: SfuListener)
    fun setMicrophoneMuted(muted: Boolean)
    fun setSpeakerphoneOn(enabled: Boolean)
    fun close()
}

interface SfuClientFactory {
    fun create(): SfuClient
}

class UnavailableSfuClient(private val reason: String) : SfuClient {
    private var listener: SfuListener? = null

    override fun connect(url: String, token: String, listener: SfuListener) {
        this.listener = listener
        listener.onFailure(reason)
    }

    override fun setMicrophoneMuted(muted: Boolean) = Unit
    override fun setSpeakerphoneOn(enabled: Boolean) = Unit

    override fun close() {
        listener = null
    }
}

const val SFU_UNAVAILABLE = "Group calling is not available on this device"
