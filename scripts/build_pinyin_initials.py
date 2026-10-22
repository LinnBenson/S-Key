#!/usr/bin/env python3
"""Derive single-character initials from the bundled Apache-2.0 Rime/AOSP pinyin table."""
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/assets/dictionaries/pinyin_simp.dict.yaml'
DEST = SOURCE.with_name('pinyin_initials.dict.yaml')

def entries():
    result = {}
    for line in SOURCE.read_text().split('...\n', 1)[1].splitlines():
        if not line or line.startswith('#'): continue
        parts = line.split('\t')
        if len(parts) < 2: continue
        word, reading = parts[:2]
        if len(word) != 1 or not reading.isascii() or not reading.isalpha(): continue
        key = (reading[0], word)
        result[key] = max(result.get(key, 0), int(parts[2]) if len(parts)>2 else 0)
    return sorted(((word, initial, weight) for (initial, word), weight in result.items()), key=lambda r: (r[1], -r[2], r[0]))

def build():
    rows = entries()
    header = '''# Derived from pinyin_simp.dict.yaml (Rime / Android Open Source Project).
# Apache-2.0; original attribution and license retained with the source dictionary.
# All single characters, grouped by reading initial; duplicate readings use maximum weight.
# Pure pinyin only. No invented i/u/v mappings; edit then compile to apply.
---
name: pinyin_initials
version: "2"
sort: by_weight
...
'''
    DEST.write_text(header + ''.join(f'{word}\t{initial}\t{weight}\n' for word, initial, weight in rows))
    print(f'{len(rows)} entries, {len({r[0] for r in rows})} characters, {len({r[1] for r in rows})} initials')
if __name__ == '__main__': build()
