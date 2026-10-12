package io.secure.keyboard.concurrent

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.*
import org.junit.Test

class BoundedTaskQueueTest {
    private class Manual : Executor {
        val tasks = ArrayDeque<Runnable>()
        var reject = false
        override fun execute(command: Runnable) {
            if (reject) throw RejectedExecutionException()
            tasks.addLast(command)
        }
        fun next() = tasks.removeFirst().run()
    }
    @Test fun burstStaysBoundedAndPreservesAcceptedFifo() {
        val executor = Manual()
        val queue = BoundedTaskQueue(executor, 3, 10)
        val seen = mutableListOf<Int>()
        repeat(1000) { i -> assertEquals(i < 3, queue.submit(3) { seen.add(i) }) }
        assertEquals(1, executor.tasks.size)
        while (executor.tasks.isNotEmpty()) executor.next()
        assertEquals(listOf(0, 1, 2), seen)
        assertTrue(queue.submit(10) { seen.add(1001) })
        assertFalse(queue.submit(1) {})
        executor.next()
        assertEquals(1001, seen.last())
    }
    @Test fun executingPayloadCountsAgainstBudgetAndYields() {
        val executor = Manual()
        val queue = BoundedTaskQueue(executor, 2, 10)
        val seen = mutableListOf<Int>()
        queue.submit(6) {
            assertFalse(queue.submit(5) {})
            assertTrue(queue.submit(4) { seen.add(2) })
            assertFalse(queue.submit(0) {})
        }
        executor.execute { seen.add(1) }
        executor.next(); executor.next(); executor.next()
        assertEquals(listOf(1, 2), seen)
    }
    @Test fun closeDropsPendingWorkAndRejectsFurtherCopies() {
        val executor = Manual()
        val queue = BoundedTaskQueue(executor, 3, 10)
        queue.submit(1) { queue.close() }
        queue.submit(1) { fail("Closed payload executed") }
        executor.next()
        assertFalse(queue.submit(1) {})
        assertTrue(executor.tasks.isEmpty())
    }
    @Test fun rejectionAndThrowingTaskDoNotStrandPayloads() {
        val executor = Manual()
        val queue = BoundedTaskQueue(executor, 3, 10)
        executor.reject = true
        assertFalse(queue.submit(10) {})
        executor.reject = false
        assertTrue(queue.submit(10) { error("fixture") })
        try { executor.next(); fail() } catch (_: IllegalStateException) {}
        assertTrue(queue.submit(10) {})
        executor.next()
    }
}
