"""Verify imported weights, exact ordering, prefix indexes and catalog integrity."""
import sqlite3
import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
GENERATED=ROOT/'app/build/generated/dictionaryCatalog/dictionaries'

class CatalogTest(unittest.TestCase):
    def test_sources_counts_and_empty_priorities(self):
        with sqlite3.connect(GENERATED/'catalog.db') as db:
            self.assertEqual('ok', db.execute('pragma integrity_check').fetchone()[0])
            rows=db.execute('select filename,priority,(select count(*) from entries where library=l.id) from libraries l order by id').fetchall()
            self.assertEqual([
                ('skey_wubi86.tsv',0,136916),('skey_wubi86_high.tsv',1,0),
                ('skey_pinyin.tsv',0,65125),('skey_pinyin_high.tsv',1,0),
                ('skey_english.tsv',0,25000),('skey_english_high.tsv',1,0)],rows)
    def test_all_imported_weights_and_order(self):
        with sqlite3.connect(GENERATED/'runtime.db') as db:
            self.assertEqual('ok',db.execute('pragma integrity_check').fetchone()[0])
            for kind in ['wubi','pinyin','english']:
                filename='skey_wubi86.tsv' if kind=='wubi' else f'skey_{kind}.tsv'
                expected=[]
                for line in (ROOT/'third_party/open_dictionaries'/filename).read_text().splitlines():
                    if not line or line.startswith('#'): continue
                    fields=line.split('\t')
                    text,code,weight=(fields[0],fields[0],fields[1]) if kind=='english' else fields
                    expected.append((text,code,int(weight)))
                self.assertEqual(expected,db.execute('select text,code,weight from words where kind=? order by ordinal',(kind,)).fetchall())
                expected.sort(key=lambda r:(r[1].replace(' ',''),-r[2]))
                self.assertEqual(expected,db.execute('select text,code,weight from words where kind=? order by compact,priority,weight desc,ordinal',(kind,)).fetchall())
            self.assertEqual(db.execute("select text,code,weight,ordinal from words where kind='pinyin' order by ordinal").fetchall(),db.execute("select text,code,weight,ordinal from words where kind='mixed_pinyin' order by ordinal").fetchall())
            expected=db.execute("select text,substr(code,1,1),max(weight) from words where kind='pinyin' and length(text)=1 and instr(code,' ')=0 group by text,substr(code,1,1) order by text,substr(code,1,1)").fetchall()
            self.assertEqual(expected,db.execute("select text,code,weight from words where kind='pinyin_initial' order by text,code").fetchall())
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

if __name__=='__main__':unittest.main()
