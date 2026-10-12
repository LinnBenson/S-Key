package io.secure.keyboard.voice

/** Small, deterministic host-commit rule; recognition and provisional previews stay untouched. */
object VoiceSegmentFormatter {
    fun formatFinalSegment(rawText: String): String {
        val text = rawText.trim()
        if (text.isEmpty()) return ""
        // Inspect a code point, not half of a supplementary character. Quotes and brackets
        // are punctuation too: preserve them without appending a second punctuation mark.
        return when (Character.getType(text.codePointBefore(text.length))) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt() -> text
            else -> "$text，"
        }
    }
}
