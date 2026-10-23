# Open dictionary replacement — 1.0.96

- Default sources fetched from pinned GitHub revisions; URLs, input/output hashes, entry counts and conversion changes are in `third_party/open_dictionaries/manifest.json`.
- APK base TSV hashes match all three converted sources. Counts: Wubi 136916, Pinyin 65125, English 25000. Six protected names and same-name replacement remain supported.
- Bundled dictionaries match the current source manifest. Upstream attribution and applicable licenses are retained.
- 261 JVM tests and four full-data/index Python checks passed. Eight device functional tests plus the corrected resource-reclamation test passed (nine device cases total).
- Twelve successive apply cycles: every previous SQLite connection/file descriptor closed; old snapshot weak references cleared after GC; only the current snapshot is open after lookup. All three languages return new data and do not fall back to packaged words. Superseded compiled files are removed after readers close.
- Controlled test process heap: Java 2.23 → 2.24 MiB; native 3.66 → 3.67 MiB. These are test-process observations, not a guarantee of zero Android/OS caching.
- Reclamation test was corrected to avoid dereferencing the weak reference immediately before GC, which could itself keep a temporary strong reference alive on ART. Application code was unchanged between the failed and successful reclamation test.
- Root emulator release install succeeded. Actual soft-key input `wq` displayed `你` as the first candidate.
- Packaged default assets remain on disk for restore-default functionality; applying a custom dictionary releases old runtime handles/references and does not load a fallback dictionary.
