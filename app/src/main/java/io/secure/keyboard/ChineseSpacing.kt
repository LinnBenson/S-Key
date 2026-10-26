package io.secure.keyboard

/** Inserts spaces only across Han/ASCII word boundaries, never within a word. */
internal object ChineseSpacing {
    private fun han(cp: Int) = Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN
    private fun word(cp: Int) = cp in 48..57 || cp in 65..90 || cp in 97..122
    private fun boundary(a: Int, b: Int) = (han(a) && word(b)) || (word(a) && han(b))
    data class Edit(val text: String, val trailingSpace: Boolean)
    fun format(value: String, before: String = "", after: String = ""): Edit {
        if (value.isEmpty()) return Edit(value, false)
        val result = StringBuilder()
        var previous = if (before.isEmpty()) -1 else before.codePointBefore(before.length)
        var index = 0
        while (index < value.length) {
            val cp = value.codePointAt(index)
            if (previous >= 0 && boundary(previous, cp)) result.append(' ')
            result.appendCodePoint(cp)
            previous = cp
            index += Character.charCount(cp)
        }
        val trailing = after.isNotEmpty() && boundary(previous, after.codePointAt(0))
        if (trailing) result.append(' ')
        return Edit(result.toString(), trailing)
    }
}
