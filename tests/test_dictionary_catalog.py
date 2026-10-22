"""Full Wubi ordering parity and packaged catalog integrity; no Android required."""
import sqlite3
import struct
import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
ASSETS=ROOT/'app/src/main/assets/dictionaries'
GENERATED=ROOT/'app/build/generated/dictionaryCatalog/dictionaries'

def decode(key):
    chars=[]
    for _ in range(4):
        key,digit=divmod(key,27)
        if digit: chars.append(chr(96+digit))
    return ''.join(reversed(chars))

class CatalogTest(unittest.TestCase):
    def test_catalog_sources_and_counts(self):
        with sqlite3.connect(GENERATED/'catalog.db') as db:
            self.assertEqual('ok',db.execute('pragma integrity_check').fetchone()[0])
            self.assertEqual([136916,12,1522,481,316743,141,32633,65125,25000,108051,16855],
                [row[0] for row in db.execute('select count(*) from entries group by library order by library')])
    def test_empty_priority_templates_are_in_catalog(self):
        with sqlite3.connect(GENERATED/'catalog.db') as db:
            self.assertEqual(13,db.execute('select count(*) from libraries').fetchone()[0])
            for name,kind in [('pinyin_high_priority.dict.yaml','pinyin'),('english_high_priority.tsv','english')]:
                row=db.execute('select id,kind,priority from libraries where filename=?',(name,)).fetchone()
                self.assertEqual((kind,1),row[1:])
                self.assertEqual(0,db.execute('select count(*) from entries where library=?',(row[0],)).fetchone()[0])
            self.assertEqual('1',db.execute("select value from metadata where key='priority_libraries_added'").fetchone()[0])

    def test_english_extension_coverage_and_common_ranking(self):
        core={line.split('\t')[0]:int(line.split('\t')[1]) for line in (ASSETS/'english_frequency.tsv').read_text().splitlines() if line and not line.startswith('#')}
        with sqlite3.connect(GENERATED/'runtime.db') as db:
            words=dict(db.execute("select text,weight from words where kind='english'"))
            for word in ['asynchronous','refactoring','serialization','neuroscience','virtualization','algorithmic']:
                self.assertNotIn(word,core)
                self.assertEqual(1,words[word])
            for word,weight in core.items(): self.assertEqual(weight,words[word])
            self.assertEqual(['what','we','was','with','well','will','want','who'],[r[0] for r in db.execute("select text from words where kind='english' and compact>'w' and compact<'w{' order by weight desc,text limit 8")])
    def test_all_wubi_exact_groups_and_prefix_order(self):
        data=(ASSETS/'compact-wubi.skwb').read_bytes()
        _,_,entries,exact_count,prefix_count,exact_start,prefix_start,refs_start,text_start,_,_=struct.unpack_from('>8s10I',data)
        def text(offset):
            length=struct.unpack_from('>H',data,offset)[0]
            return data[offset+2:offset+2+length].decode(),offset+2+length
        expected=[]
        for i in range(exact_count):
            code,offset,count=struct.unpack_from('>III',data,exact_start+i*12)
            for _ in range(count):
                word,offset=text(offset);expected.append((decode(code),word))
        with sqlite3.connect(GENERATED/'runtime.db') as db:
            actual=db.execute("select code,text from words where kind='wubi' order by compact,priority,weight desc,ordinal").fetchall()
            self.assertEqual(entries,len(actual));self.assertEqual(expected,actual)
            # Rebuild prefix selection from the SQL ordinary ranking, independent of priority.
            prefixes={}
            for code,word in db.execute("select code,text from words where kind='wubi' order by weight desc,ordinal"):
                for size in range(1,len(code)):
                    group=prefixes.setdefault(code[:size],[])
                    if len(group)<30:group.append((word,code))
            for i in range(prefix_count):
                code,first,count=struct.unpack_from('>III',data,prefix_start+i*12)
                group=[]
                for j in range(count):
                    offset,full=struct.unpack_from('>II',data,refs_start+(first+j)*8)
                    group.append((text(offset)[0],decode(full)))
                self.assertEqual(group,prefixes[decode(code)],decode(code))
    def test_mixed_scope_and_initials(self):
        with sqlite3.connect(GENERATED/'runtime.db') as db:
            self.assertEqual(65125,db.execute("select count(*) from words where kind='mixed_pinyin'").fetchone()[0])
            self.assertEqual(16855,db.execute("select count(*) from words where kind='pinyin_initial'").fetchone()[0])
            expected={}
            for line in (ASSETS/'pinyin_simp.dict.yaml').read_text().split('...\n',1)[1].splitlines():
                if not line or line.startswith('#'): continue
                parts=line.split('\t')
                if len(parts)<2 or len(parts[0])!=1 or not parts[1].isascii() or not parts[1].isalpha(): continue
                key=(parts[0],parts[1][0]); expected[key]=max(expected.get(key,0),int(parts[2]) if len(parts)>2 else 0)
            self.assertEqual(expected,{(word,code):weight for word,code,weight in db.execute("select text,code,weight from words where kind='pinyin_initial'")})
            self.assertEqual(['你','那','呢','能','年'],[r[0] for r in db.execute("select text from words where kind='pinyin_initial' and code='n' order by weight desc,ordinal limit 5")])
            self.assertEqual([],db.execute("select text from words where kind='mixed_pinyin' and code='b'").fetchall())
    def test_short_prefix_index_matches_unbounded_ranking(self):
        with sqlite3.connect(GENERATED/'runtime.db') as db:
            for kind,limit,order in [('wubi',30,'weight desc,ordinal'),('english',12,'weight desc,text')]:
                expected={}
                for rowid,code in db.execute(f"select rowid,compact from words where kind='{kind}' order by {order}"):
                    for length in range(1,min(2,len(code)-1)+1):
                        bucket=expected.setdefault(code[:length],[])
                        if len(bucket)<limit: bucket.append(rowid)
                actual={}
                for prefix,rowid in db.execute('select prefix,word_id from short_prefixes where kind=? order by prefix,rank',(kind,)):
                    actual.setdefault(prefix,[]).append(rowid)
                self.assertEqual(expected,actual)
                self.assertNotIn('',actual)

    def test_query_plans_use_ranked_and_covering_indexes(self):
        with sqlite3.connect(GENERATED/'runtime.db') as db:
            for kind in ['pinyin','mixed_pinyin','pinyin_initial']:
                ranking='weight DESC,ordinal' if kind=='pinyin_initial' else 'priority,weight DESC,ordinal'
                plan=str(db.execute(f"EXPLAIN QUERY PLAN SELECT text,code,weight FROM words WHERE kind='{kind}' AND kind IN ('pinyin','mixed_pinyin','pinyin_initial') AND compact='n' ORDER BY {ranking} LIMIT 64").fetchall())
                self.assertIn('ranked_exact_words' if kind=='pinyin_initial' else 'exact_words',plan)
                self.assertNotIn('TEMP B-TREE',plan)
            plan=str(db.execute("EXPLAIN QUERY PLAN SELECT w.text,w.code,w.weight FROM short_prefixes p JOIN words w ON w.rowid=p.word_id WHERE p.kind='english' AND p.prefix='w' ORDER BY p.rank LIMIT 12").fetchall())
            self.assertNotIn('TEMP B-TREE',plan)
            plan=str(db.execute("EXPLAIN QUERY PLAN SELECT text FROM words WHERE kind='pinyin' AND kind IN ('pinyin','mixed_pinyin') AND text>'中国' AND text<'中国\uffff' ORDER BY weight DESC LIMIT 12").fetchall())
            self.assertIn('COVERING INDEX prediction_words',plan)

    def test_runtime_only_readable_rows(self):
        with sqlite3.connect(GENERATED/'runtime.db') as db:
            self.assertEqual('ok',db.execute('pragma integrity_check').fetchone()[0])
            self.assertEqual(133051,db.execute("select count(*) from words where kind='english'").fetchone()[0])
            self.assertEqual(65125,db.execute("select count(*) from words where kind='pinyin'").fetchone()[0])
if __name__=='__main__':unittest.main()
