# Static English completion data

The application packages 25,000 useful English word forms in
`app/src/main/assets/dictionaries/english_frequency.tsv`.
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

## Runtime and integration

`EnglishLexicon(reader)` parses the packaged TSV once and closes the reader.
`entryCount` reports validated unique words.
`complete(prefix, limit = 8)` returns frequency-ranked strict extensions of
the one supplied current word. It accepts a first letter, preserves the typed
prefix, treats a single uppercase initial as title case, and produces uppercase
completions when a longer prefix is all uppercase. Empty, multiword, numeric,
punctuated, and non-ASCII inputs produce no results. No surrounding editor text
is accepted. Exact words are not repeated as their own completion.

The lexically sorted in-memory index uses binary searches to isolate a prefix
range and a bounded priority queue for its best results. Queries are read-only
and thread-safe, and do not allocate/sort the whole dictionary each keystroke.

Current limitations: this is prefix completion, not typo correction or
next-word prediction. The fixed 2018 frequencies emphasize conversational
English. Inflections are included, but rare words, apostrophes, accents,
hyphens, and names are deliberately limited. Corpus words are not censored.

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
