"""Compare warm SQLite lookup latency; pass immutable before/after runtime databases."""
import argparse, sqlite3, time, statistics, json, os
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--before',required=True)
parser.add_argument('--after',required=True)
args=parser.parse_args()
paths=[args.before,args.after]
cases=[
('五笔前缀 a',"SELECT text,code,weight FROM words WHERE kind='wubi' AND compact>'a' AND compact<'a{' ORDER BY weight DESC,ordinal LIMIT 30", "SELECT w.text,w.code,w.weight FROM short_prefixes p JOIN words w ON w.rowid=p.word_id WHERE p.kind='wubi' AND p.prefix='a' ORDER BY p.rank LIMIT 30"),
('英文前缀 s',"SELECT text,code,weight FROM words WHERE kind='english' AND compact>'s' AND compact<'s{' ORDER BY weight DESC,text LIMIT 12", "SELECT w.text,w.code,w.weight FROM short_prefixes p JOIN words w ON w.rowid=p.word_id WHERE p.kind='english' AND p.prefix='s' ORDER BY p.rank LIMIT 12"),
('拼音 ni',"SELECT text,code,weight FROM words WHERE kind='pinyin' AND compact='ni' ORDER BY weight DESC,ordinal LIMIT 64","SELECT text,code,weight FROM words WHERE kind='pinyin' AND kind IN ('pinyin','mixed_pinyin','pinyin_initial') AND compact='ni' ORDER BY priority,weight DESC,ordinal LIMIT 64"),
('首字母 y',"SELECT text,code,weight FROM words WHERE kind='pinyin_initial' AND compact='y' ORDER BY weight DESC,ordinal","SELECT text,code,weight FROM words WHERE kind='pinyin_initial' AND kind IN ('pinyin','mixed_pinyin','pinyin_initial') AND compact='y' ORDER BY weight DESC,ordinal"),
('拼音联想 中国',"SELECT text,code,weight FROM words WHERE kind='pinyin' AND text>'中国' AND text<'中国\uffff' ORDER BY weight DESC LIMIT 12", "SELECT text FROM words WHERE kind='pinyin' AND kind IN ('pinyin','mixed_pinyin') AND text>'中国' AND text<'中国\uffff' ORDER BY weight DESC LIMIT 12")]
results=[]
for name,old,new in cases:
 times=[]; outputs=[]
 for path,sql in zip(paths,[old,new or old]):
  with sqlite3.connect(path) as db:
   db.execute('PRAGMA cache_size=-2048')
   for _ in range(5): db.execute(sql).fetchall()
   samples=[]
   for _ in range(40):
    start=time.perf_counter(); rows=db.execute(sql).fetchall(); samples.append((time.perf_counter()-start)*1000)
   times.append(round(statistics.median(samples),4)); outputs.append([r[0] for r in rows])
 assert outputs[0]==outputs[1],name
 results.append({'case':name,'before_ms':times[0],'after_ms':times[1]})
print(json.dumps({'median_40_warm_queries':results,'database_bytes':[os.path.getsize(p) for p in paths]},ensure_ascii=False,indent=2))
