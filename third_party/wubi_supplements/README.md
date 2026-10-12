# Pinned Wubi86 supplements, private testing only

This directory contains the five unchanged upstream plain-text dictionaries selected for
integration, pinned to xianghongai/rime-wubi-dict commit
`1e6afaaa8c7bd605b604d9dc1314d496a8220b92`. Source URLs, byte sizes, SHA-256 hashes,
merge order, row counts and the baseline source revision are in
`source-manifest.json`. The pinned upstream README and tree are in `provenance/`.

The upstream tree has no LICENSE. The README attributes idiom and word data to
汉典. No redistribution permission is inferred. This integration is for the
requested private testing only; resolve permissions before public/commercial
redistribution. The original Rime baseline is LGPL-3.0; that does not license
these upstream supplements. Version 0.1.8 includes eight explicitly requested
local entries in `wubi86_high_priority.dict.yaml`; their separate provenance is
`provenance/LOCAL-BUILTIN-NOTICE.txt`. Its manifest source has `exact_priority: true`: the builder promotes all rows
only within matching full-code groups. Static weights stay unchanged, while
ordinary ties and partial prefixes now use: baseline, high-priority source, IT,
administrative divisions, general words, historic/cultural cities, then idioms. Edit this file to maintain priority entries; there is no
separate hardcoded runtime mapping. The local source is not represented as an
upstream download or revision. The complete app-side notice is
`app/src/main/assets/dictionaries/COMPACT-WUBI-NOTICE.txt`.

No replacement single-character dictionary, JSON pinyin file, executable YAML,
schema, learning configuration or user data is imported. These raw supplements
stay outside Android assets, avoiding a second copy in the APK.

## Rebuild from the project root (Python 3.8+, standard library only)

```sh
python3 scripts/build_compact_wubi.py --source-root . --report third_party/wubi_supplements/BUILD_REPORT.json
python3 scripts/build_compact_wubi.py --source-root . --verify-only
python3 -m unittest discover -s tests -p 'test_compact_wubi_builder.py' -v
```

All default paths are inside the source package. No network or extra package is
needed. Explicit `--base`, `--supplements`, `--manifest`, `--output` and `--report`
paths support isolated builds. `--verify-only` rebuilds and validates in memory,
then compares every existing output byte without rewriting either asset or report.

For tests against alternative input locations, set `WUBI_BASE`,
`WUBI_SUPPLEMENTS`, and `WUBI_MANIFEST`. The full-source test skips with an explicit
reason if those inputs are unavailable. Release verification must have no skips.

`FORMAT.md` specifies the complete format. `BUILD_REPORT.json` contains exact
line/row/duplicate accounting and all-key validation counts. The 0.1.8 output is 8,624,375
bytes (about 8.23 MiB), with 441,796 retained rows; fixed bytes allow memory mapping instead of constructing full
Wubi string/object indexes. This file size is not an Android RSS measurement.
