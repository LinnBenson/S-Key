package io.secure.keyboard.ime

import android.view.inputmethod.InputConnection

/** Owns only the current editor's temporary composing span, never surrounding text. */
internal class EditorPreview {
    private var connection: InputConnection? = null
    private var text = ""

    fun update(target: InputConnection?, value: String) {
        if (target !== connection) clear()
        if (target == null || value.isEmpty()) { clear(); return }
        if (connection === target && value == text) return
        if (runCatching { target.setComposingText(value, 1) }.getOrDefault(false)) {
            connection = target
            text = value
        }
    }

    fun committed(target: InputConnection?) {
        if (target === connection) { connection = null; text = "" }
    }

    fun clear(): Boolean {
        val target = connection ?: return true
        val removed = runCatching { target.setComposingText("", 1) }.getOrDefault(false)
        runCatching { target.finishComposingText() }
        connection = null
        text = ""
        return removed
    }

    /** An external selection move ends ownership; never delete at its new location. */
    fun selectionChanged(start: Int, end: Int, composingStart: Int, composingEnd: Int): Boolean {
        if (connection == null) return false
        if (start == end && composingStart >= 0 && start == composingEnd) return false
        runCatching { connection?.finishComposingText() }
        connection = null
        text = ""
        return true
    }
}
