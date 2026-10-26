"""Build the concise space-key English gloss index (S Key).

Sources (all pinned in the repository, no network access):
  * third_party/cc_cedict/cedict.txt.gz  CC-CEDICT, CC BY-SA 4.0 (verified by SHA-256)
  * third_party/space_glossary/overrides.tsv  S Key curated glosses, CC BY-SA 4.0
  * third_party/open_dictionaries/skey_english.tsv  English frequencies (CC BY-SA 4.0 / SCOWL),
    used only to rank candidate senses; no English data is copied into the output.

Output schema is unchanged: gloss(word TEXT PRIMARY KEY, english TEXT NOT NULL) WITHOUT ROWID,
gzip-compressed as learning/glossary.bin with learning/sha256.txt of the uncompressed database.

Usage: python3 scripts/build_space_glossary.py [--tsv FILE]
"""
from pathlib import Path
import argparse, gzip, hashlib, json, math, re, sqlite3, shutil

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'third_party/cc_cedict'
CURATED = ROOT / 'third_party/space_glossary'
ENGLISH = ROOT / 'third_party/open_dictionaries/skey_english.tsv'
OUT = ROOT / 'app/build/generated/spaceGlossary/learning'

MIN_LEN, MAX_LEN = 2, 4          # SpaceGlossary.queryBeforeCursor only looks up 2-4 characters.
MAX_WORDS, MAX_CHARS = 4, 24     # Prefer short senses, but preserve complete longer definitions.
HAN = re.compile(r'[\u3400-\u9fff]')
META = re.compile(r'^(?:CL:|surname\b|(?:old |archaic |ancient |erhua |Japanese |Korean )?variant of\b|'
                  r'also (?:pr\.|pronounced|written|called)\b|abbr\.|abbreviation\b|used (?:in|as|for|before|after)\b|'
                  r'Taiwan pr\.|see (?:also )?\S|same as\b|\(?verb suffix|\(?suffix\b|\(?prefix\b|\(?particle\b|'
                  r'\(?grammatical|\(?onom\.?\)?$|\(?interj\.?\)?$|\(?euphemism\)?$|radical\b|Kangxi radical)', re.I)
REDIRECT = re.compile(r'^(?:see(?: also)?|(?:old |archaic |erhua |Japanese )?variant of|same as|abbr\. for|'
                      r'abbr\. of|abbreviation for|also written)\s+(.+)', re.I)
# Register or domain tags that make a sense a poor default for everyday typing.
TAG_PENALTY = [
    (re.compile(r'\b(?:archaic|old|obsolete|ancient|classical|literary|lit\. ?only|historical|in former times)\b', re.I), 3.0),
    (re.compile(r'\b(?:Buddhism|Buddhist|Taoism|Daoism|TCM|Chinese medicine|zoology|botany|chemistry|anatomy|'
                r'geology|mathematics|math\.|physics|biology|pharm|medicine|military|law|legal|astronomy|'
                r'linguistics|music|finance|computing|electricity|mechanics|sports|mahjong|chess|Go game)\b', re.I), 1.2),
    (re.compile(r'\b(?:dialect|Cantonese|Hokkien|Shanghainese|Tw|Taiwan|HK|Hong Kong|Mainland|Internet slang|'
                r'slang|vulgar|derog\.|derogatory|coll\.|colloquial|humble|polite|honorific|pejorative|'
                r'loanword|transliteration|onom\.)\b', re.I), 1.0),
    (re.compile(r'\bidiom\b', re.I), 0.3),
]
STOP = {'a','an','the','of','to','and','or','in','on','at','by','for','with','as','be','is','one','sb','sth',
        "one's","sb's","sth's",'oneself','etc','not','no','up','out','off','from','into'}
GENERIC = {'be','have','get','do','make','in','at','on','go','come','give','take','put','set','there'}

def split_outside(text, sep):
    depth, start = 0, 0
    for i, ch in enumerate(text):
        if ch == '(': depth += 1
        elif ch == ')': depth = max(0, depth - 1)
        elif ch == sep and depth == 0:
            yield text[start:i]; start = i + 1
    yield text[start:]

def english_ranks():
    ranks = {}
    for line in ENGLISH.read_text(encoding='utf-8').splitlines():
        if not line or line.startswith('#'): continue
        word = line.split('\t', 1)[0].lower()
        ranks.setdefault(word, len(ranks) + 1)
    return ranks


