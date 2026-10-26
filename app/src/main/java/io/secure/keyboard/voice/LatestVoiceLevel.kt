package io.secure.keyboard.voice

/** One pending UI delivery, regardless of capture rate or a stalled main thread. */
internal class LatestVoiceLevel(
    private val post: (Runnable) -> Unit,
    private val remove: (Runnable) -> Unit,
    private val consume: (Long, Float) -> Unit,
) {
    private data class Sample(val generation: Long, val level: Float)
    private var latest: Sample? = null
    private var queued = false
    private var closed = false
    private val delivery = Runnable {
        val sample = synchronized(this) {
            queued = false
            latest.also { latest = null }
        }
        sample?.let { consume(it.generation, it.level) }
    }

    @Synchronized fun offer(generation: Long, level: Float) {
        if (closed) return
        latest = Sample(generation, level)
        if (!queued) { queued = true; post(delivery) }
    }

    @Synchronized fun clear() {
        latest = null
        queued = false
        remove(delivery)
    }

    @Synchronized fun close() { closed = true; clear() }
}
