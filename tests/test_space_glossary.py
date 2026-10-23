import importlib.util
from pathlib import Path
import unittest
spec=importlib.util.spec_from_file_location('gloss',Path(__file__).resolve().parents[1]/'scripts/build_space_glossary.py')
gloss=importlib.util.module_from_spec(spec);spec.loader.exec_module(gloss)
class GlossTest(unittest.TestCase):
    def test_complete_definitions_and_case(self):
        data=gloss.entries('蘋果 苹果 [Ping2 guo3] /Apple (American tech company)/\n蘋果 苹果 [ping2 guo3] /apple/\n行 行 [xing2] /go/\n英語 英语 [ying1 yu3] /English (language)/\n早上好 早上好 [zao3 shang4 hao3] /Good morning!/\n某詞 某词 [mou3 ci2] /a very long explanation of a Chinese term/\n名字 名字 [ming2 zi4] /surname Smith/name/')
        self.assertEqual('English (language)',data['英语'])
        self.assertEqual('Good morning!',data['早上好'])
        self.assertEqual('a very long explanation of a Chinese term',data['某词'])
        self.assertEqual('name',data['名字'])
        self.assertNotIn('行',data)
        self.assertEqual('apple',data['苹果'])
        self.assertEqual(data['苹果'],data['蘋果'])
    def test_redirect_resolution_cycles_and_metadata(self):
        data=gloss.entries('測試 测试 [ce4 shi4] /to test/\n試驗 试验 [shi4 yan4] /see 測試|测试[ce4 shi4]/\n甲甲 甲甲 [jia3 jia3] /see 乙乙/\n乙乙 乙乙 [yi3 yi3] /see 甲甲/\n丙丙 丙丙 [bing3 bing3] /CL:個|个[ge4]/also pr. [bing2]/')
        self.assertEqual('to test',data['试验'])
        self.assertNotIn('甲甲',data)
        self.assertNotIn('丙丙',data)

    def test_punctuation_redirect_and_sentence_case_regressions(self):
        data=gloss.entries('三牲 三牲 [san1 sheng1] /animals (cow, sheep and pig; later chicken)/\n下次見 下次见 [xia4 ci4 jian4] /see you next time/\n什麼事 什么事 [shen2 me5 shi4] /What is up?; Is something wrong?/(coll.) example 中文 "bad; fragment"/\n入手 入手 [ru4 shou3] /example 中文 (first; broken)/to receive; to obtain/')
        self.assertEqual('animals (cow, sheep and pig; later chicken)',data['三牲'])
        self.assertEqual('see you next time',data['下次见'])
        self.assertEqual('What is up?',data['什么事'])
        self.assertEqual('to receive',data['入手'])
