# Recording focus and optional preprocessing

## Scope

AI and offline microphone capture request AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE immediately before starting AudioRecord. They abandon it on explicit stop, cancellation, capture completion, failed startup and cleanup, before network recognition or offline final decoding. No media key dispatch, forced playback restoration, global volume changes, communication mode changes or added foreground service/permissions.

Android 15+ can reject focus for an IME. Rejection permits quiet capture, but ongoing media playback asks the user to pause. Playback is checked at most every 100 ms while capture continues without a granted focus. Focus loss interrupts the capture. Each lease owns its callback; delayed callbacks from a released lease cannot poison the next recording. Delayed grants are disabled.

AEC and NS are attached only to this recorder's session, if the device reports support and the effect can be created, controlled and enabled successfully. Unsupported/failed effects are released and basic capture proceeds. No AGC is added. API success does not establish perceptual quality or guarantee that another app's playback will be cancelled acoustically.

## Privacy and memory

No new audio files, diagnostic uploads, raw audio/device logging or permission requests. Existing bounded PCM buffers and erasure remain. One focus lease and at most two effect handles per active capture; no new worker/timer thread. Effect release failures do not skip focus abandonment, and failed focus acquisition is abandoned exactly once. Platform release failures are contained, though system recovery cannot be guaranteed if the OS audio service is failing.

## Checks

Regression tests cover denial while media plays, denial in quiet with later playback, transient loss, release exactly once, request/release exceptions and stale callbacks after restart. All 262 JVM tests and the Debug build passed; Android lint completed with no errors.

No ADB device is connected. Actual media pause/resume, effects quality and Android 17 IME focus eligibility need hardware checks; no claim of hardware validation. Compare playback vs manually paused playback, screen recorder mic on/off, both voice modes, repeated cancel/retry and Bluetooth/headset routing. Some players will require manual resume after focus returns; S Key does not force them to play.
