package io.secure.keyboard.voice

import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.*
import org.junit.Test

class ModelImportCoordinatorTest {
    private class Harness {
        val workers = ArrayDeque<() -> Unit>()
        val main = ArrayDeque<() -> Unit>()
        var onMain = true
        var reject = false
        var starts = 0
        val owner = ModelImportCoordinator(
            execute = {
                if (reject) throw RejectedExecutionException("worker unavailable")
                starts++
                workers.addLast(it)
            },
            postToMain = { main.addLast(it) },
            requireMainThread = { check(onMain) { "main thread required" } },
        )

        fun runWorker() {
            onMain = false
            try { workers.removeFirst().invoke() } finally { onMain = true }
        }
        fun deliver() = main.removeFirst().invoke()
        fun observer(): Any? = ModelImportCoordinator::class.java.getDeclaredField("observer")
            .apply { isAccessible = true }.get(owner)
    }

    @Test fun oneWorkAndOneCompletionAreDeliveredOnMain() {
        val h = Harness()
        val seen = mutableListOf<ModelImportCoordinator.State>()
        var work = 0
        h.owner.attach { assertTrue(h.onMain); seen.add(it) }
        assertTrue(h.owner.start { assertFalse(h.onMain); work++ })
        assertEquals(listOf(ModelImportCoordinator.State.Idle, ModelImportCoordinator.State.Running), seen)
        assertEquals(0, work)
        h.runWorker()
        assertEquals(1, work)
        assertSame(ModelImportCoordinator.State.Running, h.owner.state)
        assertEquals(1, h.main.size)
        h.deliver()
        assertEquals(listOf(ModelImportCoordinator.State.Idle, ModelImportCoordinator.State.Running,
            ModelImportCoordinator.State.Success), seen)
        assertTrue(h.workers.isEmpty())
        assertTrue(h.main.isEmpty())
    }

    @Test fun tenRotationsAndBackReopenKeepExactlyOnePendingTransfer() {
        val h = Harness()
        val oldScreens = mutableListOf<MutableList<ModelImportCoordinator.State>>()
        var visible = mutableListOf<ModelImportCoordinator.State>()
        val firstScreen = visible
        var callback: (ModelImportCoordinator.State) -> Unit = { firstScreen.add(it) }
        h.owner.attach(callback)
        assertTrue(h.owner.start {})
        repeat(10) {
            val previous = visible
            oldScreens.add(previous)
            h.owner.detach(callback)
            assertNull("Stopped screen must not be held by the owner", h.observer())
            visible = mutableListOf()
            val newScreen = visible
            callback = { newScreen.add(it) }
            h.owner.attach(callback)
            assertEquals(listOf(ModelImportCoordinator.State.Running), visible)
            assertFalse(h.owner.start { fail("A duplicate must never execute") })
        }
        h.owner.detach(callback) // Back/permanent finish.
        assertNull(h.observer())
        val reopened = mutableListOf<ModelImportCoordinator.State>()
        h.owner.attach { reopened.add(it) }
        assertEquals(listOf(ModelImportCoordinator.State.Running), reopened)
        assertFalse(h.owner.start { fail("Reopening cannot enqueue an import") })
        assertEquals(1, h.starts)
        assertEquals(1, h.workers.size)
        val countsBefore = oldScreens.map { it.size }
        h.runWorker()
        // The accepted transfer remains pending until main-thread completion is applied.
        assertFalse(h.owner.start { fail("Queued completion still owns the flight") })
        h.deliver()
        assertEquals(listOf(ModelImportCoordinator.State.Running, ModelImportCoordinator.State.Success), reopened)
        assertEquals("Old observers receive no completion", countsBefore, oldScreens.map { it.size })
    }

