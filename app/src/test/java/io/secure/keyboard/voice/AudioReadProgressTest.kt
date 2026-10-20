package io.secure.keyboard.voice

import org.junit.Assert.*
import org.junit.Test

class AudioReadProgressTest {
    @Test fun failsWhenCaptureNeverReturnsData() {
        val progress = AudioReadProgress(100)
        assertTrue(progress.accept(0, 2099))
        assertFalse(progress.accept(0, 2100))
    }

    @Test fun receivedSilentPcmStillCountsAsProgress() {
        val progress = AudioReadProgress(0)
        for (time in 100L..60_000L step 100L) {
            assertTrue(progress.accept(3200, time))
        }
    }

    @Test fun detectsStallAfterPreviouslyWorkingCapture() {
        val progress = AudioReadProgress(0)
        assertTrue(progress.accept(3200, 1000))
        assertTrue(progress.accept(0, 2999))
        assertFalse(progress.accept(0, 3000))
    }

    @Test fun rejectsReadErrors() {
        assertFalse(AudioReadProgress(0).accept(-3, 10))
    }
}
