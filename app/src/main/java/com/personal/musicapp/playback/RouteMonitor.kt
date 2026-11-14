package com.personal.musicapp.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/** Pause when the output goes away (headphones unplugged); fade in when an output device changes. */
class RouteMonitor(
    private val context: Context,
    private val onEvent: (PolicyEvent) -> Unit,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                onEvent(PolicyEvent.BecomingNoisy)
            }
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (addedDevices.any { it.isSink }) onEvent(PolicyEvent.RouteChanged)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (removedDevices.any { it.isSink }) onEvent(PolicyEvent.RouteChanged)
        }
    }

    fun start() {
        ContextCompat.registerReceiver(
            context,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
    }

    fun stop() {
        runCatching { context.unregisterReceiver(noisyReceiver) }
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
    }
}