    @Test fun completionQueuedBeforeStopDoesNotCaptureOrNotifyDetachedObserver() {
        val h = Harness()
        val seen = mutableListOf<ModelImportCoordinator.State>()
        val callback: (ModelImportCoordinator.State) -> Unit = { seen.add(it) }
        h.owner.attach(callback)
        h.owner.start {}
        h.runWorker() // Completion is already in the main queue.
        h.owner.detach(callback)
        assertNull(h.observer())
        h.deliver()
        assertEquals(listOf(ModelImportCoordinator.State.Idle, ModelImportCoordinator.State.Running), seen)
        val reopened = mutableListOf<ModelImportCoordinator.State>()
        h.owner.attach { reopened.add(it) }
        assertEquals(listOf(ModelImportCoordinator.State.Success), reopened)
    }

    @Test fun lateDestroyCannotDetachNewStartedScreen() {
        val h = Harness()
        val first: (ModelImportCoordinator.State) -> Unit = {}
        val second: (ModelImportCoordinator.State) -> Unit = {}
        h.owner.attach(first)
        h.owner.attach(second)
        h.owner.detach(first)
        assertSame(second, h.observer())
        h.owner.detach(second)
        h.owner.detach(second) // onStop + onDestroy are idempotent.
        assertNull(h.observer())
    }

    @Test fun failureSurvivesBackReopenAndRetryReplacesItWithoutHistory() {
        val h = Harness()
        val before = mutableListOf<ModelImportCoordinator.State>()
        val callback: (ModelImportCoordinator.State) -> Unit = { before.add(it) }
        h.owner.attach(callback)
        assertTrue(h.owner.start { throw IOException("controlled import error") })
        h.owner.detach(callback)
        h.runWorker()
        h.deliver()
        assertNull(h.observer())
        assertEquals(ModelImportCoordinator.State.Failure("controlled import error"), h.owner.state)
        val reopened = mutableListOf<ModelImportCoordinator.State>()
        h.owner.attach { reopened.add(it) }
        assertEquals(listOf(ModelImportCoordinator.State.Failure("controlled import error")), reopened)
        assertTrue(h.owner.start {})
        assertSame(ModelImportCoordinator.State.Running, h.owner.state)
        h.runWorker()
        h.deliver()
        assertSame(ModelImportCoordinator.State.Success, h.owner.state)
        assertEquals(2, h.starts)
        assertEquals(listOf(ModelImportCoordinator.State.Idle, ModelImportCoordinator.State.Running), before)
    }

    @Test fun failureKeepsOnlyMessageAndMissingMessageUsesFallback() {
        val h = Harness()
        h.owner.start { throw IOException() }
        h.runWorker()
        h.deliver()
        val state = h.owner.state
        assertEquals(ModelImportCoordinator.State.Failure("模型导入失败，请重试"), state)
        assertEquals("No Throwable, provider or result history is retained", listOf(String::class.java),
            state.javaClass.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.type })
    }

    @Test fun failedSubmissionReleasesFlightForRetry() {
        val h = Harness()
        h.reject = true
        assertTrue(h.owner.start {})
        assertEquals(ModelImportCoordinator.State.Failure("worker unavailable"), h.owner.state)
        assertTrue(h.workers.isEmpty())
        h.reject = false
        assertTrue(h.owner.start {})
        h.runWorker()
        h.deliver()
        assertSame(ModelImportCoordinator.State.Success, h.owner.state)
    }

    @Test fun pickerResultBeforeStartHasNoObserverAndAttachesCurrentProgress() {
        val h = Harness()
        assertTrue(h.owner.start {})
        assertNull(h.observer())
        val seen = mutableListOf<ModelImportCoordinator.State>()
        h.owner.attach { seen.add(it) }
        assertEquals(listOf(ModelImportCoordinator.State.Running), seen)
    }

    @Test fun stateAndObserverOperationsRejectWrongThread() {
        val h = Harness()
        h.onMain = false
        val callback: (ModelImportCoordinator.State) -> Unit = {}
        for (operation in listOf<() -> Unit>(
            { h.owner.state; Unit }, { h.owner.attach(callback) },
            { h.owner.detach(callback) }, { h.owner.start {}; Unit },
        )) {
            try { operation(); fail("Expected main-thread check") } catch (_: IllegalStateException) { }
        }
        assertEquals(0, h.starts)
        assertNull(h.observer())
    }
}
