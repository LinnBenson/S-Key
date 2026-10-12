#!/usr/bin/env python3
"""Rebuild the static English completion asset. Development-only; no Android runtime dependency.

Downloads are read-only from pinned official upstream sources and hash-checked before parsing.
Pass --cache pointing to a previously populated cache to reproduce the asset without networking.
The shipped derived word-frequency data is licensed CC BY-SA 4.0; see ENGLISH-NOTICE.txt.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import tarfile
import urllib.request


ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/main/assets/dictionaries"
COUNT = 25_000
CALENDAR_WORDS = {
    "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
    "january", "february", "march", "april", "may", "june", "july", "august",
    "september", "october", "november", "december",
}
SOURCES = {
    "en_50k.txt": {
        "url": "https://raw.githubusercontent.com/hermitdave/FrequencyWords/525f9b560de45753a5ea01069454e72e9aa541c6/content/2018/en/en_50k.txt",
        "sha256": "5351ff405b1126ef555791dd4d9798a48e3e9a501a9fc481a9da957752cfb458",
    },
    "scowl-2020.12.07.tar.gz": {
        "url": "https://downloads.sourceforge.net/wordlist/scowl-2020.12.07.tar.gz",
        "sha256": "5587667caa20c4891390c2d42dbb4d5c4c3f41bee77af1457ece3ba23fb859cc",
    },
}
HEADER = """# Offline Keyboard English current-word completion, 25,000 entries.
# FrequencyWords by Hermit Dave and contributors; OpenSubtitles 2018 via OPUS.
# https://github.com/hermitdave/FrequencyWords/tree/525f9b560de45753a5ea01069454e72e9aa541c6
# Filtered using SCOWL 2020.12.07 by Kevin Atkinson and contributors.
# Weekday/month names are eligible too, with their original corpus counts.
# https://wordlist.aspell.net/ ; full attribution and changes: ENGLISH-NOTICE.txt
# Adapted word-frequency data: CC BY-SA 4.0, https://creativecommons.org/licenses/by-sa/4.0/
# Retain this attribution, the accompanying notices, and the license when redistributing.
# Format: lowercase ASCII word<TAB>corpus occurrence count; higher ranks first.
"""


def digest(data):
    return hashlib.sha256(data).hexdigest()


def fetch(cache, name):
    source = SOURCES[name]
    path = cache / name
    if path.exists():
        data = path.read_bytes()
    else:
        with urllib.request.urlopen(source["url"], timeout=90) as response:
            data = response.read()
        if digest(data) != source["sha256"]:
            raise ValueError(f"Source hash mismatch: {name}")
        path.write_bytes(data)
    if digest(data) != source["sha256"]:
        raise ValueError(f"Cached source hash mismatch: {name}")
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, required=True)
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)
    frequency_path = fetch(args.cache, "en_50k.txt")
    scowl_path = fetch(args.cache, "scowl-2020.12.07.tar.gz")
    plain_word = re.compile(r"[a-z]{2,40}")
    selected_member = re.compile(
        r"scowl-2020\.12\.07/final/(?:english|american|british|british_z)-words\."
        r"(?:10|20|35|40|50|55|60)"
    )
    with tarfile.open(scowl_path) as archive:
        # Read regular members only. Do not extract files or execute upstream scripts.
        members = sorted(
            member.name for member in archive.getmembers()
            if member.isfile() and selected_member.fullmatch(member.name)
        )
        if len(members) != 28:
            raise ValueError(f"Unexpected SCOWL file count: {len(members)}")
        allowed = {
            word for name in members
            for word in archive.extractfile(name).read().decode("iso-8859-1").splitlines()
            if plain_word.fullmatch(word)
        }
    scowl_allowed_count = len(allowed)
    # Calendar names are ordinary keyboard vocabulary, but capitalized in SCOWL.
    # Admit only these explicit exceptions, without importing personal-name lists.
    allowed.update(CALENDAR_WORDS)
    counts = {}
    for line in frequency_path.read_text(encoding="utf-8").splitlines():
        word, count_text = line.rsplit(" ", 1)
        count = int(count_text)
        if word in allowed and count > 0:
            counts[word] = max(count, counts.get(word, 0))
    ranked = sorted(counts.items(), key=lambda item: (-item[1], item[0]))[:COUNT]
    if len(ranked) != COUNT:
        raise ValueError(f"Too few eligible words: {len(ranked)}")
    body = HEADER + "".join(f"{word}\t{count}\n" for word, count in ranked)
    output = ASSETS / "english_frequency.tsv"
    output.write_bytes(body.encode("utf-8"))
    manifest = {
        "format": "lowercase ASCII word, tab, positive integer corpus occurrence count",
        "entry_count": COUNT,
        "ranking": "descending occurrence count, then ascending word",
        "runtime": "packaged static asset; no network, learning, context, or next-word prediction",
        "license": "CC-BY-SA-4.0; retain accompanying SCOWL notices",
        "upstream_sources": SOURCES,
        "scowl_members": members,
        "scowl_allowed_count": scowl_allowed_count,
        "calendar_eligibility_exceptions": sorted(CALENDAR_WORDS),
        "allowed_count_after_calendar_exceptions": len(allowed),
        "frequency_intersection_count": len(counts),
        "asset_bytes": output.stat().st_size,
        "asset_sha256": digest(output.read_bytes()),
    }
    (ROOT / "third_party/english/manifest.json").write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    print(f"Built {len(ranked):,} entries: {output.stat().st_size:,} bytes, SHA-256 {manifest['asset_sha256']}")


if __name__ == "__main__":
    main()
