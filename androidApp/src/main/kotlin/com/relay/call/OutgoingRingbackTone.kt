package com.relay.call

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log

private const val TAG = "RelayCallRingback"
private const val RINGBACK_VOLUME = 80

object OutgoingRingbackTone {

    private val main = Handler(Looper.getMainLooper())
    private var generator: ToneGenerator? = null

    fun start() {
        main.post {
            if (generator != null) return@post
            val tones = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, RINGBACK_VOLUME) }
                .onFailure { Log.w(TAG, "cannot open the ringback tone generator", it) }
                .getOrNull() ?: return@post
            generator = tones
            tones.startTone(ToneGenerator.TONE_SUP_RINGTONE)
        }
    }

    fun stop() {
        main.post {
            val tones = generator ?: return@post
            generator = null
            tones.stopTone()
            tones.release()
        }
    }
}
