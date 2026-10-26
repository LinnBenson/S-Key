#!/usr/bin/env python3
"""Reproduce/verify the licensed dictionary-specific CJK fallback font assets.

Requires fonttools==4.61.1. Normal builds use checked-in assets; no network is
needed. Upstream archive is only downloaded when --download is explicit.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import tempfile
import urllib.request
import zipfile
import zlib

import fontTools
from fontTools import subset
from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets"
OUTPUT = ASSETS / "fonts"
SOURCE_URL = "https://glyphwiki.org/hanazono/hanazono-20170904.zip"
SOURCE_SHA256 = "571cd4a09ae7da0c642d640fc2442c050aa450ebb0587a95cdd097d41a9c9572"
FONTTOOLS_VERSION = "4.61.1"
EXACT_REGRESSION_POINTS = {0x2575A, 0x25B07}
SOURCES = {
    "bmp": ("HanaMinA.ttf", "7ca87559414474848fc513a8d8eb42d5b0f8194c30c2abdfbfedff5398ec88d7", "OfflineKeyboardCjkBmp.ttf", "Offline Keyboard CJK BMP"),
    "supplement": ("HanaMinB.ttf", "829543436cdf7b4c6730e2af5806fa406f8ddf0d4b656e0cd49c58d4db1c8d3c", "OfflineKeyboardCjkSupplement.ttf", "Offline Keyboard CJK Supplement"),
}


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def dictionary_characters():
    points = set()
    hashes = {}
    for path in sorted((ASSETS / "dictionaries").glob("*.dict.yaml")):
        data = path.read_bytes()
        hashes[path.name] = sha256(data)
        in_entries = False
        for line in data.decode("utf-8").splitlines():
            if line == "...":
                in_entries = True
                continue
            if in_entries and "\t" in line and not line.startswith("#"):
                points.update(map(ord, line.split("\t", 1)[0]))
    if not EXACT_REGRESSION_POINTS <= points:
        raise ValueError("The original tdu candidates must remain in the dictionary")
    return points, hashes


def needs_bundled_coverage(cp):
    # Common U+4E00..U+9FA5 Hanzi and UI punctuation remain system-rendered.
    return (0x3400 <= cp <= 0x4DBF or 0x9FA6 <= cp <= 0x9FFF
            or 0xF900 <= cp <= 0xFAFF or 0x20000 <= cp <= 0x323AF)


def ranges(points):
    result = []
    for cp in sorted(points):
        if result and cp == result[-1][1] + 1:
            result[-1][1] = cp
        else:
            result.append([cp, cp])
    return result


def rename(font, family):
    # OFL reserved names are not retained as the modified font's primary names.
    postscript = family.replace(" ", "") + "-Regular"
    new = {1: family, 2: "Regular", 3: "OfflineKeyboard-1.000-" + postscript,
           4: family + " Regular", 5: "Version 1.000; dictionary subset of 20170904",
           6: postscript, 16: family, 17: "Regular", 18: family + " Regular",
           21: family, 22: "Regular", 25: postscript}
    for record in font["name"].names:
        if record.nameID in new:
            record.string = new[record.nameID].encode(record.getEncoding())
    for name_id, value in new.items():
        font["name"].setName(value, name_id, 3, 1, 0x409)
        font["name"].setName(value, name_id, 1, 0, 0)


def validate_font(path, expected):
    font = TTFont(path, recalcTimestamp=False)
    cmap = font.getBestCmap()
    if set(cmap) != expected:
        raise ValueError(f"Unexpected character coverage: {path.name}")
    for cp in expected:
        if cmap[cp] == ".notdef" or font["glyf"][cmap[cp]].numberOfContours == 0:
            raise ValueError(f"Empty glyph for U+{cp:04X} in {path.name}")
    if "HanaMin" in font["name"].getDebugName(1):
        raise ValueError("Modified font must have its own family name")
    font.close()


def verify():
    manifest = json.loads((OUTPUT / "manifest.json").read_text())
    points, hashes = dictionary_characters()
    if manifest["dictionary_sha256"] != hashes:
        raise ValueError("Dictionaries changed: regenerate and review font coverage")
    covered = set()
    for part in manifest["fonts"]:
        path = OUTPUT / part["file"]
        if sha256(path.read_bytes()) != part["sha256"]:
            raise ValueError(f"Font checksum mismatch: {path.name}")
        expected = {cp for lo, hi in part["ranges"] for cp in range(lo, hi + 1)}
        validate_font(path, expected)
        coverage_path = OUTPUT / part["coverage_file"]
        expected_ranges = "".join(f"{lo:X}-{hi:X}\n" for lo, hi in ranges(expected))
        if coverage_path.read_text() != expected_ranges:
            raise ValueError(f"Runtime coverage index mismatch: {coverage_path.name}")
        covered |= expected
    needed = {cp for cp in points if needs_bundled_coverage(cp)}
    if covered != needed or not EXACT_REGRESSION_POINTS <= covered:
        raise ValueError("Missing required dictionary extension glyphs")
    if sha256((OUTPUT / "HANAZONO-LICENSE.txt").read_bytes()) != manifest["license_sha256"]:
        raise ValueError("Upstream license checksum mismatch")
    print(f"PASS: {len(covered):,} dictionary rare CJK codepoints, including U+2575A and U+25B07; all mapped to nonempty glyphs")
    print(f"All {sum(cp > 0xFFFF for cp in points):,} supplementary dictionary codepoints are covered")
    print("Common Hanzi and punctuation use device fonts; imported characters outside the listed subset retain normal system fallback")


def build(source):
    if fontTools.__version__ != FONTTOOLS_VERSION:
        raise ValueError(f"Reproduction requires fonttools=={FONTTOOLS_VERSION}; found {fontTools.__version__}")
    source_data = source.read_bytes()
    if sha256(source_data) != SOURCE_SHA256:
        raise ValueError("Upstream archive checksum mismatch")
    points, hashes = dictionary_characters()
    needed = {cp for cp in points if needs_bundled_coverage(cp)}
    OUTPUT.mkdir(parents=True, exist_ok=True)
    manifest = {"source_url": SOURCE_URL, "source_sha256": SOURCE_SHA256,
                "source_version": "20170904", "fonttools_version": FONTTOOLS_VERSION,
                "dictionary_sha256": hashes, "dictionary_unique_codepoints": len(points),
                "fonts": []}
    with zipfile.ZipFile(io.BytesIO(source_data)) as archive:
        license_data = archive.read("LICENSE.txt")
        (OUTPUT / "HANAZONO-LICENSE.txt").write_bytes(license_data)
        manifest["license_sha256"] = sha256(license_data)
        for kind, (source_name, source_hash, filename, family) in SOURCES.items():
            data = archive.read(source_name)
            if sha256(data) != source_hash:
                raise ValueError(f"Upstream font checksum mismatch: {source_name}")
            selected = {cp for cp in needed if (cp <= 0xFFFF) == (kind == "bmp")}
            font = TTFont(io.BytesIO(data), recalcTimestamp=False)
            missing = selected - font.getBestCmap().keys()
            if missing:
                raise ValueError(f"Source lacks {len(missing)} needed characters")
            options = subset.Options()
            options.hinting = False
            options.layout_features = []
            options.name_IDs = ["*"]
            options.name_legacy = True
            options.name_languages = ["*"]
            options.notdef_glyph = True
            options.notdef_outline = True
            options.glyph_names = True
            options.recalc_timestamp = False
            options.drop_tables += ["FFTM"]
            subsetter = subset.Subsetter(options=options)
            subsetter.populate(unicodes=selected)
            subsetter.subset(font)
            rename(font, family)
            path = OUTPUT / filename
            font.save(path)
            font.close()
            validate_font(path, selected)
            output_data = path.read_bytes()
            coverage_file = f"{kind}.ranges"
            (OUTPUT / coverage_file).write_text("".join(f"{lo:X}-{hi:X}\n" for lo, hi in ranges(selected)))
            manifest["fonts"].append({"file": filename, "family": family, "source_file": source_name,
                "source_file_sha256": source_hash, "sha256": sha256(output_data),
                "bytes": len(output_data), "deflate_bytes_estimate": len(zlib.compress(output_data, 9)),
                "codepoints": len(selected), "coverage_file": coverage_file, "ranges": ranges(selected)})
            print(f"{filename}: {len(selected):,} characters, {len(output_data):,} bytes", flush=True)
    (OUTPUT / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    verify()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-zip", type=Path, default=Path(tempfile.gettempdir()) / "offline-keyboard-fonts/hanazono-20170904.zip")
    parser.add_argument("--download", action="store_true", help="Fetch the pinned official archive if missing")
    parser.add_argument("--verify-only", action="store_true", help="Verify checked-in fonts and dictionary coverage offline")
    args = parser.parse_args()
    if args.verify_only:
        verify()
        return
    if args.download and not args.source_zip.exists():
        args.source_zip.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(SOURCE_URL, timeout=90) as response:
            data = response.read()
        if sha256(data) != SOURCE_SHA256:
            raise ValueError("Downloaded upstream archive checksum mismatch")
        args.source_zip.write_bytes(data)
    build(args.source_zip)


if __name__ == "__main__":
    main()
