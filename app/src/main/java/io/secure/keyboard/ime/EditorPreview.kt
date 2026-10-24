package io.secure.keyboard.ime

import android.view.inputmethod.InputConnection

/** Owns only the current editor's temporary composing span, never surrounding text. */
internal class EditorPreview {
    private var connection: InputConnection? = null
    private var text = ""
    private var start = -1
    private val expectedCursors = ArrayDeque<Int>()

    fun update(target: InputConnection?, value: String, selectionStart: Int = -1, selectionEnd: Int = -1) {
        if (target !== connection) clear()
        if (target == null || value.isEmpty()) { clear(); return }
        if (connection === target && value == text) return
        if (connection == null && selectionStart >= 0 && selectionEnd >= 0)
            start = minOf(selectionStart, selectionEnd)
        if (runCatching { target.setComposingText(value, 1) }.getOrDefault(false)) {
            connection = target
            text = value
            if (start >= 0) {
                if (expectedCursors.size >= 128) expectedCursors.removeFirst()
                expectedCursors.addLast(start + value.length)
            }
        }
    }

    /** Cursor acknowledgements may arrive after the preview was removed for an ordered commit. */
    fun cursorsOnClear(): List<Int> = if (connection != null && start >= 0)
        expectedCursors.toList() + start else emptyList()

    fun clear(): Boolean {
        val target = connection
        // Relinquish ownership before host calls: local editors can synchronously re-enter us.
        forget()
        if (target == null) return true
        val removed = runCatching { target.setComposingText("", 1) }.getOrDefault(false)
        runCatching { target.finishComposingText() }
        return removed
    }

    private fun forget() {
        connection = null
        text = ""
        start = -1
        expectedCursors.clear()
    }

    /** A genuine external selection move ends ownership; never delete at its new location. */
    fun selectionChanged(cursor: Int, end: Int, composingStart: Int, composingEnd: Int, moved: Boolean = true): Boolean {
        if (connection == null) return false
        if (cursor == end) {
            val acknowledged = expectedCursors.indexOf(cursor)
            if (acknowledged >= 0) {
                repeat(acknowledged + 1) { expectedCursors.removeFirst() }
                return false
            }
            if (!moved || (start >= 0 && cursor == start + text.length)) return false
            if (composingStart >= 0 && cursor == composingEnd) {
                start = composingStart
                return false
            }
        }
        val target = connection
        forget()
        runCatching { target?.finishComposingText() }
        return true
    }
}
