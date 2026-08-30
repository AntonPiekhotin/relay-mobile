package com.relay.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.getSystemService

private const val TAG = "RelayCallRinger"
private const val RING_TIMEOUT_MILLIS = 60_000L
private val VIBRATION_PATTERN = longArrayOf(0L, 1_000L, 1_000L)

object IncomingCallRinger {

    private val main = Handler(Looper.getMainLooper())
    private val timeout = Runnable { stop() }

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var audio: AudioManager? = null
    private var focus: AudioFocusRequest? = null
    private var ringing = false

    fun start(context: Context) {
        val app = context.applicationContext
        main.post {
            if (ringing) return@post
            ringing = true
            main.postDelayed(timeout, RING_TIMEOUT_MILLIS)
            val manager = app.getSystemService<AudioManager>()
            audio = manager
            if (manager?.ringerMode == AudioManager.RINGER_MODE_SILENT) return@post
            startVibration(app)
            if (manager?.ringerMode == AudioManager.RINGER_MODE_VIBRATE) return@post
            startTone(app, manager)
        }
    }

    fun stop() {
        main.post {
            if (!ringing) return@post
            ringing = false
            main.removeCallbacks(timeout)
            releaseTone()
            vibrator?.cancel()
            vibrator = null
        }
    }

    private fun startTone(context: Context, manager: AudioManager?) {
        val uri = ringtoneUri(context) ?: return
        val attributes = ringtoneAttributes()
        requestFocus(manager, attributes)
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(attributes)
                setDataSource(context, uri)
                isLooping = true
                setOnPreparedListener { prepared -> if (ringing) prepared.start() }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "the ringtone stopped with $what/$extra")
                    true
                }
                prepareAsync()
            }
        }.onFailure { Log.w(TAG, "cannot play the ringtone", it) }.getOrNull()
    }

    private fun releaseTone() {
        val current = player
        if (current != null) {
            current.setOnPreparedListener(null)
            current.setOnErrorListener(null)
            runCatching { current.stop() }
            current.release()
        }
        player = null
        val request = focus
        if (request != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audio?.abandonAudioFocusRequest(request)
        }
        focus = null
        audio = null
    }

    private fun requestFocus(manager: AudioManager?, attributes: AudioAttributes) {
        if (manager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .build()
        focus = request
        manager.requestAudioFocus(request)
    }

    private fun startVibration(context: Context) {
        val device = vibratorOf(context)?.takeIf { it.hasVibrator() } ?: return
        vibrator = device
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            device.vibrate(
                VibrationEffect.createWaveform(VIBRATION_PATTERN, 0),
                ringtoneAttributes()
            )
        } else {
            @Suppress("DEPRECATION")
            device.vibrate(VIBRATION_PATTERN, 0)
        }
    }

    private fun vibratorOf(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService<VibratorManager>()?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService<Vibrator>()
        }

    private fun ringtoneAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    private fun ringtoneUri(context: Context): Uri? =
        runCatching {
            RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
        }.getOrNull()
            ?: Settings.System.DEFAULT_RINGTONE_URI
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
}
