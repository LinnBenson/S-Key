# Static English completion data

The application packages 133,051 English word forms: 25,000 frequency-ranked common words in
`app/src/main/assets/dictionaries/english_frequency.tsv` and 108,051 additional words in `english_extended.tsv`.
Frequency ranking comes from Hermit Dave's **FrequencyWords / OpenSubtitles
2018**; a **SCOWL 2020.12.07** spelling filter removes most names, acronyms,
malformed tokens, and non-word strings. Both US and UK spellings are eligible.

The derived data is **CC BY-SA 4.0**. Redistribution must retain attribution,
the license, and SCOWL's original copyright notices. Complete notices ship in
the APK next to the dictionary:

- `ENGLISH-NOTICE.txt`: credits, sources, changes, hashes, and limitations
- `ENGLISH-FREQUENCYWORDS-README.txt`: upstream statement of content licensing
- `ENGLISH-SCOWL-COPYRIGHT.txt`: complete, unchanged SCOWL release notice
- `LICENSE-ENGLISH-CC-BY-SA-4.0.txt`: complete Creative Commons license

## Rebuild

Run from the repository root:

```sh
python3 third_party/english/rebuild.py --cache /path/to/source-cache
```

On the first run, this developer-only script downloads two public data files
from the official sources. It checks their SHA-256 values before parsing. If
both files already exist in the supplied cache, it performs no network access.
It does not execute upstream code or extract archive members to the filesystem.
It regenerates the TSV and `third_party/english/manifest.json` deterministically.

Source selection: SCOWL's lowercase ordinary `english`, `american`, `british`,
and `british_z` word lists through size 60, excluding names/upper words,
abbreviations, contractions, special lists, and non-ASCII tokens. Explicitly
admit the seven weekday names and twelve month names, because SCOWL capitalizes
these everyday words. Intersect
with the pinned FrequencyWords English 2018 top-50k file. Retain the 25,000
highest corpus counts; equal counts use alphabetical order. No handwritten
word boosts, previous user text, or generated frequencies are included; calendar
names keep their unchanged corpus counts.

## ESDB extension

The extension uses the official [ESDB (formerly SCOWL)](https://wordlist.aspell.net/)
US and GB large word lists, pinned to `en-wl/wordlist-diff` commit
`7f2f4078354752045ee16a041605686561122185`. Unchanged inputs and the full upstream
Copyright are vendored under `esdb/`; SHA-256 values are in `esdb/manifest.json`.
ESDB permits redistribution and modification with its notices retained.
The complete notice ships as `assets/licenses/ESDB-Copyright.txt`.

Rebuild offline with `python3 third_party/english/rebuild_extended.py`.
Only lowercase ASCII words of 2–40 letters are retained, matching the current
completion engine. Take the union of US/GB, remove the existing common words,
and sort lexically. Extension weights are 1 (fallback rank, not measured corpus
frequencies). All 25,000 existing corpus weights remain unchanged, so uncommon
additions cannot displace frequent completions. New coverage includes
asynchronous, refactoring, serialization, neuroscience, virtualization and
algorithmic. Apostrophes, accents, hyphens and capitalized proper nouns remain
outside this completion format; the collection is larger, not exhaustive.

## Runtime and integration

Both original TSV files appear under built-in English dictionaries and can be
edited, exported or deleted. Changes take effect after successful compilation.
`CompiledDictionary` queries the read-only SQLite snapshot; `EnglishLexicon`
is retained as a reference reader for tests. Completion remains a strict prefix
extension with typed casing preserved, not typo correction or next-word prediction.
Existing custom English words still apply immediately.

## Deterministic examples

- `w`: what, we, was, with, well, will, want, who
- `compu`: computer, computers, compulsive, compulsion, compute, compulsory,
  computing, computerized
- `hel`: help, hello, hell, helping, helped, held, helps, helicopter
- `Hel`: Help, Hello, Hell, Helping, Helped, Held, Helps, Helicopter
- empty input and `hello `: no results

Pure JUnit coverage is in `EnglishLexiconTest.kt`: ranking, first-letter
completion, strict current-word boundaries, casing and Turkish locale,
limits/ties, malformed input/rows, deduplication, reader closure, a bounded
ranking oracle, actual packaged vocabulary across daily/technical prefixes, and
all weekday/month names.
