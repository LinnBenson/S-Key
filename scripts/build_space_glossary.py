"""Build a conservative short-gloss index from the pinned CC-CEDICT snapshot."""
from pathlib import Path
import gzip, hashlib, json, re, sqlite3, shutil
ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'third_party/cc_cedict'
OUT = ROOT / 'app/build/generated/spaceGlossary/learning'

def entries(text):
    choices = {}
    for line in text.splitlines():
        if not line or line.startswith('#'): continue
        match = re.fullmatch(r'(\S+) (\S+) \[([^]]+)\] /(.+)/', line)
        if not match: continue
        traditional, simplified, _, meanings = match.groups()
        if not (2 <= len(simplified) <= 12 and all('\u3400' <= c <= '\u9fff' for c in simplified)): continue
        # Preserve source sense order; never turn a descriptive sentence into a single word.
        for sense in meanings.split('/'):
            for gloss in sense.split(';'):
                gloss = gloss.strip()
                if re.fullmatch(r'[a-z]+(?:[\x27-][a-z]+)*(?: [a-z]+(?:[\x27-][a-z]+)*){0,3}', gloss) and len(gloss) <= 48 and not gloss.startswith(('see ', 'variant of ', 'surname ', 'also ', 'abbr ', 'used ', 'a kind of ')):
                    for word in (simplified, traditional): choices.setdefault(word, gloss)
                    break
            else: continue
            break
    return choices

def main():
    raw = (SOURCE/'cedict.txt.gz').read_bytes()
    manifest = json.loads((SOURCE/'manifest.json').read_text())
    assert hashlib.sha256(raw).hexdigest() == manifest['sha256']
    data = entries(gzip.decompress(raw).decode('utf-8'))
    # Only generated outputs live here; discard obsolete resource names before rebuilding.
    if OUT.exists(): shutil.rmtree(OUT)
    OUT.mkdir(parents=True)
    dbfile = OUT/'glossary.db'
    dbfile.unlink(missing_ok=True)
    with sqlite3.connect(dbfile) as db:
        db.execute('CREATE TABLE gloss(word TEXT PRIMARY KEY, english TEXT NOT NULL) WITHOUT ROWID')
        db.executemany('INSERT INTO gloss VALUES (?,?)', sorted(data.items()))
        db.commit()
        db.execute('VACUUM')
    dbraw = dbfile.read_bytes()
    (OUT/'glossary.bin').write_bytes(gzip.compress(dbraw, mtime=0))
    (OUT/'sha256.txt').write_text(hashlib.sha256(dbraw).hexdigest())
    dbfile.unlink()
    for name in ['LICENSE.txt','NOTICE.txt']:
        (OUT/name).write_bytes((SOURCE/name).read_bytes())
    print(json.dumps({'entries':len(data),'database_bytes':len(dbraw),'compressed_bytes':(OUT/'glossary.bin').stat().st_size}))
if __name__ == '__main__': main()
