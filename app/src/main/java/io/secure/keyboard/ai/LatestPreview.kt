package io.secure.keyboard.ai

/** Accumulate deltas without copying the prefix; snapshot only on timed delivery. */
internal class LatestPreview(
    private val post: (Runnable) -> Unit,
    private val remove: (Runnable) -> Unit,
    private val consume: (String) -> Unit,
) {
    private val value = StringBuilder()
    private var queued = false
    private var closed = false
    private val delivery = Runnable {
        val next = synchronized(this) {
            queued = false
            if (closed) null else value.toString()
        }
        if (next != null) consume(next)
    }
    @Synchronized fun offer(text: String) {
        if (closed) return
        value.append(text)
        if (!queued) { queued = true; post(delivery) }
    }
    @Synchronized fun close() {
        closed = true; value.setLength(0); queued = false; remove(delivery)
    }
}
