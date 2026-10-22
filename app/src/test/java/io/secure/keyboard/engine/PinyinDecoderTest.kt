package io.secure.keyboard.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader

class PinyinDecoderTest {
    @org.junit.Test fun exactPriorityOverridesFrequencyAndContextWithoutReweightingSegments() {
        val decoder=PinyinDecoder(lookup={ token ->
            if(token=="nihao") listOf(
                DictionaryEngine.Entry("拟好","ni hao",0,10),
                DictionaryEngine.Entry("你好","ni hao",1000000,11),
                DictionaryEngine.Entry("霓皓","ni hao",999999999)
            ) else emptyList()
        },predict={ listOf("霓皓") })
        org.junit.Assert.assertEquals(listOf("拟好","你好","霓皓"),decoder.candidates("nihao","你好").map { it.text })
    }

    @org.junit.Test fun obsoleteQueriesStopBeforeTraversingTheWholeLattice() {
        var queries = 0
        val decoder = PinyinDecoder(
            lookup = { token -> queries++; listOf(DictionaryEngine.Entry("字", token, 1)) },
            cancelled = { queries >= 3 },
        )
        org.junit.Assert.assertTrue(decoder.candidates("a".repeat(128)).isEmpty())
        org.junit.Assert.assertEquals(3, queries)
    }

    private val rows = listOf(
        DictionaryEngine.Entry("你", "ni", 1000), DictionaryEngine.Entry("好", "hao", 1000),
        DictionaryEngine.Entry("你好", "ni hao", 5000), DictionaryEngine.Entry("我", "wo", 1000),
        DictionaryEngine.Entry("是", "shi", 3000), DictionaryEngine.Entry("中国人", "zhong guo ren", 5000),
        DictionaryEngine.Entry("西安", "xi an", 100), DictionaryEngine.Entry("先", "xian", 1000),
        DictionaryEngine.Entry("世", "shi", 6000),
    )
    private fun decoder() = PinyinDecoder({ raw -> rows.filter {
        it.code.replace(" ", "") == raw.replace("'", "") && (!raw.contains('\'') || it.code.replace(" ", "'") == raw)
    } }, { if(it.endsWith("我")) listOf("是") else if(it.endsWith("中国")) listOf("人") else emptyList() })
    @Test fun wholeSentenceAndPartialSelection() {
        val choices=decoder().candidates("nihaowoshizhongguoren")
        assertTrue(choices.any { it.text=="你好我是中国人" && it.consumed==21 })
        assertTrue(choices.any { it.text=="你好" && it.consumed==5 })
        val composition=PinyinComposition()
        composition.select("你好","nihao")
        assertEquals("你好",composition.text)
        assertEquals("nihao",composition.undo())
        assertEquals("",composition.text)
    }
    @Test fun explicitBoundariesAndContext() {
        assertFalse(decoder().candidates("xi'an").any { it.text=="先" })
        assertEquals("西安", decoder().candidates("xi'an").first().text)
        assertEquals("是", decoder().candidates("shi", "我").first().text)
        assertEquals("人", decoder().candidates("", "中国").first().text)
    }
    @Test fun customWordsAndLimits() {
        assertEquals("倪好",decoder().candidates("nihao",custom=listOf("ni hao" to "倪好")).first().text)
        assertTrue(decoder().candidates("a".repeat(129)).isEmpty())
        assertTrue(decoder().candidates("<html>").isEmpty())
    }
    @Test fun modesAreIsolatedAndDefaultMixed() {
        val engine=DictionaryEngine(StringReader("...\n五笔\tni\t100\n"), StringReader("...\n你\tni\t100\n"))
        assertEquals(ChineseInputMode.MIXED,ChineseInputMode.fromId(null))
        assertEquals(listOf("五笔"),engine.candidates("ni",mode=ChineseInputMode.WUBI).map { it.text })
        assertEquals(listOf("你"),engine.candidates("ni",mode=ChineseInputMode.PINYIN).map { it.text })
        assertEquals(listOf("五笔","你"),engine.candidates("ni").map { it.text })
    }
}
