package io.secure.keyboard.voice

import org.junit.Assert.*
import org.junit.Test

class CaptureSignalHealthTest {
    @Test fun silenceIsNotCaptureFailure() {
        val health = CaptureSignalHealth()
        repeat(600) { assertTrue(health.accept(ShortArray(1600), 0, 1600)) }
        assertFalse(health.hasVariation)
        assertTrue(health.accept(shortArrayOf(100, -100), 0, 2))
        assertTrue(health.hasVariation)
    }
    @Test fun constantDcAcrossReadsIsRejected() {
        val health = CaptureSignalHealth()
        repeat(10) { assertTrue(health.accept(ShortArray(1600) { 1000 }, 0, 1600)) }
        assertFalse(health.accept(shortArrayOf(1000), 0, 1))
    }
    @Test fun continuousClippingIsRejectedButShortPeakIsAllowed() {
        val health = CaptureSignalHealth()
        val clipped = ShortArray(1600) { if (it % 2 == 0) 32767 else -32768 }
        repeat(19) { assertTrue(health.accept(clipped, 0, clipped.size)) }
        assertFalse(health.accept(clipped, 0, clipped.size))
        val shortPeak = CaptureSignalHealth()
        assertTrue(shortPeak.accept(clipped, 0, 1600))
        assertTrue(shortPeak.accept(ShortArray(1600), 0, 1600))
    }
    @Test fun byteInputHonorsOffsetAndRejectsPartialSample() {
        val health = CaptureSignalHealth()
        assertTrue(health.accept(byteArrayOf(1, 2, -24, 3, 24, -4), 2, 4))
        assertTrue(health.hasVariation)
        assertFalse(health.accept(byteArrayOf(1), 0, 1))
    }
}
