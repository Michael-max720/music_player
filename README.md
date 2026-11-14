# Personal Music: M1 Foundation

Project skeleton, foreground playback service, audio focus and call handling.
Stand-in player: Media3 ExoPlayer behind `PolicyPlayer`. M2 swaps in the native Oboe engine.

## Open and run
1. Open the folder in Android Studio (JDK 17+). If there is no `gradlew`, run `gradle wrapper --gradle-version 8.13` once.
2. Run the unit tests: `./gradlew :app:testDebugUnitTest` (the call/focus/route gates).
3. Install on a phone (Android 12+), pick any audio file, press Play.

## Where things live
- `playback/InterruptionPolicy.kt`: pure state machine, all call/focus/route rules, fully unit-tested.
- `playback/PolicyPlayer.kt`: wraps the player; every play/pause (UI, notification, headset) goes through the policy.
- `playback/AudioFocusController.kt`: Exclusive / Duck / Mix; standard media usage only.
- `playback/CallStateMonitor.kt`: cellular, VoIP, call screening via audio mode (no phone permission).
- `playback/RouteMonitor.kt`: pause on unplug, fade on route change.
- `playback/PlaybackService.kt`: Media3 foreground media service.
- `ui/PlayerScreen.kt`: probe screen with the live event log for the manual test matrix.

## Manual gates still to run on a real phone
Incoming call during playback, call on hold, VoIP call (WhatsApp/Meet), speakerphone, Bluetooth headset,
headphone unplug, route change, focus loss and regain (play another app), navigation prompt ducking,
process kill then position restore, all three focus modes. The event log on screen records each step.

## Deferred on purpose
- Per-device safe starting volume (needs the device/route database, M3/M4).
- Sample-accurate fades and DSP: M2. Current fades are handler-timed.
- Call in the middle of a DJ crossfade: M7 (the policy already exposes one pause path both decks can use).
- Sample-rate-switch muting, DSD, USB: M2/M4.
