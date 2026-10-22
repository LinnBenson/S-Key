#!/usr/bin/env python3
"""Rebuild the ESDB extension offline from vendored, hash-verified upstream files."""
import hashlib
import json
import re
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent

def build():
    manifest = json.loads((HERE / 'esdb/manifest.json').read_text())
    words = set()
    for name, expected in manifest['sha256'].items():
        data = (HERE / 'esdb' / name).read_bytes()
        assert hashlib.sha256(data).hexdigest() == expected, name
        if name.endswith('.txt'):
            words.update(w for w in data.decode('utf-8').splitlines() if re.fullmatch('[a-z]{2,40}', w))
    core = {line.split('\t')[0] for line in (ROOT / 'app/src/main/assets/dictionaries/english_frequency.tsv').read_text().splitlines() if line and not line.startswith('#')}
    extension = sorted(words - core)
    output = ROOT / 'app/src/main/assets/dictionaries/english_extended.tsv'
    output.write_text('# ESDB English extension: US + GB large lists\n# Source and license: licenses/ESDB-Copyright.txt\n# Common-word frequencies remain in english_frequency.tsv\n' + ''.join(f'{word}\t1\n' for word in extension), encoding='utf-8')
    print(f'core={len(core)}, extension={len(extension)}, total={len(core)+len(extension)}')
if __name__ == '__main__':
    build()
