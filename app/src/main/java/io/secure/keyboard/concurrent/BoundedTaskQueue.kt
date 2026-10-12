package io.secure.keyboard.concurrent

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** FIFO commands bounded by both count and retained text, including the executing command.
 * Overflow rejects the new command without discarding or reordering accepted copies.
 */
internal class BoundedTaskQueue(
    private val executor: Executor,
    private val maxTasks: Int,
    private val maxWeight: Int,
) {
    private data class Task(val weight: Int, val action: () -> Unit)
    private val lock = Any()
    private val pending = ArrayDeque<Task>()
    private var count = 0
    private var weight = 0
    private var closed = false
    private var scheduled = false

    init { require(maxTasks > 0 && maxWeight > 0) }

    fun submit(taskWeight: Int, action: () -> Unit): Boolean = synchronized(lock) {
        require(taskWeight >= 0)
        if (closed || count >= maxTasks || taskWeight > maxWeight - weight) return false
        pending.addLast(Task(taskWeight, action))
        count++
        weight += taskWeight
        if (!scheduled && !schedule()) return false
        true
    }

    // Called under lock. Rejection releases every pending payload, preserving running accounting.
    private fun schedule(): Boolean {
        scheduled = true
        try { executor.execute { runOne() } }
        catch (_: RejectedExecutionException) {
            pending.forEach { count--; weight -= it.weight }
            pending.clear()
            scheduled = false
            return false
        }
        return true
    }

    private fun runOne() {
        val task = synchronized(lock) {
            if (pending.isEmpty()) { scheduled = false; return }
            pending.removeFirst()
        }
        try { task.action() }
        finally {
            synchronized(lock) {
                count--
                weight -= task.weight
                scheduled = false
                if (!closed && pending.isNotEmpty()) schedule()
            }
        }
    }

    fun close() = synchronized(lock) {
        closed = true
        pending.forEach { count--; weight -= it.weight }
        pending.clear()
    }
}
