package com.personal.musicapp.playback

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Tiny process-wide settings holder. Replaced by the Room/DataStore settings in M3. */
object AudioSettings {
    private const val PREFS = "audio_settings"
    private const val KEY_MODE = "focus_mode"

    private lateinit var prefs: SharedPreferences
    private val _focusMode = MutableStateFlow(FocusMode.EXCLUSIVE)
    private val listeners = CopyOnWriteArrayList<(FocusMode) -> Unit>()

    val focusMode: StateFlow<FocusMode> = _focusMode.asStateFlow()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _focusMode.value = prefs.getString(KEY_MODE, null)
            ?.let { name -> runCatching { FocusMode.valueOf(name) }.getOrNull() }
            ?: FocusMode.EXCLUSIVE
    }

    fun setFocusMode(mode: FocusMode) {
        if (mode == _focusMode.value) return
        prefs.edit().putString(KEY_MODE, mode.name).apply()
        _focusMode.value = mode
        listeners.forEach { it(mode) }
    }

    fun addListener(listener: (FocusMode) -> Unit) { listeners.add(listener) }
    fun removeListener(listener: (FocusMode) -> Unit) { listeners.remove(listener) }
}
