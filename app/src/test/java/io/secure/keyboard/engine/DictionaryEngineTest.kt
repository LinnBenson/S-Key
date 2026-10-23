package io.secure.keyboard.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class DictionaryEngineTest {
    companion object {
        private val engine: DictionaryEngine by lazy {
            val root = File("src/test/resources/dictionaries")
            DictionaryEngine(
                File(root, "wubi86.dict.yaml").reader(),
                File(root, "pinyin_simp.dict.yaml").reader(),
                EnglishLexicon(File(root, "english_frequency.tsv").reader()),
            )
        }
    }

    @Test
    fun exactWubiLookupNeverReturnsPrefixesOrPinyin() {
        assertEquals("使", engine.exactWubi("wgkq", 1).single().text)
        assertEquals("你", engine.exactWubi("wq", 1).single().text)
        assertTrue(engine.exactWubi("nihao").isEmpty())
        assertTrue(engine.exactWubi("wq").all { it.code == "wq" && it.source == Source.WUBI })
        assertTrue(engine.exactWubi("ni", 0).isEmpty())
    }

    @Test
    fun fullDictionariesLoaded() {
        assertEquals(136916, engine.wubiEntryCount)
        assertEquals(65125, engine.pinyinEntryCount)
    }

    @Test
    fun wubiComesFirstWhenCodesOverlap() {
        assertEquals("你", engine.candidates("wq").first().text)
        assertEquals(Source.WUBI, engine.candidates("ni").first().source)
        assertTrue(engine.candidates("ni").any { it.text == "你" && it.source == Source.PINYIN })
    }

    @Test
    fun phrasesAndFuzzyAreReal() {
        assertEquals("你好", engine.candidates("nihao").first().text)
        assertTrue(engine.candidates("zongguo", true).any { it.text == "中国" })
        assertFalse(engine.candidates("zongguo", false).any { it.text == "中国" })
        assertEquals("你好我是中国人", engine.candidates("nihaowoshizhongguoren").first().text)
    }

    @Test
    fun realRLInitialCandidatesFollowTheIndependentSwitch() {
        for ((typed, expected) in listOf("le" to "热", "re" to "乐", "luanjian" to "软件")) {
            assertTrue(
                "$typed must offer $expected when r/l is enabled",
                engine.candidates(typed, fuzzyPairs = setOf("r_l")).any {
                    it.text == expected && it.source == Source.PINYIN
                },
            )
            assertFalse(
                "$typed must not offer $expected when r/l is disabled",
                engine.candidates(typed, fuzzyPairs = emptySet()).any {
                    it.text == expected && it.source == Source.PINYIN
                },
            )
            assertFalse(
                engine.candidates(typed, false, fuzzyPairs = setOf("r_l")).any {
                    it.text == expected && it.source == Source.PINYIN
                }
            )
        }
        assertFalse(engine.candidates("el", fuzzyPairs = setOf("r_l")).any { it.text == "而" })
        assertFalse(engine.candidates("len", fuzzyPairs = setOf("r_l")).any { it.text == "人" })
        assertTrue(engine.candidates("er", fuzzyPairs = setOf("r_l")).any { it.text == "而" })
    }

    @Test
    fun apostropheDisambiguatesSyllables() {
        assertEquals("西安", engine.candidates("xi'an", false).first().text)
        assertFalse(engine.candidates("xi'an", false).any { it.text == "先" })
    }

    @Test
    fun currentWordCompletionOnly() {
        assertTrue(engine.completeEnglish("hel").any { it.text == "hello" })
        assertTrue(engine.completeEnglish("Hel").any { it.text == "Hello" })
        assertTrue(engine.completeEnglish("HEL").any { it.text == "HELLO" })
        assertTrue(engine.completeEnglish("").isEmpty())
        assertTrue(engine.completeEnglish("hello ").isEmpty())
    }

    @Test
    fun invalidInputAndLimits() {
        assertTrue(engine.candidates("").isEmpty())
        assertTrue(engine.candidates("ni123").isEmpty())
        assertTrue(engine.candidates("n".repeat(65)).isEmpty())
        assertTrue(engine.candidates("ni", limit = 0).isEmpty())
        assertTrue(engine.candidates("ni", limit = 3).size <= 3)
        assertEquals(engine.candidates("nihao"), engine.candidates("nihao"))
        assertEquals(engine.candidates("ni").distinctBy { it.text }, engine.candidates("ni"))
    }

    @Test
    fun compactIndexesPreserveStableWeightOrderAndExactBeforePrefix() {
        val fixture =
            DictionaryEngine(
                ("...\n" +
                        listOf(
                                "低\tabc\t10",
                                "并列甲\tabc\t20",
                                "并列乙\tabc\t20",
                                "重复\tabc\t30",
                                "重复\tabc\t25",
                                "前缀\tabcd\t100",
                            )
                            .joinToString("\n"))
                    .reader(),
                "...\n".reader(),
            )

        assertEquals(6, fixture.wubiEntryCount)
        assertEquals(0, fixture.pinyinEntryCount)
        assertEquals(listOf("重复", "并列甲", "并列乙", "低"), fixture.exactWubi("abc").map { it.text })
        assertEquals(
            listOf("重复", "并列甲", "并列乙", "低", "前缀"),
            fixture.candidates("abc", false).map { it.text },
        )
        assertEquals(
            listOf("前缀", "重复", "并列甲", "并列乙", "低"),
            fixture.candidates("ab", false).map { it.text },
        )
    }

    @Test
    fun compactPrefixBucketsStillCapAtThirtyWithoutTruncatingExactMatches() {
        val rows = (0 until 40).map { "词$it\tabcd\t$it" }
        val fixture =
            DictionaryEngine(("...\n" + rows.joinToString("\n")).reader(), "...\n".reader())

        assertEquals(40, fixture.wubiEntryCount)
        assertEquals((39 downTo 0).map { "词$it" }, fixture.exactWubi("abcd", 40).map { it.text })
        assertEquals(
            (39 downTo 10).map { "词$it" },
            fixture.candidates("ab", false, 40).map { it.text },
        )
    }

    @Test
    fun compactPinyinBucketsPreserveExactPriorityTiesAndSyllableBoundaries() {
        val fixture =
            DictionaryEngine(
                "...\n".reader(),
                ("...\n" +
                        listOf(
                                "并列甲\tzhong guo\t30",
                                "并列乙\tzhong guo\t30",
                                "精确\tzong guo\t10",
                                "不同分词\tzongguo\t50",
                            )
                            .joinToString("\n"))
                    .reader(),
            )

        assertEquals(0, fixture.wubiEntryCount)
        assertEquals(4, fixture.pinyinEntryCount)
        assertEquals(
            listOf("精确", "并列甲", "并列乙"),
            fixture.candidates("zong'guo", fuzzyPairs = setOf("zh_z")).map { it.text },
        )
        assertEquals(listOf("精确"), fixture.candidates("zong'guo", false).map { it.text })
    }
}
