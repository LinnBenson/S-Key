package io.secure.keyboard.data

import java.util.concurrent.Executor

/** One in-flight write and one latest pending value. A clear cannot be lost to a later edit. */
internal class LatestWriteQueue<T : Any>(
    private val executor: Executor,
    private val write: (T) -> Unit,
    private val performClear: () -> Unit,
    private val error: () -> Unit,
) {
    private data class Pending<T>(val value: T?, val clearFirst: Boolean)
    private var pending: Pending<T>? = null
    private var running = false

    @Synchronized fun offer(value: T) {
        pending = Pending(value, pending?.clearFirst == true)
        start()
    }
    @Synchronized fun clear() {
        pending = Pending(null, true)
        start()
    }
    private fun start() {
        if (running) return
        running = true
        executor.execute {
            while (true) {
                val next = synchronized(this) {
                    val value = pending
                    pending = null
                    if (value == null) running = false
                    value
                } ?: break
                try {
                    if (next.clearFirst) performClear()
                    next.value?.let(write)
                } catch (_: Exception) { error() }
            }
        }
    }
}
