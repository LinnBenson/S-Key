package io.secure.keyboard.engine

import android.os.Bundle
import android.os.SystemClock
import android.test.InstrumentationTestCase

/** Exercises packaged dictionaries on Android, surfacing initialization errors directly. */
@Suppress("DEPRECATION")
class DictionaryEngineDeviceTest : InstrumentationTestCase() {
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

    fun testMappedWubiIncludesCompleteLargeExactBucket() {
        val assets = instrumentation.targetContext.assets
        // openFd fails for compressed assets, detecting accidental packaging regressions.
        assets.openFd("dictionaries/compact-wubi.skwb").use { descriptor ->
            assertTrue("Compact table must be a directly mappable APK asset", descriptor.length > 64)
        }
        val engine = OfflineEngine(instrumentation.targetContext)
        engine.load()
        val full = engine.candidates("khkh", false)
        assertEquals("All exact entries must remain reachable beyond the first 40", 472, full.size)
        assertEquals(full.size, full.map { it.text }.distinct().size)
        assertTrue(full.all { it.source == Source.WUBI && it.code == "khkh" })
        assertEquals(full.first(), engine.exactWubi("khkh", 1).single())
        assertTrue("Short prefix remains bounded", engine.candidates("kh", false).size <= 40)
    }
}
