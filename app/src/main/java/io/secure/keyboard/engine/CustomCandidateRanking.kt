package io.secure.keyboard.engine

import java.util.Locale

/** Deterministic user-dictionary merging. No query cache, learning, or personal frequencies. */
object CustomCandidateRanking {
    /**
     * Keep the first normal/Wubi candidate, then promote exact custom-code matches in saved
     * dictionary order. The rest of the normal candidates keep their relative order, followed
     * by partial-code custom matches. A text appears once, at its highest-priority position.
     *
     * [baseline] is already in the caller's normal Wubi-first order. Its lookup limit applies
     * only to built-ins: every matching custom entry remains reachable in the final result.
     * Finally, candidates containing any CJK Extension B code point move to the tail, preserving
     * both groups' relative order. This final rule also applies to exact/prefix custom matches.
     */
    fun <T> merge(
        query: String,
        baseline: List<String>,
        custom: List<T>,
        reading: (T) -> String,
        text: (T) -> String,
    ): List<String> {
        val key = normalizeCode(query)
        if (key.isEmpty()) return extensionBLast(baseline.distinct())
        val exact = ArrayList<String>()
        val prefix = ArrayList<String>()
        for (entry in custom) {
            val code = normalizeCode(reading(entry))
            if (code == key) exact.add(text(entry))
            else if (code.startsWith(key)) prefix.add(text(entry))
        }
        val result = LinkedHashSet<String>()
        baseline.firstOrNull()?.let(result::add)
        result.addAll(exact)
        result.addAll(baseline)
        result.addAll(prefix)
        return extensionBLast(result.toList())
    }

    /** Ranking only: retain complete strings, including supplementary pairs and mixed phrases. */
    private fun extensionBLast(values: List<String>): List<String> {
        val ordinary = ArrayList<String>(values.size)
        val extensionB = ArrayList<String>()
        for (value in values) {
            var index = 0
            var containsExtensionB = false
            while (index < value.length) {
                val codePoint = Character.codePointAt(value, index)
                if (codePoint in 0x20000..0x2A6DF) {
                    containsExtensionB = true
                    break
                }
                index += Character.charCount(codePoint)
            }
            if (containsExtensionB) extensionB.add(value) else ordinary.add(value)
        }
        if (extensionB.isEmpty()) return values
        ordinary.addAll(extensionB)
        return ordinary
    }

    private fun normalizeCode(value: String): String =
        value.lowercase(Locale.ROOT).replace('ü', 'v').filterNot { it.isWhitespace() || it == '\'' }
}
