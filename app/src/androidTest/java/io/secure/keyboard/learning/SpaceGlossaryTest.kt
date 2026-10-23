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
        ui { owner = SpaceGlossary(main, backend, { shown.add(it); if (it == "new") fresh.countDown() }); owner.query("old") }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            ui { owner.query("new") }
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
            owner.query("old")
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            ui {
                owner.query("queued")
                owner.close(); owner.close(); owner.query("after close")
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
            owner.query("word")
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
            owner.query("first")
        }
        try {
            assertTrue(shown.await(5, TimeUnit.SECONDS))
            SystemClock.sleep(250)
            ui { owner.query("second") }
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
            ui { owner = SpaceGlossary(main, SpaceGlossary.SqliteBackend(instrumentation.targetContext), { if (it == "apple") ready.countDown() }); owner.query("苹果") }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            ui { owner.query("银行"); owner.close() }
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
