"""Release checks must reject unwanted assets, stale indexes and changed source bytes."""
import importlib.util
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('verify_dictionary_apk',ROOT/'scripts/verify_dictionary_apk.py')
check=importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)

class Archive:
    def __init__(self,data,duplicate=False): self.data=data; self.duplicate=duplicate
    def namelist(self): return list(self.data)+([next(iter(self.data))] if self.duplicate else [])
    def read(self,name): return self.data[name]

class DictionaryApkTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data={}
        for p in (ROOT/'app/build/generated/spaceGlossary/learning').iterdir():
            cls.data['assets/learning/'+p.name]=p.read_bytes()
        for p in (ROOT/'app/build/generated/dictionaryCatalog/dictionary-sources').iterdir():
            cls.data['assets/dictionary-sources/'+p.name]=p.read_bytes()
        for p in (ROOT/'app/src/main/assets/dictionaries').iterdir():
            cls.data['assets/dictionaries/'+p.name]=p.read_bytes()
        for name in ['catalog.db','runtime.db']:
            cls.data['assets/dictionaries/'+name]=(ROOT/'app/build/generated/dictionaryCatalog/dictionaries'/name).read_bytes()
    def test_current_assets_pass(self):
        self.assertEqual(6,len(check.verify_archive(Archive(self.data))['sources']))
    def test_extra_dictionary_is_rejected(self):
        for name in ['assets/dictionary-sources/unwanted.tsv','assets/unexpected.db','assets/learning/unused.bin']:
            with self.assertRaises(AssertionError): check.verify_archive(Archive(dict(self.data,**{name:b'bad'})))
    def test_missing_source_is_rejected(self):
        data=dict(self.data); del data['assets/dictionary-sources/skey_wubi86.tsv']
        with self.assertRaises(AssertionError): check.verify_archive(Archive(data))
    def test_changed_source_priority_or_index_is_rejected(self):
        for name in ['assets/dictionary-sources/skey_wubi86.tsv','assets/dictionary-sources/skey_pinyin_high.tsv','assets/dictionaries/catalog.db','assets/dictionaries/runtime.db','assets/learning/glossary.bin']:
            with self.assertRaises(AssertionError): check.verify_archive(Archive(dict(self.data,**{name:b'changed'})))
    def test_duplicate_entry_is_rejected(self):
        with self.assertRaises(AssertionError): check.verify_archive(Archive(self.data,duplicate=True))

if __name__=='__main__': unittest.main()
