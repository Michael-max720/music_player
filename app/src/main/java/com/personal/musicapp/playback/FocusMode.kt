package com.personal.musicapp.playback

/** The three agreed audio-focus modes. Exclusive is the default and the only bit-perfect-compatible one. */
enum class FocusMode(val label: String, val bitPerfectCompatible: Boolean) {
    EXCLUSIVE("Exclusive", true),
    DUCK("Duck others", false),
    MIX("Mix", false),
}
