package io.secure.keyboard.ai

/** A single queued delivery and a single latest value, cleared on cancellation. */
internal class LatestPreview(
    private val post: (Runnable) -> Unit,
    private val remove: (Runnable) -> Unit,
    private val consume: (String) -> Unit,
) {
    private var value: String? = null
    private var queued = false
    private var closed = false
    private val delivery = Runnable {
        val next = synchronized(this) {
            queued = false
            if (closed) null else value.also { value = null }
        }
        if (next != null) consume(next)
    }
    @Synchronized fun offer(text: String) {
        if (closed) return
        value = text
        if (!queued) { queued = true; post(delivery) }
    }
    @Synchronized fun close() {
        closed = true; value = null; queued = false; remove(delivery)
    }
}
