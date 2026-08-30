package com.relay.call

import android.content.Context
import android.media.AudioManager
import androidx.core.content.getSystemService
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val CONNECT_FAILED = "Could not connect to the call"

class LivekitSfuClient(private val context: Context) : SfuClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioManager = context.getSystemService<AudioManager>()

    private var room: Room? = null
    private var listener: SfuListener? = null
    private var previousMode: Int? = null
    private var previousSpeakerphone: Boolean? = null
    private var closed = false

    override fun connect(url: String, token: String, listener: SfuListener) {
        this.listener = listener
        val room = LiveKit.create(context.applicationContext)
        this.room = room
        claimAudioRoute()
        scope.launch {
            room.events.collect { event -> onRoomEvent(event) }
        }
        scope.launch {
            listener.onConnectionState(SfuConnectionState.CONNECTING)
            try {
                room.connect(url, token)
                room.localParticipant.setMicrophoneEnabled(true)
                listener.onConnectionState(SfuConnectionState.CONNECTED)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) { // allow: broad-catch LiveKit's connect surfaces engine, socket, and token errors with no useful common supertype; every one of them means the room is unreachable
                listener.onFailure(e.message ?: CONNECT_FAILED)
            }
        }
    }

    override fun setMicrophoneMuted(muted: Boolean) {
        val room = room ?: return
        scope.launch {
            try {
                room.localParticipant.setMicrophoneEnabled(!muted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) { // allow: broad-catch toggling a track mid-teardown throws engine-specific exceptions; a failed mute toggle must never crash the call
            }
        }
    }

    override fun setSpeakerphoneOn(enabled: Boolean) {
        audioManager?.isSpeakerphoneOn = enabled
    }

    override fun close() {
        if (closed) return
        closed = true
        listener = null
        val current = room
        room = null
        current?.disconnect()
        current?.release()
        releaseAudioRoute()
        scope.cancel()
    }

    private fun onRoomEvent(event: RoomEvent) {
        when (event) {
            is RoomEvent.Reconnecting -> listener?.onConnectionState(SfuConnectionState.RECONNECTING)
            is RoomEvent.Reconnected -> listener?.onConnectionState(SfuConnectionState.CONNECTED)
            is RoomEvent.Disconnected -> listener?.onConnectionState(SfuConnectionState.DISCONNECTED)
            else -> Unit
        }
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
}

class LivekitSfuClientFactory(private val context: Context) : SfuClientFactory {
    override fun create(): SfuClient = LivekitSfuClient(context)
}
