package com.personal.musicapp.playback

import android.content.Context
import android.widget.Toast
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Wraps the real player so that EVERY play/pause request (app UI, notification, headset button,
 * Bluetooth, Android Auto) goes through [InterruptionPolicy]. It is the only place that touches
 * audio focus, call state, route changes, fades and position saving.
 *
 * It only depends on [Player], so in M2 the native Oboe engine slots in underneath unchanged.
 */
class PolicyPlayer(
    private val context: Context,
    private val exo: ExoPlayer,
) : ForwardingPlayer(exo) {

    private val policy = InterruptionPolicy()
    private val ramper = VolumeRamper(exo)
    private val positions = PositionStore(context)
    private val focus = AudioFocusController(context, ::dispatch)
    private val calls = CallStateMonitor(context) { inCall ->
        dispatch(if (inCall) PolicyEvent.CallStarted else PolicyEvent.CallEnded)
    }
    private val routes = RouteMonitor(context, ::dispatch)
    private val modeListener: (FocusMode) -> Unit = { onFocusModeChanged(it) }

    /** True while the user still wants playback (even if a call has it paused). */
    val wantsToPlay: Boolean get() = policy.userWantsPlay

    private val exoListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) dispatch(PolicyEvent.UserPause)
        }

        override fun onPlayerError(error: PlaybackException) {
            PlaybackStatus.log("player error: ${error.errorCodeName}")
            dispatch(PolicyEvent.UserPause)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = publishStatus()
    }

    init {
        exo.addListener(exoListener)
        restorePosition()
        calls.start()
        routes.start()
        AudioSettings.addListener(modeListener)
        publishStatus()
    }

    // ---- Player overrides: all transport goes through the policy -------------------------------

    override fun play() = requestPlay()

    override fun pause() = dispatch(PolicyEvent.UserPause)

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) requestPlay() else dispatch(PolicyEvent.UserPause)
    }

    override fun stop() {
        dispatch(PolicyEvent.UserPause)
        ramper.cancel()
        exo.stop()
    }

    override fun release() {
        teardown()
        exo.release()
    }

    /** Called after the user confirms "play anyway" in the call dialog. */
    fun confirmPlayDuringCall() {
        PlaybackStatus.update { it.copy(confirmPlayDuringCall = false) }
        dispatch(PolicyEvent.UserPlayDuringCallConfirmed)
    }

    // ---- Policy plumbing -------------------------------------------------------------------------

    private fun requestPlay() {
        if (exo.mediaItemCount == 0) return
        dispatch(PolicyEvent.UserPlay)
    }

    private fun dispatch(event: PolicyEvent) {
        val actions = policy.onEvent(event)
        PlaybackStatus.log("$event -> ${actions.joinToString().ifEmpty { "no action" }}")
        actions.forEach(::execute)
        publishStatus()
    }

    private fun execute(action: PolicyAction) {
        when (action) {
            PolicyAction.RequestFocus -> when (focus.request(AudioSettings.focusMode.value)) {
                AudioFocusController.Result.GRANTED -> dispatch(PolicyEvent.FocusGained)
                AudioFocusController.Result.DELAYED -> PlaybackStatus.log("waiting for audio focus")
                AudioFocusController.Result.FAILED -> dispatch(PolicyEvent.FocusDenied)
            }
            PolicyAction.AbandonFocus -> focus.abandon()
            PolicyAction.SavePosition -> savePosition()
            PolicyAction.ApplyDuck -> ramper.rampTo(targetGain(), PolicyTiming.DUCK_RAMP_MS)
            PolicyAction.ConfirmPlayDuringCall -> {
                PlaybackStatus.update { it.copy(confirmPlayDuringCall = true) }
                Toast.makeText(context, "On a call. Open the app to confirm playing.", Toast.LENGTH_SHORT).show()
            }
            is PolicyAction.Pause -> ramper.rampTo(0f, action.fadeMs) { exo.pause() }
            is PolicyAction.Resume -> resumeWithFade(action.fadeMs)
            is PolicyAction.RampIn -> {
                ramper.snapTo(0f)
                ramper.rampTo(targetGain(), action.fadeMs)
            }
        }
    }

    private fun resumeWithFade(fadeMs: Long) {
        // If a fade-out is still running, keep the current level and ramp up from there.
        if (!exo.playWhenReady) ramper.snapTo(0f)
        if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
        if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0)
        exo.play()
        ramper.rampTo(targetGain(), fadeMs)
    }

    private fun targetGain() = if (policy.ducked) PolicyTiming.DUCK_GAIN else 1f

    private fun onFocusModeChanged(mode: FocusMode) {
        PlaybackStatus.log("focus mode -> ${mode.label}")
        if (policy.userWantsPlay && !policy.inCall) execute(PolicyAction.RequestFocus)
    }

    private fun savePosition() {
        val item = exo.currentMediaItem ?: return
        val uri = item.localConfiguration?.uri?.toString() ?: return
        val position = if (exo.playbackState == Player.STATE_ENDED) 0L else exo.currentPosition
        positions.save(uri, item.mediaMetadata.title?.toString(), position)
    }

    private fun restorePosition() {
        if (exo.mediaItemCount > 0) return
        val saved = positions.load() ?: return
        val item = MediaItem.Builder()
            .setMediaId(saved.uri)
            .setUri(saved.uri)
            .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(saved.title).build())
            .build()
        exo.setMediaItem(item, saved.positionMs)
        exo.prepare() // stays paused: restoring never starts audio
    }

    private fun publishStatus() {
        val item = exo.currentMediaItem
        PlaybackStatus.update {
            it.copy(
                title = item?.mediaMetadata?.title?.toString() ?: item?.mediaId,
                inCall = policy.inCall,
                hasFocus = focus.held,
                ducked = policy.ducked,
                playing = policy.playing,
                userWantsPlay = policy.userWantsPlay,
                confirmPlayDuringCall = it.confirmPlayDuringCall && policy.inCall,
            )
        }
    }

    private fun teardown() {
        savePosition()
        ramper.cancel()
        calls.stop()
        routes.stop()
        focus.abandon()
        AudioSettings.removeListener(modeListener)
        exo.removeListener(exoListener)
        PlaybackStatus.update { PolicyStatus() }
    }
}
