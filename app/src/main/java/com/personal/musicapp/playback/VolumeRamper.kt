package com.personal.musicapp.playback

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.Player

/**
 * Handler-timed linear volume ramp. Good enough for M1; M2 replaces it with sample-accurate
 * ramps inside the native audio engine. Main thread only.
 */
class VolumeRamper(private val player: Player) {
    private val handler = Handler(Looper.getMainLooper())
    private var task: Runnable? = null

    fun snapTo(volume: Float) {
        cancel()
        player.volume = volume
    }

    /** Starting a new ramp cancels the previous one, including its [onDone]. */
    fun rampTo(target: Float, durationMs: Long, onDone: (() -> Unit)? = null) {
        cancel()
        val from = player.volume
        if (durationMs <= 0L || from == target) {
            player.volume = target
            onDone?.invoke()
            return
        }
        val start = SystemClock.uptimeMillis()
        val step = object : Runnable {
            override fun run() {
                val t = ((SystemClock.uptimeMillis() - start).toFloat() / durationMs).coerceIn(0f, 1f)
                player.volume = from + (target - from) * t
                if (t < 1f) {
                    handler.postDelayed(this, STEP_MS)
                } else {
                    task = null
                    onDone?.invoke()
                }
            }
        }
        task = step
        handler.post(step)
    }

    fun cancel() {
        task?.let(handler::removeCallbacks)
        task = null
    }

    private companion object {
        const val STEP_MS = 8L
    }
}
