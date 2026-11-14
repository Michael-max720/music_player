package com.personal.musicapp.playback

import android.content.Context
import android.media.AudioManager
import androidx.core.content.ContextCompat

/**
 * Detects cellular, VoIP and call-screening states from the system audio mode, so no phone
 * permission is needed. Works in every focus mode, including Mix (where we get no focus events).
 */
class CallStateMonitor(
    context: Context,
    private val onChange: (inCall: Boolean) -> Unit,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val executor = ContextCompat.getMainExecutor(context)
    private val listener = AudioManager.OnModeChangedListener { mode -> publish(mode) }
    private var last = false

    fun start() {
        audioManager.addOnModeChangedListener(executor, listener)
        publish(audioManager.mode)
    }

    fun stop() = audioManager.removeOnModeChangedListener(listener)

    private fun publish(mode: Int) {
        val inCall = isCallMode(mode)
        if (inCall != last) {
            last = inCall
            onChange(inCall)
        }
    }

    private fun isCallMode(mode: Int) =
        mode == AudioManager.MODE_RINGTONE ||
            mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_CALL_SCREENING
}
