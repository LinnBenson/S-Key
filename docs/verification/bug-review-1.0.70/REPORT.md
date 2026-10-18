# 1.0.70 Bug and logic review

Reviewed recent AI supplement submission, response ownership, editor focus, candidate rendering, cancellation, and streaming paths.

## Fixed

- Editing a new supplement while generation was active called `stopAiRequest()` and invalidated the active response. Supplement draft edits now leave the current request running; original text edits still cancel and invalidate it.
- A failed supplement request left its submitted draft cleared and removed the usable parent result. Failure now restores the completed parent result and restores the submitted requirement only when there is no newer draft.
- Completion forced focus state back to the original editor. Completion now retains the current editing target, including a new supplement draft.

## Verification

- `assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug --offline`: passed.
- 204 unit tests: passed.
- 18 explicitly selected Android instrumentation tests: passed. These cover Responses payloads and stream limits/cancellation, source/credential binding, new original conversations, supplement chaining, history, editor/candidate continuity, request queue cleanup, and the new draft/failure regressions.
- `tests/privacy_audit.py`: passed.
- Lint: 0 errors, 82 warnings. Existing warnings are not treated as proof of application correctness.
- Debug APK installed with `adb install -r` on emulator-5554, preserving app data.

Network transport tests use synthetic inputs and mocked HTTPS connections. This review does not establish real-provider availability, prolonged-device memory stability, or absence of all application bugs.
