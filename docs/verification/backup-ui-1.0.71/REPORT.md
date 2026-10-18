# Unified backup UI audit

Date: 2026-10-19. Device: BlueStacks emulator-5554 (Android 13). APK installed preserving application data.

## Fixes

- Activity result callbacks now wait for SettingsModel refresh/work to finish instead of silently dropping the selected save/restore operation when busy. The real save UI regression deliberately holds a background task while returning from DocumentsUI.
- Restore now reads a fixed-depth JSON schema incrementally, rejecting unknown/duplicate fields, unsupported versions, missing categories, excessive rows, malformed types and invalid UTF-8 before commit. It bounds input and string/record sizes instead of first constructing an unrestricted JSON tree.
- File operation closures capture the application resolver and ViewModel, not the old Activity.

## Actual UI verification

A manual DocumentsUI save/open/restore pass succeeded. Automated regression then ran against isolated synthetic content through the production SettingsActivity and real Android DocumentsUI, without mocked picker results:

1. Save one JSON file using the real filename field and Save button, including while the model is busy.
2. Confirm backup completion and inspect the four-category export.
3. Change the isolated local content, select the saved file through OpenDocument and restore it.
4. Verify Chinese/English entries, custom AI prompt, multiline phrase and pin state; recent emoji remains unchanged.
5. Cancel the confirmation, save picker and open picker; verify local bytes unchanged.
6. Actually overwrite the selected test file with malformed JSON, select it and verify failure feedback and unchanged local bytes.
7. Verify the synthetic private marker is absent from logcat. Temporary export is removed by the test.

Three final instrumentation tests passed, including the above UI scenario, data round trip/atomic rejection and memory/row-limit tests. Build, 207 JVM tests and lint passed. Static privacy audit passed.

## Memory and privacy

After warmup, ran 30 export/restore cycles with a roughly 500,000-character phrase:

| Metric | Baseline | After 30 cycles and GC |
| --- | ---: | ---: |
| PSS (KiB) | 106499 | 106040 |
| Java allocated bytes | 5318768 | 5004672 |
| Native allocated bytes | 8659920 | 8352608 |

Weak references checked input streams for retention (at most the current loop-local instance allowed). No sustained heap accumulation was observed. 10,001-record input was rejected without changing local bytes. Exports contain only the four named categories, no API configuration, clipboard or conversation ID. Backup remains intentionally plaintext, disclosed on the screen. No network upload is performed by this feature; the user chooses the document provider/location.

No synthetic input marker appeared in the inspected device logs. The manually created export was removed only after its hash matched the original audit-created file; original user data was not deleted.

## Limits

This verifies the emulator's local Downloads document provider. It does not claim physical-device testing, cloud document-provider compatibility, hours-long stress testing, abrupt power loss, storage exhaustion or process death while a provider is writing. A failed external save may leave an incomplete file; the UI explicitly reports failure and tells the user not to use it.
