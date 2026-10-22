package io.secure.keyboard.ime

import org.junit.Assert.*
import org.junit.Test

class QuickPasteStateTest {
    @Test fun unusedClipIsConsumedOnFirstOpening() {
        var consumed = 0L
        val state = QuickPasteState { 11L }
        state.open(10, "copy", consumed) { consumed = it }
        assertEquals("copy", state.text)
        state.clear()
        state.open(10, "copy", consumed) { consumed = it }
        assertNull(state.text)
        // Process recreation also respects persisted consumption.
        QuickPasteState { 11L }.apply {
            open(10, "copy", consumed) { consumed = it }
            assertNull(text)
        }
    }

    @Test fun copyWhileOpenWaitsForNextOpening() {
        var consumed = 0L
        val state = QuickPasteState { 11L }
        state.open(10, "first", consumed) { consumed = it }
        // New clipboard value is read only at the next opening, never by a listener.
        assertEquals("first", state.text)
        state.clear()
        state.open(11, "second", consumed) { consumed = it }
        assertEquals("second", state.text)
        state.clear()
        state.open(11, "second", consumed) { consumed = it }
        assertNull(state.text)
    }

    @Test fun disabledAndPrivateClipsDoNotResurfaceAndNewCopiesCanRepeatText() {
        var consumed = 0L
        val state = QuickPasteState { 11L }
        state.open(10, null, consumed) { consumed = it }
        state.open(10, "copy", consumed) { consumed = it }
        assertNull(state.text)
        state.open(11, "copy", consumed) { consumed = it }
        assertEquals("copy", state.text)
        val revision = state.revision
        state.clear()
        assertTrue(state.revision > revision)
        state.open(0, "unknown", consumed) { fail("Invalid identity") }
        assertNull(state.text)
    }
    @Test fun onlyCopiesWithinFiveMinutesAreOffered() {
        val copiedAt = 1_000_000L
        var now = copiedAt + QuickPasteState.MAX_AGE_MS
        var consumed = 0L
        val state = QuickPasteState { now }
        state.open(copiedAt, "at boundary", consumed) { consumed = it }
        assertEquals("at boundary", state.text)

        now++
        consumed = 0L
        state.open(copiedAt, "expired", consumed) { consumed = it }
        assertNull(state.text)
        assertEquals(copiedAt, consumed)
        // A clock adjustment must not revive an already expired copy.
        now = copiedAt
        state.open(copiedAt, "expired", consumed) { consumed = it }
        assertNull(state.text)
    }

    @Test fun futureTimestampIsNotOffered() {
        val state = QuickPasteState { 100L }
        state.open(101L, "future", 0L) {}
        assertNull(state.text)
    }

    @Test fun copyWhileOpenCanExpireBeforeNextOpening() {
        var now = 1000L
        val state = QuickPasteState { now }
        state.open(1000L, "first", 0L) {}
        // Another copy arrives while open; it is not offered until reopening.
        val nextCopy = 2000L
        state.clear()
        now = nextCopy + QuickPasteState.MAX_AGE_MS + 1
        state.open(nextCopy, "second", 1000L) {}
        assertNull(state.text)
    }

    @Test fun visibleCopyExpiresWithoutReopeningAndCannotBePasted() {
        var now = 1000L
        val state = QuickPasteState { now }
        state.open(now, "copy", 0L) {}
        assertEquals(QuickPasteState.MAX_AGE_MS + 1, state.remainingMillis)
        now += QuickPasteState.MAX_AGE_MS
        assertFalse(state.expireIfNeeded())
        assertEquals(1L, state.remainingMillis)
        now++
        val revision = state.revision
        assertTrue(state.expireIfNeeded())
        assertNull(state.text)
        assertTrue(state.revision > revision)
        assertEquals(0L, state.remainingMillis)
        assertFalse(state.expireIfNeeded())
    }

}
