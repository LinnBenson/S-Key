package io.secure.keyboard.voice

import org.junit.Assert.*
import org.junit.Test

class RecordingFocusLeaseTest {
    @Test fun stopReleasesOnceAndLateCallbackCannotAffectNextRecording() {
        var releases = 0
        lateinit var lateLoss: () -> Unit
        val first = RecordingFocusLease({ lateLoss = it; true }, { releases++ }, { true })
        first.acquire()
        first.close(); first.close()
        val next = RecordingFocusLease({ true }, { releases++ }, { false })
        next.acquire()
        lateLoss()
        next.checkAvailable()
        assertEquals(1, releases)
        next.close()
        assertEquals(2, releases)
    }
    @Test fun deniedFocusWithMediaDoesNotStartAndAbandonsRequest() {
        var releases = 0
        val lease = RecordingFocusLease({ false }, { releases++ }, { true })
        try { lease.acquire(); fail("Must ask user to pause") } catch (_: RecordingFocusException) {}
        lease.close()
        assertEquals(1, releases)
    }
    @Test fun deniedFocusAllowsQuietRecordingButDetectsLaterPlayback() {
        var playing = false
        val lease = RecordingFocusLease({ false }, {}, { playing })
        lease.acquire(); lease.checkAvailable()
        playing = true
        try { lease.checkAvailable(); fail("Must stop on interference") } catch (_: RecordingFocusException) {}
        lease.close()
    }
    @Test fun focusLossRemainsInterruptedEvenIfPlaybackStops() {
        lateinit var loss: () -> Unit
        val lease = RecordingFocusLease({ loss = it; true }, {}, { false })
        lease.acquire(); loss()
        try { lease.checkAvailable(); fail("Must stop on focus loss") } catch (_: RecordingFocusException) {}
        lease.close()
    }
    @Test fun requestFailureStillCleansUpAndAbandonFailureIsContained() {
        var releases = 0
        val lease = RecordingFocusLease({ throw IllegalStateException("fixture") },
            { releases++; throw IllegalStateException("release fixture") }, { false })
        try { lease.acquire(); fail("Request must fail") } catch (_: IllegalStateException) {}
        lease.close()
        assertEquals(1, releases)
    }
}
