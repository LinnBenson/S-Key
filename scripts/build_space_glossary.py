"""Build a conservative short-gloss index from the pinned CC-CEDICT snapshot."""
from pathlib import Path
import gzip, hashlib, json, re, sqlite3, shutil
ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'third_party/cc_cedict'
OUT = ROOT / 'app/build/generated/spaceGlossary/learning'

def split_senses(sense):
    depth = 0
    start = 0
    for i, char in enumerate(sense):
        if char == '(': depth += 1
        elif char == ')': depth = max(0, depth - 1)
        elif char == ';' and depth == 0:
            yield sense[start:i].strip()
            start = i + 1
    yield sense[start:].strip()

def entries(text):
    records = {}
    for line in text.splitlines():
        match = re.fullmatch(r'(\S+) (\S+) \[([^]]+)\] /(.+)/', line)
        if not match: continue
        traditional, simplified, _, meanings = match.groups()
        if not (2 <= len(simplified) <= 12 and all('\u3400' <= c <= '\u9fff' for c in simplified)): continue
        for word in (simplified, traditional):
            records.setdefault(word, []).extend((sense, any(c.isupper() for c in match[3])) for sense in meanings.split('/'))

    def resolve(word, visited):
        if word in visited or len(visited) >= 8: return None
        redirects = []
        # Prefer ordinary readings over proper-name readings marked by capitalized pinyin.
        # Preserve source sense order, including sentence-initial capital letters.
        for sense, _ in sorted(records.get(word, []), key=lambda item: item[1]):
            sense = sense.strip()
            redirect = re.match(r'(?:see|variant of|old variant of|same as) (.+)', sense, re.I)
            if redirect and not re.search(r'[\u3400-\u9fff]', redirect[1]): redirect = None
            if redirect:
                redirects.extend(re.findall(r'[\u3400-\u9fff]+', redirect[1]))
                continue
            if re.match(r'(?:CL:|surname\b|also (?:pr\.|pronounced|written)|abbr\.|used (?:in|as)|Taiwan pr\.)', sense, re.I): continue
            # Keep complete definitions and meaningful qualifiers. UI handles truncation.
            for gloss in split_senses(sense):
                gloss = gloss.strip()
                if re.search(r'[A-Za-z]', gloss) and not re.search(r'[\u3400-\u9fff]|\[[^]]*\]', gloss):
                    return gloss
        for target in redirects:
            result = resolve(target, visited | {word})
            if result: return result
        return None

    return {word: gloss for word in records if (gloss := resolve(word, set()))}

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
