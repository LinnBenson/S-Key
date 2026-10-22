#!/usr/bin/env python3
"""Build the editable, attributed default catalog. Offline; stdlib only."""
import json, sqlite3, shutil
from pathlib import Path
from build_compact_wubi import parse_dictionary, load_sources
ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / 'app/build/generated/dictionaryCatalog/dictionaries/catalog.db'

def build():
    DEST.parent.mkdir(parents=True,exist_ok=True)
    DEST.unlink(missing_ok=True)
    db = sqlite3.connect(DEST)
    db.executescript('''PRAGMA user_version=1;
    CREATE TABLE libraries(id INTEGER PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL, priority INTEGER NOT NULL DEFAULT 0, notice TEXT NOT NULL DEFAULT '');
    CREATE TABLE entries(id INTEGER PRIMARY KEY, library INTEGER NOT NULL, text TEXT NOT NULL, code TEXT NOT NULL, weight INTEGER NOT NULL);
    CREATE INDEX entry_library ON entries(library,id);
    CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL);
    INSERT INTO metadata VALUES('dirty','0');
    INSERT INTO metadata VALUES('catalog_version','4');
    ''')
    manifest=json.loads((ROOT/'third_party/wubi_supplements/source-manifest.json').read_text())
    load_sources(ROOT/'app/src/main/assets/dictionaries/wubi86.dict.yaml',ROOT/'third_party/wubi_supplements',manifest)
    names=['五笔 86 基础库','本地高优先级词库','IT 词库','行政区划词库','综合词语库','历史文化名城词库','成语词库']
    for i,spec in enumerate(manifest['sources'],1):
        path=(ROOT/'app/src/main/assets/dictionaries' if i==1 else ROOT/'third_party/wubi_supplements')/spec['filename']
        rows,_=parse_dictionary(path.read_bytes(),spec['filename'],supplement=i!=1,weight_zero_fallbacks=spec.get('weight_zero_fallbacks',[]))
        db.execute('INSERT INTO libraries VALUES(?,?,?,?,?)',(i,names[i-1],'wubi',int(spec.get('exact_priority',False)),spec['license_notice']))
        db.executemany('INSERT INTO entries(library,text,code,weight) VALUES(?,?,?,?)',((i,r.text,r.code,r.weight) for r in rows))
    db.execute('INSERT INTO libraries VALUES(8,?,?,0,?)',('简体拼音词库','pinyin','Rime / AOSP; Apache-2.0'))
    started=False
    for line in (ROOT/'app/src/main/assets/dictionaries/pinyin_simp.dict.yaml').read_text().splitlines():
        if line.strip()=='...': started=True; continue
        if not started or not line or line.startswith('#'): continue
        parts=line.split('\t')
        if len(parts)<2: continue
        try: weight=int(parts[2])
        except (ValueError,IndexError): weight=0
        db.execute('INSERT INTO entries(library,text,code,weight) VALUES(8,?,?,?)',(parts[0],parts[1],weight))
    db.execute('INSERT INTO libraries VALUES(9,?,?,0,?)',('英文补全词库','english','FrequencyWords / SCOWL; CC BY-SA 4.0 and SCOWL notices'))
    for line in (ROOT/'app/src/main/assets/dictionaries/english_frequency.tsv').read_text().splitlines():
        if not line or line.startswith('#'): continue
        word,weight=line.split('\t')
        db.execute('INSERT INTO entries(library,text,code,weight) VALUES(9,?,?,?)',(word,word,int(weight)))
    db.execute('INSERT INTO libraries VALUES(10,?,?,0,?)',('英文扩展词库 · ESDB','english','ESDB / SCOWL; see licenses/ESDB-Copyright.txt'))
    for line in (ROOT/'app/src/main/assets/dictionaries/english_extended.tsv').read_text().splitlines():
        if not line or line.startswith('#'): continue
        word,weight=line.split('\t')
        db.execute('INSERT INTO entries(library,text,code,weight) VALUES(10,?,?,?)',(word,word,int(weight)))
    db.execute("ALTER TABLE libraries ADD COLUMN filename TEXT NOT NULL DEFAULT ''")
    db.execute("ALTER TABLE libraries ADD COLUMN source_path TEXT NOT NULL DEFAULT ''")
    sources=DEST.parent.parent/'dictionary-sources'; sources.mkdir(parents=True,exist_ok=True)
    for i,spec in enumerate(manifest['sources'],1):
        original=(ROOT/'app/src/main/assets/dictionaries' if i==1 else ROOT/'third_party/wubi_supplements')/spec['filename']
        shutil.copyfile(original,sources/spec['filename'])
        db.execute('UPDATE libraries SET filename=? WHERE id=?',(spec['filename'],i))
    for i,name in [(8,'pinyin_simp.dict.yaml'),(9,'english_frequency.tsv'),(10,'english_extended.tsv')]:
        shutil.copyfile(ROOT/'app/src/main/assets/dictionaries'/name,sources/name)
        db.execute('UPDATE libraries SET filename=? WHERE id=?',(name,i))
    db.commit(); db.execute('VACUUM'); db.close()
    print(DEST)
if __name__=='__main__': build()

# Compile exactly the same immutable schema as the Android compiler.
def compile_runtime():
    import re
    path=DEST.with_name('runtime.db'); path.unlink(missing_ok=True)
    source=sqlite3.connect(DEST); db=sqlite3.connect(path)
    db.execute('CREATE TABLE words(kind TEXT NOT NULL,text TEXT NOT NULL,code TEXT NOT NULL,weight INTEGER NOT NULL,ordinal INTEGER NOT NULL,priority INTEGER NOT NULL,compact TEXT NOT NULL,fuzzy TEXT NOT NULL,syllable TEXT NOT NULL,UNIQUE(kind,text,code))')
    for ordinal,(kind,text,code,weight,priority) in enumerate(source.execute('SELECT l.kind,e.text,e.code,e.weight,l.priority FROM entries e JOIN libraries l ON l.id=e.library ORDER BY l.id,e.id')):
        compact=code.replace(' ',''); fuzzy=''
        if kind=='pinyin':
            fuzzy=compact.replace('zh','z').replace('ch','c').replace('sh','s')
            fuzzy=re.sub('n(?=[aeiouv])','l',fuzzy).replace('r','l').replace('f','h').replace('ang','an').replace('eng','en').replace('ing','in')
        priority=ordinal if priority else 9223372036854775807
        syllable=code if kind=='pinyin' and ' ' not in code and len(text)==1 else ''
        db.execute('INSERT OR IGNORE INTO words VALUES(?,?,?,?,?,?,?,?,?)',(kind,text,code,weight,ordinal,priority,compact,fuzzy,syllable))
        if priority!=9223372036854775807: db.execute('UPDATE words SET priority=min(priority,?) WHERE kind=? AND text=? AND code=?',(priority,kind,text,code))
    db.execute('CREATE INDEX exact_words ON words(kind,compact,priority,weight DESC,ordinal)')
    db.execute("CREATE INDEX fuzzy_words ON words(kind,fuzzy,weight DESC,ordinal) WHERE kind='pinyin'")
    db.execute("CREATE INDEX syllable_words ON words(kind,syllable,weight DESC,ordinal) WHERE kind='pinyin'")
    db.commit(); db.execute('VACUUM'); db.close(); source.close(); print(path)
if __name__=='__main__': compile_runtime()
