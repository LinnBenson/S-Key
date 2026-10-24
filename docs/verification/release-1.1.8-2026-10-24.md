# S-Key 1.1.8 verification

## Changes

- Clipboard defaults to timed cleanup, 24 hours; retention is clamped to 1–72 hours. The unpinned count is clamped to 1–100, default 10, independently of retention mode. Explicit saved preferences remain respected within the new bounds.
- Chinese editor preview and automatic spacing default on. Chinese and English association switches default on; disabling English association clears candidates and bypasses dictionary lookup while directly committing typed letters.
- The vibration strength slider explains the system-feedback behavior at 10%.
- Clipboard, phrases and recent emoji have separate AES-GCM AtomicFile stores under noBackupFilesDir/private-content. Migration writes encrypted copies before removing plaintext, is retryable, and refuses to discard legacy data when an existing encrypted migration copy fails authentication. Clipboard mutations no longer parse or rewrite dictionary content.json.
- Shared synchronized Android Keystore key creation covers all instances of API, conversation and private content stores.
- AI streaming bounds apply to individual buffered SSE events/lines and generated text rather than cumulative protocol overhead. Existing output, cancellation and duration limits remain.
- Offline models cache successful verification using size and mtime, including an import-created persistent stamp. A changed stamp forces checksum verification. The single voice worker lazily retains its recognizer, uses a fresh stream per capture, and serializes disposal on trim/destroy or failure. Native AudioRecord.stop/release also run on the capture worker, after BlueStacks testing exposed a blocking native stop on the main thread.
- Original code is Apache-2.0 under the root LICENSE and NOTICE. Kotlin 2.2.10 and annotations 23.0.0 notices match resolved dependencies. Gradle generates the resolved runtime inventory; the app displays all packaged license, copyright and notice texts.

## Validation

- JVM suite: 256 tests passed, no failures or skips.
- Release uses the existing S-Key PKCS12 signing identity; v2/v3 signatures and packaged dictionary catalogs are verified by scripts/build_release.py.
- Device: BlueStacks Air, Android device model SM-G998B, upgraded with existing application data retained.
- Device regression: 98 storage/settings/AI/model-verification tests passed; 2 association/UI tests passed; 5 native voice tests passed (105 total).
- Native voice coverage includes public-fixture Mandarin recognition, recognizer identity reuse, trim disposal, canceled callback suppression, rapid cancel/restart, and revocation of a queued microphone start. Tests run in a foreground Activity and await asynchronous native cleanup between independent sessions. The native suite took 80.176 seconds on BlueStacks; vendor audio stopping can be slow, and is kept off the main thread.
- Manual UI check: the in-app license page loads and displays packaged notices and full license text.
- Final APK: versionName 1.1.8, versionCode 114, non-debuggable. License assets match the current sources. No public model or test waveform is packaged in production APKs.
- Certificate SHA-256: `8B:5E:26:9F:48:C4:51:CD:39:2D:A6:55:16:5D:2D:F8:F1:CB:8B:C0:68:29:08:D6:09:21:8B:FB:80:98:03:63`.

Local logs (workspace diagnostics directory): revision-complete-test-build.log, revision-complete-release-build.log, revision-final-data-tests.log, revision-final-input-ui-tests.log, revision-final-voice-tests.log.

## Final APK SHA-256

```text
33705425280dc8380fd67fde19460a3c44503bd68d2554938eec21cb79b3b5f8  S-Key-1.1.8-arm64-v8a-release.apk
d43acbd645c9022b523339d11fc69b1814edd60dd7a4be459ab6240a57e7af16  S-Key-1.1.8-x86_64-release.apk
0366d682a25fa4154d26617f659e2329fad2696c287e737b513e716dbace51c8  S-Key-1.1.8-universal-release.apk
```

Public voice fixtures are supplied only through -PvoiceFixtureDir to androidTest assets. Production APKs do not include those test models or recordings. Source audio recognition tests do not exercise a live cloud AI service; SSE tests use deterministic streams.
