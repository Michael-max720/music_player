package com.personal.musicapp.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand

object SessionCommands {
    const val ACTION_PLAY_DURING_CALL = "com.personal.musicapp.PLAY_DURING_CALL"
    val PLAY_DURING_CALL = SessionCommand(ACTION_PLAY_DURING_CALL, Bundle.EMPTY)
}
