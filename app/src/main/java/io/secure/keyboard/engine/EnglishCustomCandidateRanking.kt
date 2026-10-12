package io.secure.keyboard.engine

import java.util.Locale

/** Stateless English-only merging. Stored outputs are literal, including case and Unicode. */
object EnglishCustomCandidateRanking {
    /**
     * Keep the first built-in, exact custom codes in saved order, the remaining built-ins,
     * then custom code prefixes. Deduplicate exact output strings at their first occurrence.
     * Only the caller's built-in lookup is bounded; never truncate the custom tail.
     */
    fun <T> merge(
        query: String,
        baseline: List<String>,
        custom: List<T>,
        reading: (T) -> String,
        text: (T) -> String,
    ): List<String> {
        if (!isCode(query)) return baseline.distinct()
        val key = query.lowercase(Locale.ROOT)
        val exact = ArrayList<String>()
        val prefix = ArrayList<String>()
        for (entry in custom) {
            val savedCode = reading(entry)
            if (!isCode(savedCode)) continue
            val code = savedCode.lowercase(Locale.ROOT)
            if (code == key) exact.add(text(entry))
            else if (code.startsWith(key)) prefix.add(text(entry))
        }
        val result = LinkedHashSet<String>()
        baseline.firstOrNull()?.let(result::add)
        result.addAll(exact)
        result.addAll(baseline)
        result.addAll(prefix)
        return result.toList()
    }

    private fun isCode(value: String): Boolean =
        value.length in 1..40 && value.all { it in 'a'..'z' || it in 'A'..'Z' }
}
