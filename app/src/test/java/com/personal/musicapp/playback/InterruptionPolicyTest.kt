package com.personal.musicapp.playback

import com.personal.musicapp.playback.PolicyAction.AbandonFocus
import com.personal.musicapp.playback.PolicyAction.ApplyDuck
import com.personal.musicapp.playback.PolicyAction.ConfirmPlayDuringCall
import com.personal.musicapp.playback.PolicyAction.Pause
import com.personal.musicapp.playback.PolicyAction.RampIn
import com.personal.musicapp.playback.PolicyAction.RequestFocus
import com.personal.musicapp.playback.PolicyAction.Resume
import com.personal.musicapp.playback.PolicyAction.SavePosition
import com.personal.musicapp.playback.PolicyEvent.BecomingNoisy
import com.personal.musicapp.playback.PolicyEvent.CallEnded
import com.personal.musicapp.playback.PolicyEvent.CallStarted
import com.personal.musicapp.playback.PolicyEvent.DuckStart
import com.personal.musicapp.playback.PolicyEvent.FocusDenied
import com.personal.musicapp.playback.PolicyEvent.FocusGained
import com.personal.musicapp.playback.PolicyEvent.FocusLostPermanent
import com.personal.musicapp.playback.PolicyEvent.FocusLostTransient
import com.personal.musicapp.playback.PolicyEvent.RouteChanged
import com.personal.musicapp.playback.PolicyEvent.UserPause
import com.personal.musicapp.playback.PolicyEvent.UserPlay
import com.personal.musicapp.playback.PolicyEvent.UserPlayDuringCallConfirmed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M1 quality gates for calls, audio focus and routes, run as plain JVM tests. */
class InterruptionPolicyTest {

    private val fadeOut = Pause(PolicyTiming.FADE_OUT_MS)
    private val fadeIn = Resume(PolicyTiming.FADE_IN_MS)

    /** User presses play and the system grants focus. */
    private fun playing(): InterruptionPolicy = InterruptionPolicy().apply {
        onEvent(UserPlay)
        onEvent(FocusGained)
    }

    @Test fun play_requestsFocus_thenFadesIn() {
        val p = InterruptionPolicy()
        assertEquals(listOf(RequestFocus), p.onEvent(UserPlay))
        assertFalse(p.playing)
        assertEquals(listOf(fadeIn), p.onEvent(FocusGained))
        assertTrue(p.playing)
    }

    @Test fun focusDenied_neverPlays() {
        val p = InterruptionPolicy()
        p.onEvent(UserPlay)
        assertEquals(emptyList<PolicyAction>(), p.onEvent(FocusDenied))
        assertFalse(p.playing)
        assertFalse(p.userWantsPlay)
    }

    @Test fun userPause_savesPosition_fades_andAbandonsFocus() {
        val p = playing()
        assertEquals(listOf(SavePosition, fadeOut, AbandonFocus), p.onEvent(UserPause))
        assertFalse(p.playing)
    }

    // ---- calls (cellular, VoIP and call-screening all arrive as CallStarted/CallEnded) ----

    @Test fun callDuringPlayback_pausesWith100msFade_andResumesAfterCall() {
        val p = playing()
        assertEquals(listOf(SavePosition, fadeOut), p.onEvent(CallStarted))
        assertEquals(100L, PolicyTiming.FADE_OUT_MS)
        assertFalse(p.playing)
        assertEquals(listOf(fadeIn), p.onEvent(CallEnded))
        assertTrue(p.playing)
    }

    @Test fun callWhileUserPaused_neverResumes() {
        val p = playing()
        p.onEvent(UserPause)
        assertEquals(emptyList<PolicyAction>(), p.onEvent(CallStarted))
        assertEquals(emptyList<PolicyAction>(), p.onEvent(CallEnded))
        assertFalse(p.playing)
    }

    @Test fun userPausesDuringCall_cancelsResume() {
        val p = playing()
        p.onEvent(CallStarted)
        p.onEvent(UserPause)
        assertEquals(emptyList<PolicyAction>(), p.onEvent(CallEnded))
        assertFalse(p.playing)
    }

    @Test fun playPressedDuringCall_needsConfirmation_andDoesNotPlay() {
        val p = InterruptionPolicy()
        p.onEvent(CallStarted)
        assertEquals(listOf(ConfirmPlayDuringCall), p.onEvent(UserPlay))
        assertFalse(p.playing)
        assertFalse(p.userWantsPlay)
    }

