package io.secure.keyboard.ime

import org.junit.Assert.*
import org.junit.Test

class QuickPasteStateTest {
    @Test fun unusedClipIsConsumedOnFirstOpening() {
        var consumed = 0L
        val state = QuickPasteState()
        state.open(10, "copy", consumed) { consumed = it }
        assertEquals("copy", state.text)
        state.clear()
        state.open(10, "copy", consumed) { consumed = it }
        assertNull(state.text)
        // Process recreation also respects persisted consumption.
        QuickPasteState().apply {
            open(10, "copy", consumed) { consumed = it }
            assertNull(text)
        }
    }

    @Test fun copyWhileOpenWaitsForNextOpening() {
        var consumed = 0L
        val state = QuickPasteState()
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
        val state = QuickPasteState()
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
}
