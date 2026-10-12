package io.secure.keyboard.voice

import org.junit.Assert.*
import org.junit.Test
import io.secure.keyboard.voice.VoiceCommitGuard.Decision.*

class VoiceCommitGuardTest {
    @Test fun finalizedSegmentCommitsAtCapturedCursorOnce() {
        val guard = VoiceCommitGuard(7, 4)
        assertEquals(COMMIT, guard.reserve(0, 2, 7, 4, 4))
        assertEquals(6, guard.expectedCursor)
        assertEquals(IGNORE, guard.reserve(0, 2, 7, 6, 6))
        assertEquals(6, guard.expectedCursor)
    }

    @Test fun identicalPhrasesWithNewIdentitiesBothCommit() {
        val guard = VoiceCommitGuard(7, 0)
        assertEquals(COMMIT, guard.reserve(0, 2, 7, 0, 0))
        assertEquals(COMMIT, guard.reserve(1, 2, 7, 2, 2))
        assertEquals(4, guard.expectedCursor)
    }

    @Test fun emptyEndpointDoesNotInsertOrRequireConsecutiveIds() {
        val guard = VoiceCommitGuard(7, 0)
        assertEquals(IGNORE, guard.reserve(0, 0, 7, 0, 0))
        assertEquals(COMMIT, guard.reserve(3, 2, 7, 0, 0))
        assertEquals(IGNORE, guard.reserve(1, 4, 7, 2, 2))
    }

    @Test fun cancelPreventsFinalFlushAndAllLaterSegments() {
        val guard = VoiceCommitGuard(7, 0)
        assertEquals(COMMIT, guard.reserve(0, 2, 7, 0, 0))
        guard.cancel()
        assertEquals(STOP, guard.reserve(1, 2, 7, 2, 2))
        assertEquals(2, guard.expectedCursor)
    }

    @Test fun editorGenerationChangeRejectsEvenIdenticalCursor() {
        val guard = VoiceCommitGuard(7, 0)
        assertEquals(STOP, guard.reserve(0, 2, 8, 0, 0))
        assertFalse(guard.active)
    }

    @Test fun manualCursorMovementStopsWithoutReplacingText() {
        val guard = VoiceCommitGuard(7, 6)
        assertFalse(guard.selectionChanged(6, 6, 1, 1))
        assertEquals(STOP, guard.reserve(0, 2, 7, 1, 1))
        assertEquals(6, guard.expectedCursor)
    }

    @Test fun selectionReplacementIsNeverAllowed() {
        val guard = VoiceCommitGuard(7, 0)
        assertEquals(STOP, guard.reserve(0, 2, 7, 0, 4))
        assertEquals(0, guard.expectedCursor)
    }

    @Test fun actualCursorCheckCatchesMovementBeforeSelectionCallback() {
        val guard = VoiceCommitGuard(7, 5)
        assertEquals(STOP, guard.reserve(0, 2, 7, 3, 3))
    }

    @Test fun unknownCursorFailsClosed() {
        assertEquals(STOP, VoiceCommitGuard(7, -1).reserve(0, 2, 7, -1, -1))
        assertEquals(STOP, VoiceCommitGuard(7, 0).reserve(0, 2, 7, -1, -1))
    }

    @Test fun ownCommitSelectionDoesNotCancel() {
        val guard = VoiceCommitGuard(7, 0)
        guard.reserve(0, 2, 7, 0, 0)
        assertTrue(guard.selectionChanged(0, 0, 2, 2))
        assertEquals(COMMIT, guard.reserve(1, 2, 7, 2, 2))
    }

    @Test fun delayedOwnSelectionUpdatesAreAcceptedInOrder() {
        val guard = VoiceCommitGuard(7, 0)
        guard.reserve(0, 2, 7, 0, 0)
        guard.reserve(1, 2, 7, 2, 2)
        assertTrue(guard.selectionChanged(0, 0, 2, 2))
        assertTrue(guard.selectionChanged(2, 2, 4, 4))
        assertFalse(guard.selectionChanged(4, 4, 2, 2))
    }

    @Test fun coalescedOwnSelectionUpdatesAreAccepted() {
        val guard = VoiceCommitGuard(7, 0)
        guard.reserve(0, 2, 7, 0, 0)
        guard.reserve(1, 2, 7, 2, 2)
        assertTrue(guard.selectionChanged(0, 0, 4, 4))
        assertEquals(COMMIT, guard.reserve(2, 1, 7, 4, 4))
    }

    @Test fun surrogatePairsAdvanceInAndroidUtf16Units() {
        val guard = VoiceCommitGuard(7, 0)
        assertEquals(COMMIT, guard.reserve(0, "𥝚".length, 7, 0, 0))
        assertEquals(2, guard.expectedCursor)
    }

    @Test fun invalidLengthsAndOverflowStop() {
        assertEquals(STOP, VoiceCommitGuard(7, 0).reserve(0, -1, 7, 0, 0))
        assertEquals(STOP, VoiceCommitGuard(7, Int.MAX_VALUE).reserve(
            0, 2, 7, Int.MAX_VALUE, Int.MAX_VALUE))
    }
}
