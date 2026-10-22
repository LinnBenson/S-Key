# Microphone capture correction — 2026-10-23

Reported on Samsung Fold8, Android 17 / One UI 9.0: capture fails without screen-recorder microphone enabled, with large levels even while the user is silent. Enabling the screen-recorder microphone makes transcription work. Videos establish the difference in behavior, not the raw audio or definitive OEM root cause.

## Changes

- AI and offline capture share source startup and cleanup. MIC is now preferred to avoid relying on vendor VOICE_RECOGNITION processing. Initialization/start failure releases the first recorder before trying VOICE_RECOGNITION. Permission failure and user cancellation never trigger fallback. No global routing, Bluetooth, speakerphone or audio-mode changes.
- Poll this recorder's system-silenced status at most every 100 ms on API 29+. API 26–28 keep the existing supported capture APIs. No retained recording callback, new thread or recording configuration history.
- Fixed-size signal statistics reject a second of identical nontrivial nonzero samples, two seconds of continuous rail clipping, and incomplete PCM samples. These are conservative corruption checks, not speech detection. Silence alone does not trigger a source switch. A session containing no variation does not proceed to final transcription.
- Waveform measures AC energy (DC removed); it remains an amplitude display, not a claim that speech is present. Existing expiry/reset remains, pending levels are cleared on cleanup.
- Existing bounded buffers and cancellation cleanup remain; new checks retain only scalar counters. No audio files, telemetry, device-name logs, raw audio logs or keys added. AI audio submission still occurs only after explicit stop and accepted capture checks. These checks cannot detect every kind of invalid/noisy recording.

## Verification

All 257 JVM tests, Debug/Release builds and lintDebug pass. The signed APK uses the existing release certificate. Release version: 1.0.82 (versionCode 89). Added regressions for DC-independent waveform, sustained DC/clipping, normal silence followed by signal and byte offsets/alignment.

No ADB device was connected. Samsung hardware fix is not yet verified. Required device comparison: no screen recording; screen recording without mic; screen recording with mic; silence then speech; cancellation/retry; permission denial; another recorder silencing capture; offline and AI modes. This change does not promise simultaneous capture with another app when Android policy forbids it.
