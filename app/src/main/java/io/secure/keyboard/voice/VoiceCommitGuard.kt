package io.secure.keyboard.voice

/**
 * Main-thread session gate. Keeps only segment identities and cursor offsets, never transcript
 * history. A segment is reserved before calling the host, so a failed/duplicate callback cannot
 * insert it twice. Cursor offsets and lengths use Android's UTF-16 units.
 */
class VoiceCommitGuard(val editorGeneration: Int, initialCursor: Int) {
    enum class Decision { COMMIT, IGNORE, STOP }

    var active = initialCursor >= 0
        private set
    var expectedCursor = initialCursor
        private set
    private var lastSegmentId = -1L
    private val pendingSelections = ArrayDeque<Int>()

    fun reserve(
        segmentId: Long,
        textLength: Int,
        currentEditorGeneration: Int,
        selectionStart: Int,
        selectionEnd: Int,
    ): Decision {
        if (!active) return Decision.STOP
        if (currentEditorGeneration != editorGeneration) return stop()
        if (segmentId <= lastSegmentId) return Decision.IGNORE
        if (selectionStart != expectedCursor || selectionEnd != expectedCursor) return stop()
        lastSegmentId = segmentId
        if (textLength == 0) return Decision.IGNORE
        if (textLength < 0 || expectedCursor > Int.MAX_VALUE - textLength) return stop()
        expectedCursor += textLength
        pendingSelections.addLast(expectedCursor)
        return Decision.COMMIT
    }

    /** Android may coalesce or delay acknowledgements from our own successive commits. */
    fun selectionChanged(oldStart: Int, oldEnd: Int, newStart: Int, newEnd: Int): Boolean {
        if (!active) return false
        if (newStart < 0 || newStart != newEnd) { cancel(); return false }
        if (oldStart == newStart && oldEnd == newEnd) return true
        if (newStart == expectedCursor) { pendingSelections.clear(); return true }
        val index = pendingSelections.indexOf(newStart)
        if (index >= 0) {
            repeat(index + 1) { pendingSelections.removeFirst() }
            return true
        }
        cancel()
        return false
    }

    fun cancel() { active = false; pendingSelections.clear() }

    private fun stop(): Decision { cancel(); return Decision.STOP }
}
