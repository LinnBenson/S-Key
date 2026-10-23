# Default dictionary sources

These pinned GitHub sources provide the three default base dictionaries. See `manifest.json` for source URLs, SHA-256 hashes, counts, licenses and conversion changes. `upstream/` contains the originals. All three current `skey_*.tsv` base filenames remain unchanged in the application.

Run `python3 scripts/convert_open_dictionaries.py` then `python3 scripts/build_dictionary_catalog.py`. Conversion preserves rows and ordering, removes YAML metadata and unused stem columns, and floors the single fractional Wubi weight to an integer (450.5 → 450). Runtime imports still reject fractional weights.

Wubi: LGPL-3.0. Pinyin: Apache-2.0. English: CC-BY-SA-4.0 with the accompanying SCOWL attribution notices. Full licenses and credits are under `app/src/main/assets/dictionaries/`. Preserve these with redistributions, and provide corresponding source and changes as required by each license. Renaming does not remove license obligations.
