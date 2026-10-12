# Offline voice device checks

These tests require a disposable emulator or developer test device with the debug app installed. They do not package model weights or recordings into either APK.

1. Build and install the ABI-matching `app-arm64-v8a-debug.apk` (phone) or `app-x86_64-debug.apk` (x86_64 emulator), and `app-debug-androidTest.apk`.
2. Provide the exact hash-pinned model files in a local folder and upstream `test_wavs/0.wav` from the same model repository. The host fixtures used for verification were under `/path/to/mandarin-test-fixture`.
3. Run `ADB=/path/to/adb tests/push_voice_fixtures.sh MODEL_DIR WAV_FILE`. This streams fixtures directly into target app-private files using `run-as`; it also grants the test app microphone permission.
4. Keep the app foregrounded and run:

```
adb shell am instrument -w -r -e class io.secure.keyboard.voice.OfflineVoiceInstrumentationTest io.secure.keyboard.test/android.test.InstrumentationTestRunner
```

The native test performs three complete recognizer/stream lifecycles and requires the expected Mandarin transcript plus an empty result from a fresh silence stream. The original native heap measurements invoke `System.gc()` before sampling. They are diagnostics, not released-process PSS or a portable leak threshold.

The microphone test starts three real AudioRecord sessions, waits for LISTENING, cancels and releases twice (idempotence), then checks there are no delayed final/interim/error callbacks. It uses real model initialization and a real emulator/device microphone path, although microphone accuracy must still be checked on a physical phone.

## Manual acceptance checks still required
- Airplane mode: import local model; tap the toolbar voice icon, dictate Mandarin and pause; verify finalized segments insert directly once, with only Cancel at the bottom.
- Deny/revoke microphone permission; show a useful setup/error response without crashing.
- Cancel while recording, loading, or finalizing: no pending or late segment inserts after cancellation; already inserted segments remain.
- Change editor/hide keyboard while recording: microphone indicator ends and no stale text appears in the new editor.
- Corrupt or choose a different model/token file: import rejects it before native loading.
- Long speech reaches the 60-second limit and flushes the last segment once; the panel remains with Cancel and no Start/Confirm controls.
- Verify no audio/transcript files appear under app-private storage after sessions. The installed model and test fixtures are intentionally present; remove test fixtures before checking production behavior.
- Validate thermal behavior, latency, and repeated-session memory on the supported physical devices.

## Packaging-only smoke regression

After installing the final compressed-JNI APK and matching instrumentation APK, use the existing model fixtures and run just:

```
adb shell am instrument -w -r -e class 'io.secure.keyboard.voice.OfflineVoiceInstrumentationTest#testNativePackagingSingleUtterance' io.secure.keyboard.test/android.test.InstrumentationTestRunner
```

This separate test confirms that legacy packaging extracted the JNI and ONNX Runtime libraries, checks model/token/WAV hashes, and decodes one complete known utterance with the exact expected Mandarin result. The three-cycle inference and lifecycle coverage is retained; direct-dictation segment callbacks have separate stale/cancel/endpoint tests. For a packaging-only change with identical source/AAR, this targeted regression is sufficient; rerun the full suite for engine, model, session, or native dependency changes. Software emulation can still take several minutes for the one utterance; its elapsed time is not physical-device latency.

## No-forced-GC memory diagnostic

Run the new diagnostic alone in a fresh target process, with the same hash-pinned fixtures. It refuses a baseline that already has sherpa/ONNX JNI libraries loaded. Do not run another instrumentation suite concurrently.

```
adb shell am force-stop io.secure.keyboard
adb shell am instrument -w -r -e class 'io.secure.keyboard.voice.VoiceMemoryDiagnosticTest#testColdLoadedDecodedReleasedMemoryWithoutForcedGc' io.secure.keyboard.test/android.test.InstrumentationTestRunner
adb exec-out run-as io.secure.keyboard cat files/voice-memory-diagnostic.jsonl
```

This records cold, loaded, decoded and released memory, then samples again after 2 and 10 seconds, across 3 cycles without forced GC. It separates process PSS, Java/native allocation and file-backed runtime mappings. Instrumentation overhead remains in this process. Native release is synchronous in this diagnostic; production cancellation reports idle before its worker finishes native cleanup, so the diagnostic does not measure user-visible cancellation latency. See TEST_RESULTS.md for actual results, rather than assuming that adding the test means it passed.

## 0.1.2 direct dictation

- VoiceCommitGuard unit tests cover segment identities, UTF-16 cursor offsets, coalesced selection acknowledgements, cancellation, and editor changes.
- DirectVoiceInputTest exercises the production service with controlled host connections, including asynchronous cursor checks, exactly-once segment commits, cancellation and unsupported editors.
- The native endpoint regression decodes repeated Mandarin samples with actual endpoint/reset and final-flush APIs. It does not simulate a full 60-second microphone recording; that limit remains a physical-device/manual acceptance check.
- Interim hypotheses never call host composing APIs or replace previously inserted speech. Only finalized segments are queued, with a bounded backlog. The original direct-dictation build did not restore punctuation; the explicit 0.1.4 final-segment rule is documented below.

## S Key 0.1.3 held-space acceptance

- Verify the newly installed io.secure.keyboard identity is separately enabled; do not uninstall or clear the old app. Import the model and grant microphone permission for S Key.
- Chinese space is labelled 五笔 but both Wubi and Pinyin still work. Ordinary tap and horizontal cursor swipe must preserve their existing behavior.
- Hold space until listening; release the owning finger; verify the final segment commits once and the voice panel closes after validation. No extra literal space.
- Cancel the touch, hide the IME, switch editors, or rotate while held: recording stops, queued/uncommitted speech is discarded, and late callbacks do not modify another field.
- Hold before a pending Chinese confirmation completes: prior released keys remain ordered. Releasing before listening starts must revoke the pending start.
- Repeat with missing model or microphone denial: setup may open, but returning must not start recording by itself.
- Overlap fingers on the same/different keys before the hold deadline: ordinary tap order remains correct and voice must not start.

## 0.1.4 final-segment comma rule

- Only nonempty finalized segments receive a trailing Chinese comma when their final Unicode code point is not punctuation. Outer whitespace is trimmed; existing ending punctuation, quotes and brackets are preserved.
- Format once before enqueue so VoiceCommitGuard counts the exact committed UTF-16 length. Interim previews/native recognizer output remain unchanged.
- JVM formatter tests cover blank input, Unicode punctuation and supplementary code points. The attached native EditText regression covers sequential commits, duplicate segment IDs, cursor acknowledgments and held-release finalization. Existing cancel/stale-session behavior must remain intact.
- This is a deterministic comma rule, not an automatic semantic punctuation model.
