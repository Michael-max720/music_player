package com.personal.musicapp.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * Owns the platform audio-focus request. Standard media usage only: USAGE_MEDIA / CONTENT_TYPE_MUSIC,
 * never a voice or communication stream, so music cannot enter call audio.
 */
class AudioFocusController(
    context: Context,
    private val onEvent: (PolicyEvent) -> Unit,
) {
    enum class Result { GRANTED, DELAYED, FAILED }

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var current: AudioFocusRequest? = null

    val held: Boolean get() = current != null

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> onEvent(PolicyEvent.FocusGained)
            AudioManager.AUDIOFOCUS_LOSS -> onEvent(PolicyEvent.FocusLostPermanent)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onEvent(PolicyEvent.FocusLostTransient)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onEvent(PolicyEvent.DuckStart)
        }
    }

    /** Requests focus for [mode]. A new request replaces the old one only after it is granted. */
    fun request(mode: FocusMode): Result {
        val gain = when (mode) {
            FocusMode.EXCLUSIVE -> AudioManager.AUDIOFOCUS_GAIN
            FocusMode.DUCK -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            FocusMode.MIX -> {
                abandon() // Mix plays alongside everything and takes no focus
                return Result.GRANTED
            }
        }
        val previous = current
        val request = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(false) // we duck ourselves
            .setOnAudioFocusChangeListener(listener, handler)
            .build()
        return when (audioManager.requestAudioFocus(request)) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                current = request
                previous?.let(audioManager::abandonAudioFocusRequest)
                Result.GRANTED
            }
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                current = request
                previous?.let(audioManager::abandonAudioFocusRequest)
                Result.DELAYED
            }
            else -> Result.FAILED
        }
    }

    fun abandon() {
        current?.let(audioManager::abandonAudioFocusRequest)
        current = null
    }
}
