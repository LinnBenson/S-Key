#!/usr/bin/env python3
"""Build the six-library licensed source catalog and query indexes, offline."""
import json, sqlite3, shutil, hashlib
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / 'app/build/generated/dictionaryCatalog/dictionaries/catalog.db'
SPECS = [
    ('S Key 五笔 86', 'wubi', 'skey_wubi86.tsv', 0),
    ('本地高优先级词库', 'wubi', 'skey_wubi86_high.tsv', 1),
    ('S Key 拼音', 'pinyin', 'skey_pinyin.tsv', 0),
    ('本地高优先级词库', 'pinyin', 'skey_pinyin_high.tsv', 1),
    ('S Key 英语', 'english', 'skey_english.tsv', 0),
    ('本地高优先级词库', 'english', 'skey_english_high.tsv', 1),
]

def build():
    DEST.parent.mkdir(parents=True, exist_ok=True)
    DEST.unlink(missing_ok=True)
    sources = DEST.parent.parent/'dictionary-sources'
    if sources.exists(): shutil.rmtree(sources)
    sources.mkdir()
    report = json.loads((ROOT/'third_party/open_dictionaries/manifest.json').read_text())
    with sqlite3.connect(DEST) as db:
        db.executescript("""PRAGMA user_version=1;
        CREATE TABLE libraries(id INTEGER PRIMARY KEY,name TEXT NOT NULL,kind TEXT NOT NULL,priority INTEGER NOT NULL DEFAULT 0,notice TEXT NOT NULL DEFAULT '',filename TEXT NOT NULL DEFAULT '',source_path TEXT NOT NULL DEFAULT '');
        CREATE TABLE entries(id INTEGER PRIMARY KEY,library INTEGER NOT NULL,text TEXT NOT NULL,code TEXT NOT NULL,weight INTEGER NOT NULL);
        CREATE INDEX entry_library ON entries(library,id);
        CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL);
        INSERT INTO metadata VALUES('dirty','0');
        INSERT INTO metadata VALUES('catalog_version','open-1');
        """)
        for i,(name,kind,filename,priority) in enumerate(SPECS,1):
            source_name = filename
            source = ROOT/('app/src/main/assets/dictionaries' if priority else 'third_party/open_dictionaries')/source_name
            raw = source.read_bytes()
            if not priority:
                assert hashlib.sha256(raw).hexdigest() == report[source_name]['sha256'], filename
            shutil.copyfile(source,sources/filename)
            notice = 'Local user-maintained entries' if priority else report[filename]['license']
            db.execute('INSERT INTO libraries VALUES(?,?,?,?,?,?,?)',(i,name,kind,priority,notice,filename,''))
            body=raw.decode()
            if filename.endswith('.yaml'): body=body.split('...\n',1)[1]
            rows=[]
            for line in body.splitlines():
                if not line or line.startswith('#'): continue
                parts=line.split('\t')
                if kind=='english': text,weight=parts;code=text
                else: text,code,weight=parts
                rows.append((i,text,code,int(weight)))
            if priority: assert not rows, 'Factory high-priority templates must be empty'
            db.executemany('INSERT INTO entries(library,text,code,weight) VALUES(?,?,?,?)',rows)
        db.commit()
        db.execute('VACUUM')
    print(DEST)

