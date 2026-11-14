package com.personal.musicapp.playback

/** All timings in one place so the quality gates can assert on them. */
object PolicyTiming {
    const val FADE_OUT_MS = 100L       // call / focus-loss pause
    const val FADE_IN_MS = 150L        // start and resume
    const val ROUTE_FADE_IN_MS = 400L  // output device added/removed while playing
    const val DUCK_RAMP_MS = 150L
    const val DUCK_GAIN = 0.25f        // about -12 dB
}

sealed interface PolicyEvent {
    data object UserPlay : PolicyEvent
    data object UserPlayDuringCallConfirmed : PolicyEvent
    data object UserPause : PolicyEvent
    data object CallStarted : PolicyEvent
    data object CallEnded : PolicyEvent
    data object FocusGained : PolicyEvent
    data object FocusLostTransient : PolicyEvent
    data object FocusLostPermanent : PolicyEvent
    data object DuckStart : PolicyEvent
    data object FocusDenied : PolicyEvent
    data object BecomingNoisy : PolicyEvent
    data object RouteChanged : PolicyEvent
}

sealed interface PolicyAction {
    data object RequestFocus : PolicyAction
    data object AbandonFocus : PolicyAction
    data object SavePosition : PolicyAction
    data object ApplyDuck : PolicyAction
    data object ConfirmPlayDuringCall : PolicyAction
    data class Pause(val fadeMs: Long) : PolicyAction
    data class Resume(val fadeMs: Long) : PolicyAction
    data class RampIn(val fadeMs: Long) : PolicyAction
}

/**
 * Pure state machine for calls, audio focus, ducking and route changes. No Android imports, so every
 * quality gate in the plan (call during playback, hold, VoIP, focus loss and regain, route change)
 * is a plain JVM unit test.
 *
 * Rules (from the agreed plan):
 *  - A call pauses with a ~100 ms fade and remembers the position.
 *  - Resume only if the user still wanted playback; never resume during a call.
 *  - Play pressed during a call needs explicit confirmation.
 *  - A permanent focus loss or unplugged output never auto-resumes.
 */
class InterruptionPolicy {
    var playing = false
        private set
    var userWantsPlay = false
        private set
    var inCall = false
        private set
    var ducked = false
        private set

    private var focusLost = false
    private var callOverride = false

    fun onEvent(event: PolicyEvent): List<PolicyAction> = when (event) {
        PolicyEvent.UserPlay -> userPlay()
        PolicyEvent.UserPlayDuringCallConfirmed -> userPlayConfirmed()
        PolicyEvent.UserPause -> userPause()
        PolicyEvent.CallStarted -> callStarted()
        PolicyEvent.CallEnded -> callEnded()
        PolicyEvent.FocusGained -> focusGained()
        PolicyEvent.FocusLostTransient -> focusLostTransient()
        PolicyEvent.FocusLostPermanent -> focusLostPermanent()
        PolicyEvent.DuckStart -> duckStart()
        PolicyEvent.FocusDenied -> focusDenied()
        PolicyEvent.BecomingNoisy -> becomingNoisy()
        PolicyEvent.RouteChanged -> routeChanged()
    }

    private fun pauseNow(fadeMs: Long = PolicyTiming.FADE_OUT_MS): List<PolicyAction> {
        playing = false
        return listOf(PolicyAction.SavePosition, PolicyAction.Pause(fadeMs))
    }

    private fun userPlay(): List<PolicyAction> {
        if (inCall) {
            if (!callOverride) return listOf(PolicyAction.ConfirmPlayDuringCall)
            userWantsPlay = true
            if (playing) return emptyList()
            playing = true
            return listOf(PolicyAction.Resume(PolicyTiming.FADE_IN_MS))
        }
        userWantsPlay = true
        return if (playing) emptyList() else listOf(PolicyAction.RequestFocus)
    }

    private fun userPlayConfirmed(): List<PolicyAction> {
        if (inCall) callOverride = true
        return userPlay()
    }

    private fun userPause(): List<PolicyAction> {
        userWantsPlay = false
        focusLost = false
        ducked = false
        val out = mutableListOf<PolicyAction>()
        if (playing) out += pauseNow()
        out += PolicyAction.AbandonFocus
        return out
    }

    private fun callStarted(): List<PolicyAction> {
        if (inCall) return emptyList()
        inCall = true
        return if (playing) pauseNow() else emptyList()
    }

    private fun callEnded(): List<PolicyAction> {
        if (!inCall) return emptyList()
        inCall = false
        if (callOverride) {
            callOverride = false
            // The user chose to play over the call without focus; take focus properly now.
            return if (playing) listOf(PolicyAction.RequestFocus) else emptyList()
        }
        if (userWantsPlay && !playing && !focusLost) {
            playing = true
            return listOf(PolicyAction.Resume(PolicyTiming.FADE_IN_MS))
        }
        return emptyList()
    }

    private fun focusGained(): List<PolicyAction> {
        focusLost = false
        val out = mutableListOf<PolicyAction>()
        if (ducked) {
            ducked = false
            if (playing) out += PolicyAction.ApplyDuck
        }
        if (inCall && !callOverride) return out
        if (userWantsPlay && !playing) {
            playing = true
            out += PolicyAction.Resume(PolicyTiming.FADE_IN_MS)
        }
        return out
    }

    private fun focusLostTransient(): List<PolicyAction> {
        focusLost = true
        return if (playing) pauseNow() else emptyList()
    }

    private fun focusLostPermanent(): List<PolicyAction> {
        userWantsPlay = false
        focusLost = false
        ducked = false
        val out = mutableListOf<PolicyAction>()
        if (playing) out += pauseNow()
        out += PolicyAction.AbandonFocus
        return out
    }

    private fun duckStart(): List<PolicyAction> {
        ducked = true
        return if (playing) listOf(PolicyAction.ApplyDuck) else emptyList()
    }

    private fun focusDenied(): List<PolicyAction> {
        userWantsPlay = false
        return if (playing) pauseNow() else emptyList()
    }

    private fun becomingNoisy(): List<PolicyAction> {
        userWantsPlay = false // never auto-resume after the output went away
        return if (playing) pauseNow(fadeMs = 0) else emptyList()
    }

    private fun routeChanged(): List<PolicyAction> =
        if (playing) listOf(PolicyAction.RampIn(PolicyTiming.ROUTE_FADE_IN_MS)) else emptyList()
}
