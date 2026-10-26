#!/usr/bin/env python3
"""Reject release APKs with unexpected dictionary assets or stale dictionary data."""
from pathlib import Path
import hashlib
import json
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
HIGH = {'skey_wubi86_high.tsv', 'skey_pinyin_high.tsv', 'skey_english_high.tsv'}
NOTICES = {'NOTICE.txt', 'ENGLISH-NOTICE.txt', 'ENGLISH-FREQUENCYWORDS-README.txt',
           'ENGLISH-SCOWL-COPYRIGHT.txt', 'LICENSE-ENGLISH-CC-BY-SA-4.0.txt',
           'LICENSE-GPL-3.0.txt', 'LICENSE-PINYIN-APACHE-2.0.txt', 'LICENSE-WUBI-LGPL-3.0.txt'}

def verify_archive(apk):
    manifest = json.loads((ROOT/'third_party/open_dictionaries/manifest.json').read_text())
    sources = {'assets/dictionary-sources/'+n for n in set(manifest) | HIGH}
    resources = {'assets/dictionaries/'+n for n in HIGH | NOTICES | {'emoji_keywords.tsv','catalog.db','runtime.db'}}
    names = apk.namelist()
    assert len(names)==len(set(names)), 'Duplicate APK entries'
    actual = {n for n in names if n.startswith(('assets/dictionaries/','assets/dictionary-sources/'))}
    assert actual == sources | resources, f'Unexpected/missing dictionary assets: {sorted(actual ^ (sources | resources))}'
    tables = {n for n in names if n.startswith('assets/') and n.endswith(('.tsv','.yaml','.yml','.db','.skwb'))}
    assert tables <= sources | resources, f'Unexpected dictionary data elsewhere: {sorted(tables - sources - resources)}'
    for name, item in manifest.items():
        data=apk.read('assets/dictionary-sources/'+name)
        assert hashlib.sha256(data).hexdigest()==item['sha256'], f'Wrong base dictionary: {name}'
    for name in HIGH:
        expected=(ROOT/'app/src/main/assets/dictionaries'/name).read_bytes()
        assert all(not line.strip() or line.startswith(b'#') for line in expected.splitlines()), f'Nonempty factory priority: {name}'
        for prefix in ['assets/dictionaries/','assets/dictionary-sources/']:
            assert apk.read(prefix+name)==expected, f'Wrong priority source: {prefix+name}'
    for name in ['catalog.db','runtime.db']:
        expected=ROOT/'app/build/generated/dictionaryCatalog/dictionaries'/name
        assert hashlib.sha256(apk.read('assets/dictionaries/'+name)).digest()==hashlib.sha256(expected.read_bytes()).digest(), f'Stale compiled dictionary: {name}'
    for name in NOTICES | {'emoji_keywords.tsv'}:
        assert apk.read('assets/dictionaries/'+name)==(ROOT/'app/src/main/assets/dictionaries'/name).read_bytes(), f'Stale notice/emoji resource: {name}'
    learning = ROOT/'app/build/generated/spaceGlossary/learning'
    learning_names = {'glossary.bin', 'sha256.txt', 'LICENSE.txt', 'NOTICE.txt'}
    assert {p.name for p in learning.iterdir()} == learning_names, 'Unexpected generated learning resources'
    expected_learning = {'assets/learning/'+name for name in learning_names}
    assert {n for n in names if n.startswith('assets/learning/')} == expected_learning, 'Unexpected/missing learning resources'
    for p in learning.iterdir():
        assert apk.read('assets/learning/'+p.name) == p.read_bytes(), f'Stale learning resource: {p.name}'
    return {'base_entries':{n:v['entries'] for n,v in manifest.items()},'sources':sorted(sources),'compiled':['catalog.db','runtime.db']}

def verify_apk(path):
    with zipfile.ZipFile(path) as apk:
        result=verify_archive(apk)
    print(json.dumps({'apk':str(path), 'verified':result},ensure_ascii=False))
    return result

if __name__=='__main__':
    for path in sys.argv[1:]: verify_apk(Path(path))
