package io.secure.keyboard.ai
import org.junit.Test
import org.junit.Assert.*
class LatestPreviewTest {
    @Test fun burstKeepsOnlyLatestAndCancelDropsQueuedPrivateText() {
        val queue = mutableListOf<Runnable>(); val received = mutableListOf<String>()
        val delivery = LatestPreview({ queue.add(it) }, { queue.remove(it) }, { received.add(it) })
        repeat(10000) { delivery.offer("synthetic-$it") }
        assertEquals(1, queue.size)
        queue.removeAt(0).run(); assertEquals(listOf("synthetic-9999"), received)
        delivery.offer("private-synthetic"); delivery.close()
        assertTrue(queue.isEmpty()); delivery.offer("late"); assertTrue(queue.isEmpty())
    }
}
