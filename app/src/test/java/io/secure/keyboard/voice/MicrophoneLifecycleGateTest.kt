package io.secure.keyboard.voice

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class MicrophoneLifecycleGateTest {
    @Test fun stopBeforeStartPreventsMicrophoneRestart() {
        val gate = MicrophoneLifecycleGate()
        val allowed = AtomicBoolean(true)
        val started = AtomicBoolean(false)
        val stopEntered = CountDownLatch(1)
        val releaseStop = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val stop = pool.submit {
                gate.stop {
                    allowed.set(false)
                    stopEntered.countDown()
                    check(releaseStop.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(stopEntered.await(5, TimeUnit.SECONDS))
            val start = pool.submit<Boolean> {
                gate.startIfAllowed({ allowed.get() }) { started.set(true) }
            }
            releaseStop.countDown()
            stop.get(5, TimeUnit.SECONDS)
            assertFalse(start.get(5, TimeUnit.SECONDS))
            assertFalse("Nothing may restart recording after stop won the gate", started.get())
        } finally {
            releaseStop.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun stopWaitsForAnAlreadyStartingRecorderThenStopsIt() {
        val gate = MicrophoneLifecycleGate()
        val allowed = AtomicBoolean(true)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val startEntered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val stopAttempted = CountDownLatch(1)
        val stopThread = AtomicReference<Thread>()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val start = pool.submit<Boolean> {
                gate.startIfAllowed({ allowed.get() }) {
                    events.add("start")
                    startEntered.countDown()
                    check(releaseStart.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(startEntered.await(5, TimeUnit.SECONDS))
            val stop = pool.submit {
                stopThread.set(Thread.currentThread())
                stopAttempted.countDown()
                gate.stop { allowed.set(false); events.add("stop") }
            }
            assertTrue(stopAttempted.await(5, TimeUnit.SECONDS))
            assertBlockedAtGate(stopThread.get(), stop)
            releaseStart.countDown()
            assertTrue(start.get(5, TimeUnit.SECONDS))
            stop.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("start", "stop"), events)
            assertFalse(gate.startIfAllowed({ allowed.get() }) { events.add("late-start") })
            assertEquals(listOf("start", "stop"), events)
        } finally {
            releaseStart.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun permissionToStartAndRecorderStartShareOneAtomicBoundary() {
        val gate = MicrophoneLifecycleGate()
        val allowed = AtomicBoolean(true)
        val predicateEntered = CountDownLatch(1)
        val releasePredicate = CountDownLatch(1)
        val stopAttempted = CountDownLatch(1)
        val stopThread = AtomicReference<Thread>()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val pool = Executors.newFixedThreadPool(2)
        try {
            val start = pool.submit<Boolean> {
                gate.startIfAllowed({
                    predicateEntered.countDown()
                    check(releasePredicate.await(5, TimeUnit.SECONDS))
                    allowed.get()
                }) { events.add("start") }
            }
            assertTrue(predicateEntered.await(5, TimeUnit.SECONDS))
            val stop = pool.submit {
                stopThread.set(Thread.currentThread())
                stopAttempted.countDown()
                gate.stop { allowed.set(false); events.add("stop") }
            }
            assertTrue(stopAttempted.await(5, TimeUnit.SECONDS))
            assertBlockedAtGate(stopThread.get(), stop)
            releasePredicate.countDown()
            assertTrue(start.get(5, TimeUnit.SECONDS))
            stop.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("start", "stop"), events)
        } finally {
            releasePredicate.countDown()
            pool.shutdownNow()
        }
    }
    private fun assertBlockedAtGate(thread: Thread, future: Future<*>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.state != Thread.State.BLOCKED && !future.isDone &&
            System.nanoTime() < deadline) Thread.yield()
        assertEquals("Stop must be waiting on the same start/check monitor", Thread.State.BLOCKED, thread.state)
        assertFalse("Stop cannot finish before the atomic start/check boundary releases", future.isDone)
    }

}
