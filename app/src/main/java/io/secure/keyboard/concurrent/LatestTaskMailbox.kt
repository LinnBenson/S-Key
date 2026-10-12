package io.secure.keyboard.concurrent

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * Latest-only derived work on an existing asynchronous, serial executor. This is not a command
 * queue: keys, commits, clipboard events and finalized voice segments must keep their own ordering.
 *
 * Owns at most one executing action and one pending action. Replacing/clearing pending drops its
 * action and rejection callback immediately. The executor receives one payload-free dispatcher,
 * never one Runnable per submission. Each dispatcher runs just one action, then resubmits at the
 * executor's tail so unrelated queued work gets a turn. A blocked action is not interrupted and
 * cannot be worked around with another thread. Do not supply a direct/CallerRuns executor.
 */
internal class LatestTaskMailbox(private val executor: Executor) {
    private class Task(val action: () -> Unit, val onRejected: () -> Unit)
    internal data class Snapshot(val running: Int, val pending: Int, val scheduled: Boolean, val closed: Boolean)

    private val monitor = Any()
    private var pending: Task? = null
    private var running = false
    // True while open and a dispatcher is queued/executing/being submitted, or an action runs.
    // close() can leave one harmless payload-free Runnable in the executor while this is false.
    private var scheduled = false
    private var closed = false
    private val dispatcher = Runnable { runOne() }

    /** Replaced/canceled requests are intentionally silent; rejection is reported for the owner. */
    fun submit(onRejected: () -> Unit = {}, action: () -> Unit) {
        var reject = false
        val needsDispatch = synchronized(monitor) {
            if (closed) {
                reject = true
                false
            } else {
                pending = Task(action, onRejected)
                if (scheduled) false else {
                    scheduled = true
                    true
                }
            }
        }
        // No user action/callback runs under the monitor, including a closed-mailbox rejection.
        if (reject) onRejected() else if (needsDispatch) dispatch()
    }

    /** Invalidates only pending derived work. A caller's generation token revokes in-flight work. */
    fun clear() = synchronized(monitor) { pending = null }

    /** Call before shutting down the executor so shutdownNow cannot retain a stranded payload. */
    fun close() = synchronized(monitor) {
        closed = true
        pending = null
        if (!running) scheduled = false
    }

    /** Counts only; contains no request, editor, text, context or view references. */
    fun snapshot(): Snapshot = synchronized(monitor) {
        Snapshot(if (running) 1 else 0, if (pending == null) 0 else 1, scheduled, closed)
    }

    private fun dispatch() {
        try {
            executor.execute(dispatcher)
        } catch (_: RejectedExecutionException) {
            val rejected = synchronized(monitor) {
                scheduled = false
                pending.also { pending = null }
            }
            // Includes continuation rejection: release the latest pending owner and let its
            // existing failure path settle confirmation/voice state rather than hanging it.
            rejected?.onRejected?.invoke()
        }
    }

    private fun runOne() {
        val task = synchronized(monitor) {
            pending.also {
                pending = null
                running = it != null
            }
        }
        try {
            task?.action?.invoke()
        } finally {
            val again = synchronized(monitor) {
                running = false
                if (!closed && pending != null) true else {
                    scheduled = false
                    false
                }
            }
            if (again) dispatch()
        }
    }
}
