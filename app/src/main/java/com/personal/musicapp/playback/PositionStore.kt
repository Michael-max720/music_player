package com.personal.musicapp.playback

import android.content.Context

data class SavedPosition(val uri: String, val title: String?, val positionMs: Long)

/** Last track and position, so the service can restore after the process is killed. Room replaces this in M3. */
class PositionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("playback_position", Context.MODE_PRIVATE)

    fun save(uri: String, title: String?, positionMs: Long) {
        prefs.edit().putString(KEY_URI, uri).putString(KEY_TITLE, title).putLong(KEY_POS, positionMs).apply()
    }

    fun load(): SavedPosition? {
        val uri = prefs.getString(KEY_URI, null) ?: return null
        return SavedPosition(uri, prefs.getString(KEY_TITLE, null), prefs.getLong(KEY_POS, 0L))
    }

    private companion object {
        const val KEY_URI = "uri"
        const val KEY_TITLE = "title"
        const val KEY_POS = "pos"
    }
}
