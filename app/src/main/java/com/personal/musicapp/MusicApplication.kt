package com.personal.musicapp

import android.app.Application
import com.personal.musicapp.playback.AudioSettings

class MusicApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AudioSettings.init(this)
    }
}