# Compile exactly the same immutable schema as the Android compiler.
def compile_runtime():
    import re
    path=DEST.with_name('runtime.db'); path.unlink(missing_ok=True)
    source=sqlite3.connect(DEST); db=sqlite3.connect(path)
    db.execute('PRAGMA user_version=2')
    db.execute('CREATE TABLE words(kind TEXT NOT NULL,text TEXT NOT NULL,code TEXT NOT NULL,weight INTEGER NOT NULL,ordinal INTEGER NOT NULL,priority INTEGER NOT NULL,compact TEXT NOT NULL,fuzzy TEXT NOT NULL,syllable TEXT NOT NULL,UNIQUE(kind,text,code))')
    for ordinal,(kind,text,code,weight,priority,filename) in enumerate(source.execute('SELECT l.kind,e.text,e.code,e.weight,l.priority,l.filename FROM entries e JOIN libraries l ON l.id=e.library ORDER BY l.id,e.id')):
        compact=code.replace(' ',''); fuzzy=''
        if kind=='pinyin':
            fuzzy=compact.replace('zh','z').replace('ch','c').replace('sh','s')
            fuzzy=re.sub('n(?=[aeiouv])','l',fuzzy).replace('r','l').replace('f','h').replace('ang','an').replace('eng','en').replace('ing','in')
        priority=ordinal if priority else 9223372036854775807
        syllable=code if kind=='pinyin' and ' ' not in code and len(text)==1 else ''
        db.execute('INSERT OR IGNORE INTO words VALUES(?,?,?,?,?,?,?,?,?)',(kind,text,code,weight,ordinal,priority,compact,fuzzy,syllable))
        if kind=='pinyin' and filename=='skey_pinyin.tsv': db.execute('INSERT OR IGNORE INTO words VALUES(?,?,?,?,?,?,?,?,?)',('mixed_pinyin',text,code,weight,ordinal,priority,compact,fuzzy,syllable))
        if priority!=9223372036854775807: db.execute('UPDATE words SET priority=min(priority,?) WHERE kind=? AND text=? AND code=?',(priority,kind,text,code))
    db.execute("INSERT INTO words SELECT 'pinyin_initial',text,substr(code,1,1),max(weight),min(ordinal),9223372036854775807,substr(code,1,1),'','' FROM words WHERE kind='pinyin' AND length(text)=1 AND instr(code,' ')=0 GROUP BY text,substr(code,1,1)")
    db.execute('CREATE INDEX exact_words ON words(kind,compact,priority,weight DESC,ordinal)')
    db.execute("CREATE INDEX fuzzy_words ON words(kind,fuzzy,weight DESC,ordinal) WHERE kind='pinyin'")
    db.execute("CREATE INDEX syllable_words ON words(kind,syllable,weight DESC,ordinal) WHERE kind='pinyin'")
    db.execute("CREATE INDEX mixed_fuzzy_words ON words(kind,fuzzy,weight DESC,ordinal) WHERE kind='mixed_pinyin'")
    db.execute("CREATE INDEX mixed_syllable_words ON words(kind,syllable,weight DESC,ordinal) WHERE kind='mixed_pinyin'")
    db.execute("CREATE INDEX ranked_exact_words ON words(kind,compact,weight DESC,ordinal) WHERE kind='pinyin_initial'")
    db.execute("CREATE INDEX prediction_words ON words(kind,text,weight DESC) WHERE kind IN ('pinyin','mixed_pinyin')")
    db.execute("CREATE TABLE short_prefixes(kind TEXT NOT NULL,prefix TEXT NOT NULL,rank INTEGER NOT NULL,word_id INTEGER NOT NULL,PRIMARY KEY(kind,prefix,rank)) WITHOUT ROWID")
    for kind,limit,order in [('wubi',30,'weight DESC,ordinal'),('english',12,'weight DESC,text')]:
        counts={}
        for rowid,code in db.execute(f"SELECT rowid,compact FROM words WHERE kind='{kind}' ORDER BY {order}"):
            for size in range(1,min(2,len(code)-1)+1):
                prefix=code[:size]; rank=counts.get(prefix,0)
                if rank>=limit: continue
                db.execute('INSERT INTO short_prefixes VALUES(?,?,?,?)',(kind,prefix,rank,rowid))
                counts[prefix]=rank+1
    db.execute('PRAGMA user_version=3')
    db.commit(); db.execute('VACUUM'); db.close(); source.close(); print(path)
if __name__=='__main__':
    build()
    compile_runtime()
