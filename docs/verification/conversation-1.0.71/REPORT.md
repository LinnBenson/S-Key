# 1.0.71 Single-input conversation verification

## Behavior

The original text editor is the only message input. The supplement row, shortcut prompts, submit control and Send asset were removed. Initial messages use the selected task prompt; continuations send only the new input and latest response ID. Successful completion updates the ID and timestamp and clears the submitted draft only if it has not been edited (including composition) since submission.

Session identity includes endpoint/model/credential identity, task, custom preset identity and effective prompt. Type and prompt changes reset the conversation. The original title displays the actual ID's first four characters and updates on reset or successful response. History navigation does not change the latest continuation ID. All result history is removed on any assistant exit; ordinary exits preserve the encrypted session.

The encrypted AES-GCM envelope uses Android Keystore and noBackupFilesDir. It stores ID, hashed scope, type and last-success time, not the transcript. Access validates a rolling 12-hour expiry and rejects clock rollback. Clear and double-backspace revoke the session; a store revision prevents an outstanding request from restoring a reset session. Explicit provider expiry errors revoke the ID without resubmission; ordinary failures preserve the input/session. Error responses have size and read-time bounds.

Left/right history chevrons use mirrored matching artwork. The removed row's height is reclaimed, and the cards' bottom margin is zero so they meet the toolbar.

## Verification

- 207 JVM tests passed, including expiry boundary, clock rollback, scope identity and draft edits while streaming.
- 28 device regressions passed on emulator-5554, including Responses streaming/payload tests, multi-turn requests, new drafts during generation, immediate title reset on type change, reopening, history isolation, failure/expiry, explicit reset, encrypted round-trip/corruption/revoked saves, candidates, insertion and voice cancellation.
- `assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest`, `lintDebug`: successful. Lint: 0 errors, 81 warnings.
- `tests/privacy_audit.py`: passed.
- Network tests use synthetic requests and mocked HTTPS connections; no live cloud generation is claimed. Cross-instance encrypted recovery was tested; no device reboot or prolonged heap profiling was performed.
- The final spacing-only adjustment was rebuilt separately after the above tests; it removes the card bottom margin without changing behavior.
- Final debug APK installed with `adb install -r`. Production UI opened in the simulator and screenshot inspected: supplement row absent, paired chevrons visible, content cards end at the toolbar boundary.

## Toolbar Back follow-up

The toolbar Back action now explicitly resets the encrypted AI conversation (ID, type and timestamp) before exiting. Google translation Back does not delete an unrelated saved AI session. Two targeted device regressions passed: Back cleanup/late-result rejection and the complete persistent-conversation flow. Updated debug APK installed preserving app data.

## Bug, logic, privacy and memory audit

Fixed two races:

- Delayed selected-text import now checks the AI draft epoch as well as the translation revision, preventing host selection from overwriting newly typed original text. The regression covers both AI and translation modes.
- Conversation ID and revocation generation are read atomically, using the same configuration scope as the outgoing request. Corruption also revokes pending saves. A store regression verifies that a reset rejects a save using the old snapshot generation.

Latest build, 207 JVM tests and lint completed successfully (0 errors, 82 warnings). The Cursor recycling warning in ModelRepository was inspected: its query is enclosed in Kotlin `use`, including exceptional paths.

Three 60-cycle panel stress runs retained zero old view instances after collection. In the final run, PSS changed from 138816 to 139235 KiB (+419 KiB); Java allocation fell from 24000600 to 19703176 bytes and Native allocation from 10697024 to 10660336 bytes. These short emulator runs show no sustained accumulation in the exercised panel lifecycle; they do not establish absence of all leaks.

Static privacy checks passed. The synthetic input marker was absent from device logs; synthetic input and plaintext configuration checks passed. No actual user input or credential was printed. Tests use mocked network responses; real provider availability, long audio sessions, physical-device overnight usage and full heap-dump analysis remain outside this audit.

Final clean rebuild was installed preserving app data. All 78 explicitly selected device regressions passed. An earlier run reported a reflection-method mismatch against the previous installed build; rebuilding and reinstalling both APKs resolved it. Native screenshot capture was skipped because of the known emulator screenshot issue.
