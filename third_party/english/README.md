# Static English completion data

The application packages 25,000 frequency-ranked words in
`third_party/open_dictionaries/skey_english.tsv`.
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

## Provenance

The original selection parameters are recorded in `manifest.json`. Current reproducible packaging uses the pinned upstream file and converter in `third_party/open_dictionaries/`.

Source selection: SCOWL's lowercase ordinary `english`, `american`, `british`,
and `british_z` word lists through size 60, excluding names/upper words,
abbreviations, contractions, special lists, and non-ASCII tokens. Explicitly
admit the seven weekday names and twelve month names, because SCOWL capitalizes
these everyday words. Intersect
with the pinned FrequencyWords English 2018 top-50k file. Retain the 25,000
highest corpus counts; equal counts use alphabetical order. No handwritten
word boosts, previous user text, or generated frequencies are included; calendar
names keep their unchanged corpus counts.

## Current runtime

The current base file is `third_party/open_dictionaries/skey_english.tsv`: 25,000 words from the pinned original frequency source. See [current source manifest](../open_dictionaries/README.md). The previous ESDB extension and its generator have been removed.

`CompiledDictionary` queries the read-only SQLite snapshot. `EnglishLexicon` remains a small reference parser used by engine tests. Custom English entries apply immediately; edits to built-in files require compilation. Completion extends the current prefix while preserving typed casing; it is not spelling correction or next-word prediction.
