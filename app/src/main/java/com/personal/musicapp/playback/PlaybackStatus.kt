package com.personal.musicapp.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PolicyStatus(
    val title: String? = null,
    val inCall: Boolean = false,
    val hasFocus: Boolean = false,
    val ducked: Boolean = false,
    /** Audio is actually playing right now. */
    val playing: Boolean = false,
    /** The user wants playback (stays true while paused by a call or a transient focus loss). */
    val userWantsPlay: Boolean = false,
    val confirmPlayDuringCall: Boolean = false,
    val log: List<String> = emptyList(),
)

/** Process-local bridge from the playback service to the UI (service and UI share a process). */
object PlaybackStatus {
    private const val MAX_LOG = 40
    private val _state = MutableStateFlow(PolicyStatus())
    val state: StateFlow<PolicyStatus> = _state.asStateFlow()

    fun update(transform: (PolicyStatus) -> PolicyStatus) = _state.update(transform)

    fun log(line: String) = _state.update { it.copy(log = (it.log + line).takeLast(MAX_LOG)) }
}