def clean(text, proper):
    """Remove display annotations without cutting grammatical phrases or their objects."""
    flags = set()
    tags = ' '.join(re.findall(r'\(([^()]*)\)', text))
    text = re.sub(r'\([^()]*\)', ' ', text)
    text = re.sub(r'\([^()]*\)', ' ', text)            # nested once
    text = re.sub(r'\[[^\]]*\]', ' ', text)
    m = re.match(r'\s*(lit\.|fig\.|figuratively|literally)\s*', text, re.I)
    if m:
        flags.add('lit' if m[1].lower().startswith('lit') else 'fig'); text = text[m.end():]
    if proper and ',' in text:
        # A proper name before a comma is a complete label, unlike a cut clause.
        text = text.split(',', 1)[0]
    # Keep clauses and objects: shortening at "of/with/before" changes the meaning.
    text = re.sub(r"\bsb's\b", "someone's", text)
    text = re.sub(r"\bsth's\b", "something's", text)
    text = re.sub(r"\bsb\b", "someone", text)
    text = re.sub(r"\bsth\b", "something", text)
    if re.search(r'\.\.\.|…|-ing\b', text): return None
    text = re.sub(r'^\s*(?:to|a|an|the)\s+(?=\S)', '', text.strip(), flags=re.I)
    text = re.sub(r'^(?:be|being)\s+(?=\S)', '', text, flags=re.I)
    text = re.sub(r'\s+', ' ', text).strip(' .;:!?¡¿-–—"\'')
    if not text or HAN.search(text) or re.search(r'[|]', text) or not re.search(r'[A-Za-z]{2}', text):
        return None
    if text.split()[-1].lower() in {'and', 'or'}:
        return None
    # A complete long sense is preferable to a missing word or a fabricated fragment.
    if len(text.split()) > MAX_WORDS or len(text) > MAX_CHARS: flags.add('long')
    return text, flags, tags

def parse(raw):
    records = {}
    for line in raw.splitlines():
        m = re.fullmatch(r'(\S+) (\S+) \[([^]]+)\] /(.+)/', line)
        if not m: continue
        trad, simp, pinyin, senses = m.groups()
        if not all(HAN.match(c) for c in simp): continue
        proper = pinyin[:1].isupper() or any(s[:1].isupper() for s in pinyin.split())
        for word in dict.fromkeys((simp, trad)):
            records.setdefault(word, []).append((proper, senses.split('/')))
    return records

def build(records, ranks):
    def rarity(gloss):
        words = [w for w in re.findall(r"[a-z][a-z'-]*", gloss.lower()) if w not in STOP]
        if not words: return 0.5
        return max(math.log10(ranks.get(w, ranks.get(w.rstrip('s'), 80000))) for w in words)

    def source_fallback(sense):
        # Preserve complete legacy definitions when the concise picker cannot safely use them.
        if re.match(r'(?:CL:|surname\b|also (?:pr\.|pronounced|written)|abbr\.|used (?:in|as)|Taiwan pr\.)', sense, re.I):
            return None
        if not re.search(r'[A-Za-z]', sense) or HAN.search(sense) or re.search(r'\[[^]]*\]', sense):
            return None
        return sense.strip()

    def candidates(word):
        readings = records.get(word, [])
        all_proper = all(p for p, _ in readings)
        out, redirects, fallback = [], [], []
        for r_index, (proper, senses) in enumerate(readings):
            for s_index, sense in enumerate(senses):
                sense = sense.strip()
                red = REDIRECT.match(sense)
                targets = re.findall(r'[\u3400-\u9fff]+', red[1]) if red else []
                if targets:
                    redirects += [t for t in targets if t != word]
                    continue
                for part in split_outside(sense, ';'):
                    original = source_fallback(part.strip())
                    if original:
                        fallback.append((proper, r_index, s_index, original))
                if META.match(sense) and not (red and not targets): continue
                for sub_index, part in enumerate(split_outside(sense, ';')):
                    got = clean(part, proper)
                    if not got: continue
                    gloss, flags, tags = got
                    score = 1.5 * s_index + 0.3 * sub_index + 0.6 * r_index
                    score += 0.7 * rarity(gloss)
                    if gloss.lower() in GENERIC: score += 1.5
                    n = len(gloss.split())
                    score += min(3.0, 0.3 * max(0, n - 2) + 0.02 * max(0, len(gloss) - 14))
                    if 'lit' in flags: score += 2.5
                    if 'long' in flags: score += 6.0
                    for pattern, penalty in TAG_PENALTY:
                        if pattern.search(tags) or pattern.search(part): score += penalty
                    if proper and not all_proper: score += 3.0
                    out.append((score, gloss))
        return out, redirects, fallback

    cache = {}
    def resolve(word, visited=frozenset()):
        if word in cache: return cache[word]
        if word in visited or len(visited) >= 8: return None
        found, redirects, fallback = candidates(word)
        best = min(found, key=lambda item: item[0])[1] if found else None
        if best is None:
            for target in redirects:
                best = resolve(target, visited | {word})
                if best: break
        if best is None and fallback:
            best = min(fallback)[-1]
        # A cycle/depth failure depends on this traversal, not on the word itself.
        # Never cache that failure and hide a valid route from a later lookup.
        if best is not None: cache[word] = best
        return best

    return {w: g for w in records if MIN_LEN <= len(w) <= MAX_LEN and (g := resolve(w))}

