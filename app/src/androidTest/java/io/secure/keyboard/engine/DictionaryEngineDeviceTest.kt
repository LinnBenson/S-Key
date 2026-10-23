package io.secure.keyboard.engine

import android.os.Bundle
import android.os.SystemClock
import android.test.InstrumentationTestCase

/** Exercises packaged dictionaries on Android, surfacing initialization errors directly. */
@Suppress("DEPRECATION")
class DictionaryEngineDeviceTest : InstrumentationTestCase() {
    fun testExtendedFuzzyRulesUsePackagedIndex() {
        val engine = OfflineEngine(instrumentation.targetContext).also { it.load() }
        for (mode in listOf(ChineseInputMode.MIXED, ChineseInputMode.PINYIN)) {
            assertTrue(engine.candidates("zhongkuo", true, 200, setOf("g_k"), mode = mode).any { it.text == "中国" })
            assertFalse(engine.candidates("zhongkuo", true, 200, emptySet(), mode = mode).any { it.text == "中国" })
        }
        assertTrue(engine.decodePinyin("zhongkuo", "", emptyList(), true, setOf("g_k")).any { it.text == "中国" })
        for (query in listOf("nihao", "zhongguo", "shurufa", "zhongkuo")) {
            val started = SystemClock.elapsedRealtime()
            val candidates = engine.decodePinyin(query, "", emptyList(), true, io.secure.keyboard.data.SettingsStore.fuzzyPairIds.toSet())
            assertTrue(candidates.isNotEmpty())
            instrumentation.sendStatus(2, Bundle().apply { putLong("all_fuzzy_${query}_ms", SystemClock.elapsedRealtime() - started) })
        }
    }

    fun testWarmIndexedQueryLatency() {
        val engine = OfflineEngine(instrumentation.targetContext).also { it.load() }
        val cases = linkedMapOf<String, () -> Unit>(
            "wubi_exact" to { check(engine.exactWubi("wq").isNotEmpty()) },
            "wubi_prefix" to { check(engine.candidates("a",false,mode=ChineseInputMode.WUBI).isNotEmpty()) },
            "pinyin" to { check(engine.decodePinyin("nihao","",emptyList(),false,emptySet()).isNotEmpty()) },
            "english" to { check(engine.completeEnglish("s").isNotEmpty()) }
        )
        for ((name, query) in cases) {
            repeat(5) { query() }
            val samples = (0 until 30).map {
                val start=System.nanoTime(); query(); (System.nanoTime()-start)/1_000_000.0
            }.sorted()
            instrumentation.sendStatus(0, Bundle().apply {
                putString("query_$name", "median_ms=${samples[15]},p95_ms=${samples[28]}")
            })
        }
    }

    fun testPackagedEngineLoadsAndFindsChineseCandidates() {
        val start = SystemClock.elapsedRealtime()
        val engine = OfflineEngine(instrumentation.targetContext)
        engine.load()
        instrumentation.sendStatus(
            2,
            Bundle().apply {
                putLong("dictionary_cold_load_ms", SystemClock.elapsedRealtime() - start)
            },
        )
        assertTrue("Packaged engine must finish initialization", engine.isLoaded)
        assertEquals("你好", engine.candidates("nihao", false).first().text)
        assertEquals("你", engine.exactWubi("wq", 1).first().text)
        assertTrue(engine.candidates("zongguo", true).any { it.text == "中国" })
        assertTrue(
            engine.candidates("zongguo", true, fuzzyPairs = setOf("zh_z")).any { it.text == "中国" }
        )
        assertFalse(
            engine.candidates("zongguo", true, fuzzyPairs = emptySet()).any { it.text == "中国" }
        )
        assertEquals("computer", engine.completeEnglish("compu").first().text)
        assertTrue(engine.completeEnglish("w").isNotEmpty())
        assertTrue(engine.completeEnglish("hello ").isEmpty())
        val second = OfflineEngine(instrumentation.targetContext)
        assertTrue("A fresh wrapper reuses already-loaded process tables", second.isLoaded)
        val warmStart = SystemClock.elapsedRealtime()
        second.load()
        instrumentation.sendStatus(2, Bundle().apply { putLong("dictionary_second_instance_load_ms", SystemClock.elapsedRealtime() - warmStart) })
        assertEquals(engine.candidates("nihao", false), second.candidates("nihao", false))
        assertEquals(engine.completeEnglish("compu"), second.completeEnglish("compu"))
    }

    fun testApprovedEmojiMatchesBothInputMethodsWithoutReplacingText() {
        val engine = OfflineEngine(instrumentation.targetContext)
        engine.load()
        for (code in listOf("gany", "kaixin", "kait", "weixiao")) {
            val words = engine.candidates(code, false).map { it.text }
            val result = engine.appendEmoji(words)
            assertEquals(words.take(3), result.take(minOf(3, words.size)))
            assertEquals(words, result.filter { it in words })
            if (code == "gany" || code == "kaixin") {
                assertTrue("Expected 开心 for $code", words.take(3).contains("开心"))
                assertTrue(result.contains("😀"))
            }
        }
        assertFalse(engine.exactWubi("gany").any { it.text == "😀" })
        assertTrue(engine.appendEmoji(emptyList()).isEmpty())
        assertEquals(listOf("赞", "👍"), engine.appendEmoji(listOf("赞")))
    }

    fun testIndexedWubiIncludesCompleteLargeExactBucket() {
        val repository=io.secure.keyboard.dictionaries.DictionaryRepository(instrumentation.targetContext)
        val engine = OfflineEngine(instrumentation.targetContext)
        engine.load()
        val expected=io.secure.keyboard.dictionaries.CompiledDictionary(repository.defaultRuntime()).use { it.wubi.exact("khkh",10000) }
        val full = engine.candidates("khkh", false, mode=ChineseInputMode.WUBI)
        assertEquals(expected,full)
        assertEquals(full.size, full.map { it.text }.distinct().size)
        assertTrue(full.all { it.source == Source.WUBI && it.code == "khkh" })
        assertEquals(full.first(), engine.exactWubi("khkh", 1).single())
        assertTrue("Short prefix remains bounded", engine.candidates("kh", false).size <= 40)
    }
}
