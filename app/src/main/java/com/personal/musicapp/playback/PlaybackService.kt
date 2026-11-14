package com.personal.musicapp.playback

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Foreground media service (notification, lock screen, headset buttons). Media3 starts and stops
 * the foreground state for us; [PolicyPlayer] decides when audio may actually play.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var policyPlayer: PolicyPlayer? = null

    override fun onCreate() {
        super.onCreate()
        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)                       // media only, never voice/communication
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ false,                    // PolicyPlayer owns focus
            )
            .setHandleAudioBecomingNoisy(false)                    // PolicyPlayer owns this too
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        val player = PolicyPlayer(this, exo)
        policyPlayer = player
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(SessionCallback(player))
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep running only if the user still wants playback (including paused-by-call).
        if (policyPlayer?.wantsToPlay != true) stopSelf()
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        policyPlayer?.release()
        policyPlayer = null
        super.onDestroy()
    }

    private inner class SessionCallback(private val player: PolicyPlayer) : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            // Personal app: only ourselves and system controllers (notification, headset, Bluetooth).
            val allowed = controller.packageName == packageName ||
                session.isMediaNotificationController(controller) ||
                controller.isTrusted
            if (!allowed) return MediaSession.ConnectionResult.reject()
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommands.PLAY_DURING_CALL)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == SessionCommands.ACTION_PLAY_DURING_CALL) {
                player.confirmPlayDuringCall()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { item ->
                val uri = item.localConfiguration?.uri
                    ?: item.requestMetadata.mediaUri
                    ?: Uri.parse(item.mediaId)
                item.buildUpon().setUri(uri).build()
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }
    }
}
