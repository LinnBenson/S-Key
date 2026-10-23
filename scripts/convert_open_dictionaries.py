#!/usr/bin/env python3
"""Deterministically convert pinned, licensed GitHub dictionaries to S Key TSV."""
from pathlib import Path
from decimal import Decimal, ROUND_DOWN
import hashlib, json
ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'third_party/open_dictionaries'
SOURCES = [
 ('wubi86.dict.yaml','skey_wubi86.tsv','wubi','LGPL-3.0','https://github.com/rime/rime-wubi/blob/152a0d3f3efe40cae216d1e3b338242446848d07/wubi86.dict.yaml','f833d86b72341fe82e069a425b6625f29ef85f1bc0f34f6fb7975fe514888b5a'),
 ('pinyin_simp.dict.yaml','skey_pinyin.tsv','pinyin','Apache-2.0','https://github.com/rime/rime-pinyin-simp/blob/0c6861ef7420ee780270ca6d993d18d4101049d0/pinyin_simp.dict.yaml','e341598343a0f0f2035bb1aafc34a7f3bb7887deeecb3f60796262aaa2983e6b'),
 ('english_frequency.tsv','skey_english.tsv','english','CC-BY-SA-4.0; SCOWL notices retained','https://github.com/LinnBenson/S-Key/blob/4b0e4957de249026fac5a0cd3638f9aa2ed904b0/app/src/main/assets/dictionaries/english_frequency.tsv','e818b1f3ea72bc7a391abaa171f717bc5d2f90397fdeef60cad8862202a6d290'),
]
def convert():
 report={}
 for original,filename,kind,license,url,digest in SOURCES:
  raw=(BASE/'upstream'/original).read_bytes()
  assert hashlib.sha256(raw).hexdigest()==digest, original
  text=raw.decode('utf-8'); rows=[]; changes=[]
  header=[line for line in text.splitlines() if line.startswith('#')]
  body=text.split('...\n',1)[1] if original.endswith('.yaml') else text
  for line in body.splitlines():
   if not line.strip() or line.startswith('#'): continue
   parts=line.split('\t')
   if kind=='english':
    word,weight=parts; assert word.isascii() and word.isalpha() and word.islower() and 2<=len(word)<=40
    assert int(weight)>0; rows.append(f'{word}\t{weight}')
   else:
    word,code=parts[:2]; weight=Decimal(parts[2] if len(parts)>2 else '0')
    assert weight.is_finite() and weight>=0
    normalized=int(weight.to_integral_value(rounding=ROUND_DOWN))
    if weight!=normalized: changes.append({'text':word,'code':code,'original':str(weight),'converted':normalized})
    assert 0<=normalized<=9223372036854775807
    assert word and code
    rows.append(f'{word}\t{code}\t{normalized}')
  preface=[f'# Source: {url}',f'# License: {license}', '# Converted 2026-10-24: YAML metadata/stem removed; original entry order retained.', '# Fractional weights rounded down to integer; details in manifest.json.']+header
  output=('\n'.join(preface+rows)+'\n').encode('utf-8')
  (BASE/filename).write_bytes(output)
  report[filename]={'source':original,'url':url,'source_sha256':digest,'sha256':hashlib.sha256(output).hexdigest(),'entries':len(rows),'license':license,'weight_changes':changes}
 (BASE/'manifest.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
 print(json.dumps({k:v['entries'] for k,v in report.items()}))
if __name__=='__main__': convert()
