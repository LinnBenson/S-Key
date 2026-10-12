# Bundled CJK candidate glyph fallback

The keyboard preserves every candidate and its original Unicode text. Some Samsung
system fonts cannot display rare Wubi characters. Candidate labels now use a bundled
font only for a covered CJK character when the label's normal `Paint.hasGlyph` is false.
Common Hanzi, supported rare Hanzi, punctuation and emoji keep the device's fonts.
The receiving app still controls its own rendering after a candidate is committed;
an input method cannot install a fallback font into another app.

## Provenance and license

- Upstream: GlyphWiki Project, Hanazono font, release **20170904**.
- Official project: <https://glyphwiki.org/hanazono/>.
- Official release: <https://glyphwiki.org/hanazono/hanazono-20170904.zip>.
- Archive SHA-256: `571cd4a09ae7da0c642d640fc2442c050aa450ebb0587a95cdd097d41a9c9572`.
- That archive hash also matches the independently maintained [Homebrew font-hanamin cask](https://github.com/Homebrew/homebrew-cask/blob/master/Casks/font/font-h/font-hanamin.rb).
- `HanaMinA.ttf` original SHA-256: `7ca87559414474848fc513a8d8eb42d5b0f8194c30c2abdfbfedff5398ec88d7`.
- `HanaMinB.ttf` original SHA-256: `829543436cdf7b4c6730e2af5806fa406f8ddf0d4b656e0cd49c58d4db1c8d3c`.
- Copyright 2008, 2009, 2010, 2011, 2012, 2013, 2014, 2016, 2017 GlyphWiki Project.
- The upstream font is dual-licensed under the Hanazono Font License and SIL Open
  Font License 1.1. The complete, unchanged upstream license and copyright notices
  are included in `app/src/main/assets/fonts/HANAZONO-LICENSE.txt`, in both the APK
  and the source package. License-file SHA-256:
  `3983a1b74d18bc5e9fe733224838af1045df28a68d677432462a5a85da1b689e`.
- The modified subsets retain the upstream licensing. Primary font-family and
  PostScript names have been changed to **Offline Keyboard CJK BMP** and
  **Offline Keyboard CJK Supplement**; upstream reserved names are not used as
  the modified fonts' names. Upstream metadata and attribution are retained.

No fonts are downloaded at runtime. No new Android permission is needed. The full
53.5 MB upstream fonts/archives are not packaged. These font files contain only
static, public glyph data; no personal/user input is added to them.

## Coverage and deliberate limits

Coverage is computed from actual entry text after the YAML header in both bundled
Rime dictionaries, without normalizing, replacing or deleting any character.
There are 70,976 unique dictionary codepoints in total.

The two bundled subsets contain **49,484** rare CJK codepoints:

- BMP subset: **6,614**, including every dictionary Extension A character,
  dictionary U+9FA6..U+9FFF characters and CJK compatibility characters.
- Supplement subset: **42,870**, covering **every supplementary codepoint in the
  bundled dictionaries** (42,710 Extension B and 160 Extensions C–E).
- Exact Samsung regression characters: **𥝚 U+2575A** and **𥬇 U+25B07**, both
  mapped to nonempty TrueType outlines in the final supplementary subset.
- The 21,492 remaining unique dictionary characters (common U+4E00..U+9FA5
  Hanzi and other ordinary text) rely on the normal Android font stack.
- A user-imported character inside the exact shipped subset receives the same
  fallback. Imported characters outside it remain intact and use system fallback;
  arbitrary future Unicode, unsupported emoji and all possible imports are not
  promised complete coverage.
- Hanazono is a Ming/serif font. Missing characters can therefore have a different
  stroke style from neighboring system sans-serif text; this is a glyph fallback.

Every bundled cmap entry is checked for a nonempty outline, not just a claimed
font name/block. The supplementary font uses a format-12 cmap; the BMP font uses a format-4 cmap.
The exact per-font ranges, source/dictionary hashes and output hashes are recorded
in `app/src/main/assets/fonts/manifest.json`; compact `.ranges` files are the
runtime coverage indexes.

The official Noto Sans CJK SC 2.004 font was also downloaded and inspected with
fontTools. Its cmap lacks both U+2575A and U+25B07 and covers only 2,267 of these
42,870 supplementary dictionary characters. It was not bundled. BabelStone Han's
[official coverage list](https://www.babelstone.co.uk/Fonts/Han.html) documents only
partial Extension B coverage, so it was not assumed to solve the whole dictionary.

## Size and reproducibility

Checked-in generated files:

| Asset | Uncompressed bytes | Deflate estimate (level 9) | SHA-256 |
|---|---:|---:|---|
| OfflineKeyboardCjkBmp.ttf | 3,271,452 | 1,914,865 | `5bf4c1664e70b4a81880bbc7d85664eed287a6f4feab243612d6d0f625d46c45` |
| OfflineKeyboardCjkSupplement.ttf | 22,187,516 | 12,644,311 | `aae5668d88fb85be170cb07171e92c3bfe52d328390c167ffcf6c48fe054cffb` |

APK compression and final APK/source ZIP size must be measured after building;
these estimates are not a claim about AAPT's final packaging.

Normal Android builds use the checked-in subsets and require no font tooling or
network. To reproduce the assets:

1. Install the pinned font tool from the official Python registry:
   `python3 -m pip install fonttools==4.61.1`.
2. Run `python3 scripts/build_candidate_fonts.py --download`. It downloads only
   the official pinned archive, verifies its SHA-256, and caches it outside the
   repository. Alternatively provide an existing official archive with
   `--source-zip /path/to/hanazono-20170904.zip`.
3. Run `python3 scripts/build_candidate_fonts.py --verify-only` for an offline
   checksum, dictionary coverage, runtime range-index and nonempty-outline audit.

The script removes unneeded glyphs, hinting, layout substitutions and unused tables,
renames the subset font families, and preserves stable timestamps. It does not
change dictionary assets. Two successive builds using fontTools 4.61.1 produced
identical SHA-256 hashes. A dictionary change deliberately fails verification until
coverage is regenerated and reviewed.

## Android integration and tests

`CandidateFont.apply(candidateTextView)` should be called after assigning candidate
text and its normal font. `CandidateFont.styled(context, text, normalPaint)` is also
available for direct styled-text use. Fonts are loaded lazily and cached. A corrupt
asset cannot crash the input method or cause candidates to be discarded.

`CandidateFontTest` tests the packaged font loading, final per-codepoint candidate
`TextPaint.hasGlyph` for both screenshot characters, unchanged candidate text,
full-surrogate-pair span boundaries, repeated application, and preservation of an
out-of-subset imported Extension G character. Run it on supported Android versions,
including API 26 and the target Samsung device when available. Rendering validation
on an emulator is not a claim about the receiving app or every Samsung firmware.
