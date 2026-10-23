package io.secure.keyboard.learning

import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.test.InstrumentationTestCase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Suppress("DEPRECATION")
class SpaceGlossaryTest : InstrumentationTestCase() {
    private val main = Handler(Looper.getMainLooper())
    private fun ui(action: () -> Unit) = instrumentation.runOnMainSync(action)

    fun testCursorSuffixOrderBoundariesAndSplitWords() {
        val examples = listOf(
            Triple("好我在的", "在的", listOf("好我在的", "我在的", "在的")),
            Triple("好我在的", "我在的", listOf("好我在的", "我在的")),
            Triple("好我在的", "好我在的", listOf("好我在的")),
            Triple("你好苹果", "苹果", listOf("你好苹果", "好苹果", "苹果")),
            Triple("，苹果", "苹果", listOf("苹果"))
        )
        for ((tail, hit, expected) in examples) {
            val lookedUp = mutableListOf<String>()
            val shown = CountDownLatch(1)
            lateinit var owner: SpaceGlossary
            ui {
                owner = SpaceGlossary(main, object : SpaceGlossary.Backend {
                    override fun lookup(word: String, signal: CancellationSignal): String? {
                        lookedUp.add(word)
                        return if (word == hit) "translation" else null
                    }
                    override fun close() {}
                }, { if (it == "translation") shown.countDown() })
                owner.queryBeforeCursor { tail }
            }
            try {
                assertTrue(shown.await(5, TimeUnit.SECONDS))
                assertEquals(expected, lookedUp)
            } finally { ui { owner.close() }; assertTrue(owner.awaitReleased(5000)) }
        }
    }

    fun testMissAndPunctuationNeverKeepPreviousTranslation() {
        for ((tail, expected) in listOf(
            "好我在的" to listOf("好我在的", "我在的", "在的"),
            "我，苹果" to listOf("苹果"), "你 好" to emptyList(), "字" to emptyList()
        )) {
            val done = CountDownLatch(2) // Immediate clear and completed empty result.
            val words = mutableListOf<String>()
            lateinit var owner: SpaceGlossary
            ui {
                owner = SpaceGlossary(main, object : SpaceGlossary.Backend {
                    override fun lookup(word: String, signal: CancellationSignal): String? { words.add(word); return null }
                    override fun close() {}
                }, { done.countDown() })
                owner.queryBeforeCursor { tail }
            }
            try {
                assertTrue(done.await(5, TimeUnit.SECONDS))
                assertEquals(expected, words)
                ui { assertNull(owner.text) }
            } finally { ui { owner.close() }; assertTrue(owner.awaitReleased(5000)) }
        }
    }

    fun testCommittedWordWinsAndFallbackPrefersLongest() {
        for (exactHit in listOf(true, false)) {
            val done = CountDownLatch(1)
            val calls = mutableListOf<String>()
            var reads = 0
            lateinit var owner: SpaceGlossary
            ui {
                owner = SpaceGlossary(main, object : SpaceGlossary.Backend {
                    override fun lookup(word: String, signal: CancellationSignal): String? {
                        calls.add(word)
                        return if (word == "智能" && exactHit) "intelligence"
                            else if (word == "人工智能" && !exactHit) "AI" else null
                    }
                    override fun close() {}
                }, { if (it != null) done.countDown() })
                owner.queryBeforeCursor("智能") { reads++; "人工智能" }
            }
            try {
                assertTrue(done.await(5, TimeUnit.SECONDS))
                assertEquals(if (exactHit) listOf("智能") else listOf("智能", "人工智能"), calls)
                assertEquals(if (exactHit) 0 else 1, reads)
            } finally { ui { owner.close() }; assertTrue(owner.awaitReleased(5000)) }
        }
    }

    fun testLateResultCanceledAndCloseReleasesBackend() {
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val fresh = CountDownLatch(1)
        var oldSignal: CancellationSignal? = null
        var closed = false
        val shown = mutableListOf<String?>()
        val backend = object : SpaceGlossary.Backend {
            override fun lookup(word: String, signal: CancellationSignal): String? {
                if (word == "old") {
                    oldSignal = signal; entered.countDown()
                    while (unblock.count > 0) try { unblock.await() } catch (_: InterruptedException) {}
                }
                return word
            }
            override fun close() { closed = true }
        }
        lateinit var owner: SpaceGlossary
        ui { owner = SpaceGlossary(main, backend, { shown.add(it); if (it == "new") fresh.countDown() }); owner.query { backend, signal -> backend.lookup("old", signal) } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            ui { owner.query { backend, signal -> backend.lookup("new", signal) } }
            assertTrue(oldSignal!!.isCanceled)
            unblock.countDown()
            assertTrue(fresh.await(5, TimeUnit.SECONDS))
            ui { assertFalse(shown.contains("old")); assertEquals("new", owner.text); owner.close() }
            assertTrue(owner.awaitReleased(5000)); assertTrue(closed)
        } finally { unblock.countDown(); ui { owner.close() } }
    }

