package io.secure.keyboard.engine

import java.util.Locale

/** Immutable saved-entry index. Contains no query history and preserves the user's row order. */
class CustomDictionaryIndex<T>(rows: List<T>, reading: (T) -> String, private val english: Boolean) {
    private data class Row<T>(val code: String, val order: Int, val value: T)
    private val sorted = rows.mapIndexedNotNull { order, value ->
        normalize(reading(value))?.let { Row(it, order, value) }
    }.sortedBy { it.code }

    fun matching(query: String): List<T> {
        val key = normalize(query) ?: return emptyList()
        if (key.isEmpty()) return emptyList()
        var low = 0
        var high = sorted.size
        while (low < high) {
            val mid = (low+high).ushr(1)
            if (sorted[mid].code < key) low = mid+1 else high = mid
        }
        val matches = ArrayList<Row<T>>()
        while (low < sorted.size && sorted[low].code.startsWith(key)) matches.add(sorted[low++])
        matches.sortBy { it.order }
        return matches.map { it.value }
    }

    private fun normalize(value: String): String? {
        if (english) {
            if (value.length !in 1..40 || value.any { it !in 'a'..'z' && it !in 'A'..'Z' }) return null
            return value.lowercase(Locale.ROOT)
        }
        return value.lowercase(Locale.ROOT).replace('ü','v').filterNot { it.isWhitespace() || it=='\'' }
    }
}