    @Test fun confirmedPlayDuringCall_plays_andTakesFocusWhenCallEnds() {
        val p = InterruptionPolicy()
        p.onEvent(CallStarted)
        assertEquals(listOf(fadeIn), p.onEvent(UserPlayDuringCallConfirmed))
        assertTrue(p.playing)
        assertEquals(listOf(RequestFocus), p.onEvent(CallEnded))
        assertTrue(p.playing)
    }

    @Test fun callOnHold_keepsMusicPaused_untilAllCallsEnd() {
        val p = playing()
        p.onEvent(CallStarted)
        assertEquals(emptyList<PolicyAction>(), p.onEvent(CallStarted)) // second/held call: no new event effect
        assertFalse(p.playing)
        assertEquals(listOf(fadeIn), p.onEvent(CallEnded))
    }

    @Test fun duplicateCallEnded_isIgnored() {
        val p = playing()
        p.onEvent(CallStarted)
        p.onEvent(CallEnded)
        assertEquals(emptyList<PolicyAction>(), p.onEvent(CallEnded))
    }

    @Test fun focusGainedDuringCall_doesNotResume_untilCallEnds() {
        val p = playing()
        p.onEvent(FocusLostTransient)
        p.onEvent(CallStarted)
        assertEquals(emptyList<PolicyAction>(), p.onEvent(FocusGained))
        assertFalse(p.playing)
        assertEquals(listOf(fadeIn), p.onEvent(CallEnded))
    }

    @Test fun callEndsBeforeFocusGain_waitsForFocus() {
        val p = playing()
        p.onEvent(CallStarted)
        p.onEvent(FocusLostTransient)
        assertEquals(emptyList<PolicyAction>(), p.onEvent(CallEnded))
        assertFalse(p.playing)
        assertEquals(listOf(fadeIn), p.onEvent(FocusGained))
    }

    // ---- focus loss and regain ----

    @Test fun transientFocusLoss_thenGain_resumes() {
        val p = playing()
        assertEquals(listOf(SavePosition, fadeOut), p.onEvent(FocusLostTransient))
        assertEquals(listOf(fadeIn), p.onEvent(FocusGained))
    }

    @Test fun userPausesDuringTransientLoss_doesNotResume() {
        val p = playing()
        p.onEvent(FocusLostTransient)
        p.onEvent(UserPause)
        assertEquals(emptyList<PolicyAction>(), p.onEvent(FocusGained))
        assertFalse(p.playing)
    }

    @Test fun permanentFocusLoss_pauses_andNeverResumes() {
        val p = playing()
        assertEquals(listOf(SavePosition, fadeOut, AbandonFocus), p.onEvent(FocusLostPermanent))
        assertEquals(emptyList<PolicyAction>(), p.onEvent(FocusGained))
        assertEquals(emptyList<PolicyAction>(), p.onEvent(CallStarted))
        assertEquals(emptyList<PolicyAction>(), p.onEvent(CallEnded))
        assertFalse(p.playing)
    }

    @Test fun navigationPrompt_ducksThenRestores() {
        val p = playing()
        assertEquals(listOf(ApplyDuck), p.onEvent(DuckStart))
        assertTrue(p.ducked)
        assertTrue(p.playing)
        assertEquals(listOf(ApplyDuck), p.onEvent(FocusGained))
        assertFalse(p.ducked)
        assertTrue(p.playing)
    }

    // ---- routes ----

    @Test fun headphonesUnplugged_pausesImmediately_andNeverAutoResumes() {
        val p = playing()
        assertEquals(listOf(SavePosition, Pause(0)), p.onEvent(BecomingNoisy))
        assertEquals(emptyList<PolicyAction>(), p.onEvent(FocusGained))
        assertEquals(emptyList<PolicyAction>(), p.onEvent(CallEnded))
        assertFalse(p.playing)
    }

    @Test fun routeChange_fadesInWhilePlaying_onlyWhilePlaying() {
        val p = playing()
        assertEquals(listOf(RampIn(PolicyTiming.ROUTE_FADE_IN_MS)), p.onEvent(RouteChanged))
        p.onEvent(UserPause)
        assertEquals(emptyList<PolicyAction>(), p.onEvent(RouteChanged))
    }
}