    fun testCloseDuringBlockedLookupDropsQueuedWorkAndCallbacks() {
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val closes = java.util.concurrent.atomic.AtomicInteger()
        var cancellation: CancellationSignal? = null
        val shown = mutableListOf<String?>()
        lateinit var owner: SpaceGlossary
        ui {
            owner = SpaceGlossary(main, object : SpaceGlossary.Backend {
                override fun lookup(word: String, signal: CancellationSignal): String {
                    calls.incrementAndGet(); cancellation = signal; entered.countDown()
                    while (unblock.count > 0) try { unblock.await() } catch (_: InterruptedException) {}
                    return word // Deliberately ignore cancellation to test the delivery guard.
                }
                override fun close() { closes.incrementAndGet() }
            }, { shown.add(it) })
            owner.query { backend, signal -> backend.lookup("old", signal) }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            ui {
                owner.query { backend, signal -> backend.lookup("queued", signal) }
                owner.close(); owner.close(); owner.query { backend, signal -> backend.lookup("after close", signal) }
                assertNull(owner.text)
            }
            assertTrue(cancellation!!.isCanceled)
            unblock.countDown()
            assertTrue(owner.awaitReleased(5000))
            instrumentation.waitForIdleSync()
            ui { assertTrue(shown.all { it == null }) }
            assertEquals(1, calls.get()); assertEquals(1, closes.get())
        } finally { unblock.countDown(); ui { owner.close() } }
    }

    fun testDisplayCanCloseWithoutLeavingExpiryOrWorker() {
        val closed = CountDownLatch(1)
        lateinit var owner: SpaceGlossary
        var delivered = 0
        ui {
            owner = SpaceGlossary(main, object : SpaceGlossary.Backend {
                override fun lookup(word: String, signal: CancellationSignal) = word
                override fun close() {}
            }, { if (it != null) { delivered++; owner.close(); closed.countDown() } }, durationMs = 50)
            owner.query { backend, signal -> backend.lookup("word", signal) }
        }
        try {
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            assertTrue(owner.awaitReleased(5000))
            SystemClock.sleep(100)
            ui { assertNull(owner.text); assertEquals(1, delivered) }
        } finally { ui { owner.close() } }
    }

    fun testExpiryStartsOnDisplayAndOldTimerCannotEraseNewWord() {
        val shown = CountDownLatch(1)
        lateinit var owner: SpaceGlossary
        ui {
            owner = SpaceGlossary(main, object : SpaceGlossary.Backend {
                override fun lookup(word: String, signal: CancellationSignal) = word
                override fun close() {}
            }, { if (it != null) shown.countDown() }, durationMs = 400)
            owner.query { backend, signal -> backend.lookup("first", signal) }
        }
        try {
            assertTrue(shown.await(5, TimeUnit.SECONDS))
            SystemClock.sleep(250)
            ui { owner.query { backend, signal -> backend.lookup("second", signal) } }
            SystemClock.sleep(220)
            ui { assertEquals("second", owner.text) }
            SystemClock.sleep(300)
            ui { assertNull(owner.text) }
        } finally { ui { owner.close() }; assertTrue(owner.awaitReleased(5000)) }
    }

    fun testCloseSuppressesInFlightDeliveryAndRepeatedDatabaseOpen() {
        SpaceGlossary.SqliteBackend(instrumentation.targetContext).use { assertEquals("apple", it.lookup("苹果", CancellationSignal())) }
        fun openDescriptors() = java.io.File("/proc/self/fd").listFiles().orEmpty().count {
            runCatching { android.system.Os.readlink(it.path).contains("/space-glossary/") }.getOrDefault(false)
        }
        fun workers() = Thread.getAllStackTraces().keys.count { it.name == "skey-space-gloss" && it.isAlive }
        val beforeDescriptors = openDescriptors()
        val beforeWorkers = workers()
        repeat(20) {
            val ready = CountDownLatch(1)
            lateinit var owner: SpaceGlossary
            ui { owner = SpaceGlossary(main, SpaceGlossary.SqliteBackend(instrumentation.targetContext), { if (it == "apple") ready.countDown() }); owner.query { backend, signal -> backend.lookup("苹果", signal) } }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            ui { owner.query { backend, signal -> backend.lookup("银行", signal) }; owner.close() }
            assertTrue(owner.awaitReleased(5000))
            ui { assertNull(owner.text) }
        }
        assertEquals("Closed SQLite file descriptors", beforeDescriptors, openDescriptors())
        assertEquals("Terminated glossary workers", beforeWorkers, workers())
        SpaceGlossary.SqliteBackend(instrumentation.targetContext).use { backend ->
            assertNull(backend.lookup("行", CancellationSignal()))
            assertNull(backend.lookup("不存在的学习测试词", CancellationSignal()))
            assertEquals("apple", backend.lookup("蘋果", CancellationSignal()))
            val canceled = CancellationSignal().apply { cancel() }
            try { backend.lookup("苹果", canceled); fail("Canceled query must stop") } catch (_: android.os.OperationCanceledException) {}
            val begin = SystemClock.elapsedRealtimeNanos()
            repeat(100) { backend.lookup("苹果", CancellationSignal()) }
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("audit", "100 warm lookups ns=${SystemClock.elapsedRealtimeNanos()-begin}") })
        }
    }
}
