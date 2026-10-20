package io.secure.keyboard.voice

import org.junit.Assert.*
import org.junit.Test

class LatestVoiceLevelTest {
    @Test fun slowUiRetainsOnlyNewestOfFiftyThousandUpdates() {
        val queue = mutableListOf<Runnable>()
        val received = mutableListOf<Pair<Long, Float>>()
        val levels = LatestVoiceLevel({ queue.add(it) }, { queue.remove(it) }) { id, value -> received.add(id to value) }
        repeat(50_000) { levels.offer(7, it.toFloat()) }
        assertEquals(1, queue.size)
        queue.removeAt(0).run()
        assertEquals(listOf(7L to 49_999f), received)
        levels.offer(8, .5f)
        assertEquals(1, queue.size)
        queue.removeAt(0).run()
        assertEquals(8L to .5f, received.last())
    }
    @Test fun cancellationDropsQueuedPayloadAndCanStartFresh() {
        val queue = mutableListOf<Runnable>()
        val received = mutableListOf<Long>()
        val levels = LatestVoiceLevel({ queue.add(it) }, { queue.remove(it) }) { id, _ -> received.add(id) }
        levels.offer(1, 1f)
        levels.clear()
        assertTrue(queue.isEmpty())
        levels.offer(2, .5f)
        queue.removeAt(0).run()
        assertEquals(listOf(2L), received)
        levels.offer(2, .8f)
        levels.close()
        repeat(1000) { levels.offer(1, 1f) }
        assertTrue(queue.isEmpty())
    }
}
