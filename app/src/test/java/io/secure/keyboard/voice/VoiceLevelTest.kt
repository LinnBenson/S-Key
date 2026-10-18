package io.secure.keyboard.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceLevelTest {
    @Test fun littleEndianPcmMatchesShortSamples() {
        assertEquals(VoiceLevel.measure(shortArrayOf(1000, -1000), 0, 2),
            VoiceLevel.measure(byteArrayOf(0, 0, -24, 3, 24, -4), 2, 4), 0.00001f)
    }
    @Test fun silenceAndSignalStrength() {
        assertEquals(0f, VoiceLevel.measure(ShortArray(32), 0, 32), 0f)
        val quiet = VoiceLevel.measure(ShortArray(32) { 200 }, 0, 32)
        val loud = VoiceLevel.measure(ShortArray(32) { 12000 }, 0, 32)
        assertTrue(quiet > 0f && loud > quiet && loud <= 1f)
    }
    @Test fun measuresOnlyCurrentChunk() {
        val samples = shortArrayOf(32767, 32767, 0, 0)
        assertEquals(0f, VoiceLevel.measure(samples, 2, 2), 0f)
        assertEquals(VoiceLevel.measure(shortArrayOf(1000), 0, 1),
            VoiceLevel.measure(shortArrayOf(-1000), 0, 1), 0f)
    }
}