def overrides():
    """Curated glosses override or add entries; '-' removes an entry. Traditional forms follow CC-CEDICT."""
    to_trad = {}
    for line in gzip.decompress((SOURCE/'cedict.txt.gz').read_bytes()).decode('utf-8').splitlines():
        m = re.match(r'(\S+) (\S+) \[', line)
        if m and m[1] != m[2]: to_trad.setdefault(m[2], set()).add(m[1])
    result = {}
    seen = set()
    path = CURATED/'overrides.tsv'
    for n, line in enumerate(path.read_text(encoding='utf-8').splitlines(), 1):
        if not line.strip() or line.startswith('#'): continue
        fields = line.split('\t')
        if len(fields) != 2: raise ValueError(f'overrides.tsv:{n}: expected two columns')
        word, english = fields
        word, english = word.strip(), english.strip()
        if word in seen: raise ValueError(f'overrides.tsv:{n}: duplicate word {word}')
        seen.add(word)
        assert MIN_LEN <= len(word) <= MAX_LEN and all(HAN.match(c) for c in word), f'overrides.tsv:{n}'
        assert english == '-' or (bool(re.search(r'[A-Za-z]', english)) and len(english.split()) <= MAX_WORDS and len(english) <= MAX_CHARS), f'overrides.tsv:{n}'
        for form in {word} | to_trad.get(word, set()):
            result[form] = english
    return result

def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--tsv'); args = parser.parse_args()
    raw = (SOURCE/'cedict.txt.gz').read_bytes()
    manifest = json.loads((SOURCE/'manifest.json').read_text())
    assert hashlib.sha256(raw).hexdigest() == manifest['sha256']
    records = parse(gzip.decompress(raw).decode('utf-8'))
    data = build(records, english_ranks())
    for word, english in overrides().items():
        if english == '-': data.pop(word, None)
        else: data[word] = english
    if OUT.exists(): shutil.rmtree(OUT)
    OUT.mkdir(parents=True)
    dbfile = OUT/'glossary.db'
    with sqlite3.connect(dbfile) as db:
        db.execute('CREATE TABLE gloss(word TEXT PRIMARY KEY, english TEXT NOT NULL) WITHOUT ROWID')
        db.executemany('INSERT INTO gloss VALUES (?,?)', sorted(data.items()))
        db.commit()
        db.execute('VACUUM')
    dbraw = dbfile.read_bytes()
    (OUT/'glossary.bin').write_bytes(gzip.compress(dbraw, compresslevel=9, mtime=0))
    (OUT/'sha256.txt').write_text(hashlib.sha256(dbraw).hexdigest())
    dbfile.unlink()
    (OUT/'LICENSE.txt').write_bytes((SOURCE/'LICENSE.txt').read_bytes())
    (OUT/'NOTICE.txt').write_bytes((CURATED/'NOTICE.txt').read_bytes())
    if args.tsv:
        Path(args.tsv).write_text(''.join(f'{w}\t{e}\n' for w, e in sorted(data.items())), encoding='utf-8')
    print(json.dumps({'entries': len(data), 'database_bytes': len(dbraw),
                      'compressed_bytes': (OUT/'glossary.bin').stat().st_size}))

if __name__ == '__main__': main()
