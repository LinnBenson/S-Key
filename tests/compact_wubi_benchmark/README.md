# Reproduce compact Wubi host diagnostics

Requires Python 3.8+, a JDK 17+ (JAVA_HOME or PATH), and already-built production
Kotlin classes plus Kotlin stdlib 2.0.21. No extra library or network request is
made by this harness. Prepare the Android build normally first. From project root:

```sh
./gradlew compileDebugKotlin
python3 scripts/build_compact_wubi.py --source-root . --verify-only
python3 tests/compact_wubi_benchmark/run.py --mode all --heap-repeats 2
```

Stop the emulator and other build/benchmark processes first. The harness runs
one JVM at a time with `-Xmx512m -Xms64m -XX:ActiveProcessorCount=1`; it does not
run Gradle or start/stop the emulator itself. Output goes to
`build/compact_wubi_benchmark` and may be redirected with `--output`.

Use `--project-root`, `--classes`, `--classpath`, `--java` and `--javac` for a
different checkout or toolchain. `--classpath` is a platform-separated list of
extra jars, normally just the Kotlin stdlib jar; production and harness class
directories are added automatically. The default class directory is
`app/build/tmp/kotlin-classes/debug`. Stdlib discovery checks GRADLE_USER_HOME,
the checkout's `.gradle-home`, and the user's `.gradle` cache. Missing prerequisites
are explicit errors, never automatic downloads.

`--mode parity`, `--mode heap` or `--mode latency` runs one category.

The detailed counts below describe the original 0.1.3-era benchmark, not a newly
executed benchmark of 0.1.8. Version 0.1.7 adds eight local entries: current counts
are 441,796 rows, 185,875 exact buckets and 305,579 prefix references.
Version 0.1.8 moves those entries into a marked high-priority source and reorders
only the corresponding complete-code exact groups during offline compilation.
The source preparation reads all manifest-listed inputs and separately exports
derived priorities for the parity oracle. Do not encode these priorities as
larger Reader weights: that would also change proper-prefix ordering.
Rerun diagnostics explicitly before claiming new memory or latency results.

## What each source does

- `prepare_reference.py`: verifies all manifest-listed pinned source hashes and creates a
  temporary, deduplicated, stable-weight legacy Reader input. It exits before
  JVM startup, so Python indexes/source strings are not retained during Java
  measurements. Its required `--priorities-output` path receives a separate
  text/code TSV derived from every source marked `exact_priority=true`, in
  source/file order. The wrapper supplies both output paths. It does not modify
  production assets or manually duplicate the eight-pair mapping.
- `CompactParityDiagnostic.java`: compares every one of 185,874 exact-code
  buckets, all 441,788 rows and automatic first candidates; 16,103 prefixes;
  4,480 sampled complete candidate queries; explicit fuzzy/segmentation cases;
  and the full 472-entry `khkh` bucket against the legacy Reader constructor.
  Those are the historical run's counts. For current data it additionally uses
  `ExactPriorityReference.java`, a test-only oracle that moves the derived
  priority entries ahead of each exact group and leaves the Reader's original
  prefixes intact. All priority codes receive explicit limited/expanded candidate
  checks, and every exact group and automatic first candidate is compared.
- `CompactMemoryDiagnostic.java`: each JVM creates exactly one retained engine
  with identical Pinyin/English data. Reader/file/channel loader locals return
  before explicit GC and heap samples. There is no comparison oracle or full
  source byte array retained. Compact mode uses FileChannel.map, not readAllBytes.
- `CompactLatencyDiagnostic.java`: checks candidate equality, warms both engines,
  then runs alternating-order 10,000-query passes for exact-first and mixed
  candidate lookups. It intentionally keeps both engines for timing comparison;
  do not use its process memory to estimate either engine's memory footprint.
  Its historical query set excludes the eight priority codes; raw Reader timing
  is not a parity oracle for priority-affected codes. Do not add such codes to
  this timing comparison without redesigning the reference and reporting scope.

Linux additionally exposes mapping virtual size/RSS/PSS and process VmHWM/VmRSS
through `/proc/self`. On other platforms those optional process measurements
are reported unavailable; used Java heap and correctness checks still work.

## Observed host result (2026-10-12)

Same JDK 17, explicit GC, no concurrently running emulator:

- Original base Wubi + existing Pinyin/English: 50.116 MiB used heap.
- All five supplements through old object indexes: 96.596 MiB used heap.
- Compact mapped Wubi, all words retained: 21.543 MiB mean used heap in two runs.
- Heap reduction versus the unoptimized merge: 75.053 MiB (77.70%).
- Mapping separately: 8.227 MiB virtual, 64 KiB resident just after loading,
  about 2.23 MiB after eleven fixed exact lookups. More queries may touch all
  pages. These mapped bytes are not included in the Java used-heap figure.
- Warm compact exact-first p95: 0.28–0.67 microseconds; mixed p95: 63–82
  microseconds. Reference p95: 0.11–0.58 and 62–74 respectively. Mapping has a
  small expected decode cost; this is not a claim of faster lookup.

These figures exclude Android, UI, voice/native model and runtime overhead.
They are not Android PSS, total keyboard RAM or phone latency. Validate the
final APK and real/emulated device separately. Results vary by JVM/hardware;
the numbers are diagnostic observations, not pass/fail thresholds.

The original Java harness sources were compiled/run in the isolated historical
validation workflow. The 0.1.8 priority-oracle adaptation has not been executed
as part of that historical run; its full end-to-end invocation requires the
completed Android build. This README does not claim new memory or latency results.
