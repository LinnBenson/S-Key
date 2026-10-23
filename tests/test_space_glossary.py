import importlib.util
from pathlib import Path
import unittest
spec=importlib.util.spec_from_file_location('gloss',Path(__file__).resolve().parents[1]/'scripts/build_space_glossary.py')
gloss=importlib.util.module_from_spec(spec);spec.loader.exec_module(gloss)
class GlossTest(unittest.TestCase):
    def test_whole_word_and_short_senses_only(self):
        data=gloss.entries('蘋果 苹果 [ping2 guo3] /apple/\n行 行 [xing2] /go/\n測試 测试 [ce4 shi4] /see other/to test/\n某詞 某词 [mou3 ci2] /a very long explanation of a Chinese term/\n名字 名字 [ming2 zi4] /John/name/')
        self.assertEqual({'蘋果':'apple','苹果':'apple','測試':'to test','测试':'to test','名字':'name'},data)
