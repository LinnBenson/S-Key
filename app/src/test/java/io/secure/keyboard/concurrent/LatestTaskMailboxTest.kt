package io.secure.keyboard.concurrent

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class LatestTaskMailboxTest {
    private class ManualExecutor : Executor {
        val queue = ArrayDeque<Runnable>()
        var reject = false
        @Synchronized override fun execute(command: Runnable) {
            if (reject) throw RejectedExecutionException("test gate")
            queue.addLast(command)
        }
        @Synchronized fun size() = queue.size
        fun next() = synchronized(this) { queue.removeFirst() }.run()
    }

    @Test fun thousandRefreshesOwnOnePendingPayloadAndOneDispatcher() {
        val executor = ManualExecutor()
        val mailbox = LatestTaskMailbox(executor)
        val ran = mutableListOf<Int>()
        repeat(1_000) { index ->
            mailbox.submit { ran.add(index) }
            assertEquals(0, mailbox.snapshot().running)
            assertEquals(1, mailbox.snapshot().pending)
            assertEquals(1, executor.size())
        }
        executor.next()
        assertEquals(listOf(999), ran)
        assertEquals(LatestTaskMailbox.Snapshot(0, 0, false, false), mailbox.snapshot())
    }

    @Test fun activeHostQueryPlusThousandRefreshesOwnOnlyLatestPending() {
        val executor = ManualExecutor()
        val mailbox = LatestTaskMailbox(executor)
        val ran = mutableListOf<Int>()
        mailbox.submit {
            ran.add(-1)
            repeat(1_000) { index ->
                mailbox.submit { ran.add(index) }
                assertEquals(1, mailbox.snapshot().running)
                assertEquals(1, mailbox.snapshot().pending)
                assertEquals(0, executor.size())
            }
        }
        executor.next()
        assertEquals(listOf(-1), ran)
        assertEquals(1, executor.size())
        executor.next()
        assertEquals(listOf(-1, 999), ran)
    }

    @Test fun eachDerivedTurnYieldsToEveryPreviouslyQueuedRealCommand() {
        val executor = ManualExecutor()
        val mailbox = LatestTaskMailbox(executor)
        val order = mutableListOf<String>()
        mailbox.submit {
            order.add("query A")
            mailbox.submit { order.add("obsolete query") }
            mailbox.submit { order.add("query C") }
        }
        executor.execute { order.add("commit 1") }
        executor.execute { order.add("clipboard 1") }
        executor.execute { order.add("clipboard 2") }
        executor.next()
        assertEquals(4, executor.size())
        repeat(4) { executor.next() }
        assertEquals(listOf("query A", "commit 1", "clipboard 1", "clipboard 2", "query C"), order)
    }

    @Test fun clearDropsPendingWhileKeepingQueuedDispatcherReusable() {
        val executor = ManualExecutor()
        val mailbox = LatestTaskMailbox(executor)
        var canceledRan = false
        var nextRan = false
        mailbox.submit { canceledRan = true }
        mailbox.clear()
        assertEquals(0, mailbox.snapshot().pending)
        mailbox.submit { nextRan = true }
        assertEquals(1, executor.size())
        executor.next()
        assertFalse(canceledRan)
        assertTrue(nextRan)
    }

    @Test fun clearDuringActionDoesNotInterruptActionOrRunCanceledSuccessor() {
        val executor = ManualExecutor()
        val mailbox = LatestTaskMailbox(executor)
        val order = mutableListOf<String>()
        mailbox.submit {
            mailbox.submit { fail("canceled successor ran") }
            mailbox.clear()
            order.add("active finished")
        }
        executor.next()
        assertEquals(listOf("active finished"), order)
        assertEquals(0, executor.size())
        assertEquals(LatestTaskMailbox.Snapshot(0, 0, false, false), mailbox.snapshot())
    }

    @Test fun replacedAndClearedRequestsAreNotReportedAsRejection() {
        val executor = ManualExecutor()
        val mailbox = LatestTaskMailbox(executor)
        repeat(100) { mailbox.submit(onRejected = { fail("supersession is not failure") }) {} }
        mailbox.clear()
        executor.next()
        assertEquals(0, mailbox.snapshot().pending)
    }

    @Test fun initialRejectionDropsPayloadResetsSchedulingAndAllowsLaterRetry() {
        val executor = ManualExecutor().apply { reject = true }
        val mailbox = LatestTaskMailbox(executor)
        var rejected = 0
        mailbox.submit(onRejected = { rejected++ }) { fail("rejected action ran") }
        assertEquals(1, rejected)
        assertEquals(LatestTaskMailbox.Snapshot(0, 0, false, false), mailbox.snapshot())
        executor.reject = false
        var ran = false
        mailbox.submit { ran = true }
        executor.next()
        assertTrue(ran)
    }

    @Test fun continuationRejectionSettlesOnlyTheLatestPendingOwner() {
        val executor = ManualExecutor()
        val mailbox = LatestTaskMailbox(executor)
        var rejected = 0
        mailbox.submit {
            mailbox.submit(onRejected = { fail("obsolete owner notified") }) {}
            mailbox.submit(onRejected = { rejected++ }) { fail("rejected successor ran") }
            executor.reject = true
        }
        executor.next()
        assertEquals(1, rejected)
        assertEquals(LatestTaskMailbox.Snapshot(0, 0, false, false), mailbox.snapshot())
    }

    @Test fun throwingActionStillReschedulesLatestAndDoesNotStrandFlag() {
        val executor = ManualExecutor()
        val mailbox = LatestTaskMailbox(executor)
        var nextRan = false
        mailbox.submit {
            mailbox.submit { nextRan = true }
            error("synthetic failure")
        }
        try { executor.next(); fail("expected failure") } catch (_: IllegalStateException) { }
        executor.next()
        assertTrue(nextRan)
        assertFalse(mailbox.snapshot().scheduled)
    }

    @Test fun closeBeforeShutdownDropsPendingAndRejectsFutureSubmissions() {
        val executor = ManualExecutor()
        val mailbox = LatestTaskMailbox(executor)
        mailbox.submit { fail("closed payload ran") }
        mailbox.close()
        mailbox.close()
        assertEquals(LatestTaskMailbox.Snapshot(0, 0, false, true), mailbox.snapshot())
        executor.next() // An already queued dispatcher contains no payload.
        var rejected = 0
        mailbox.submit(onRejected = { rejected++ }) { fail("closed submission ran") }
        assertEquals(1, rejected)
        assertEquals(0, executor.size())
    }

    @Test fun closeDuringActionReleasesSuccessorButLetsCurrentActionFinish() {
        val executor = ManualExecutor()
        val mailbox = LatestTaskMailbox(executor)
        var finished = false
        mailbox.submit {
            mailbox.submit { fail("closed successor ran") }
            mailbox.close()
            assertEquals(LatestTaskMailbox.Snapshot(1, 0, true, true), mailbox.snapshot())
            finished = true
        }
        executor.next()
        assertTrue(finished)
        assertEquals(LatestTaskMailbox.Snapshot(0, 0, false, true), mailbox.snapshot())
    }

    @Test fun rejectionCallbackRunsOutsideMonitorAndCanResubmit() {
        val executor = ManualExecutor().apply { reject = true }
        val mailbox = LatestTaskMailbox(executor)
        val error = AtomicReference<Throwable?>()
        val callbackDone = CountDownLatch(1)
        mailbox.submit(onRejected = {
            // Another thread must be able to enter snapshot() while this callback is active.
            val other = Thread {
                try { mailbox.snapshot() } catch (t: Throwable) { error.set(t) }
                finally { callbackDone.countDown() }
            }
            other.start()
            assertTrue("rejection callback holds mailbox monitor", callbackDone.await(5, TimeUnit.SECONDS))
            other.join(5_000)
            executor.reject = false
            mailbox.submit {}
        }) { fail("rejected action ran") }
        assertNull(error.get())
        executor.next()
        assertFalse(mailbox.snapshot().scheduled)
    }

    @Test fun concurrentReplacementWhileSubmissionIsRejectedCannotStrandLatest() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val mailbox = LatestTaskMailbox(Executor {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            throw RejectedExecutionException("concurrent rejection")
        })
        val failures = AtomicReference<Throwable?>()
        val rejected = mutableListOf<Int>()
        val submitter = Thread {
            try { mailbox.submit(onRejected = { synchronized(rejected) { rejected.add(1) } }) {} }
            catch (t: Throwable) { failures.set(t) }
        }
        submitter.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            mailbox.submit(onRejected = { synchronized(rejected) { rejected.add(2) } }) {}
            assertEquals(1, mailbox.snapshot().pending)
        } finally { release.countDown(); submitter.join(5_000) }
        assertFalse(submitter.isAlive)
        assertNull(failures.get())
        assertEquals(listOf(2), rejected)
        assertEquals(LatestTaskMailbox.Snapshot(0, 0, false, false), mailbox.snapshot())
    }
}
