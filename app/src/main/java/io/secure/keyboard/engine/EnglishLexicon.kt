package io.secure.keyboard.engine

import java.io.Reader
import java.util.Locale
import java.util.PriorityQueue

/**
 * Read-only current-word completion from a packaged, frequency-ranked vocabulary.
 *
 * The input format is UTF-8 TSV: lowercase ASCII word, tab, positive integer frequency score.
 * Higher scores rank first, with lexical ordering for ties. Comments begin with '#'. The reader
 * is consumed and closed at construction time; lookups never read storage or modify a model.
 * There is deliberately no API for editor context, next words, learning, or user history.
 */
class EnglishLexicon(reader: Reader) {
    private data class Entry(val word: String, val score: Int)

    private val entries: List<Entry> = parse(reader).sortedBy { it.word }

    val entryCount: Int
        get() = entries.size

    /**
     * Returns strict extensions of one current word, even from its first letter. Empty input,
     * spaces, punctuation, numbers, and editor context never produce suggestions.
     *
     * A single capital letter is treated as title case, as on a phone's initial Shift key.
     * Two or more all-capital letters request uppercase completions; otherwise the exact typed
     * prefix is preserved and the untyped suffix is lowercase.
     */
    fun complete(prefix: String, limit: Int = 8): List<String> {
        if (
            prefix.isEmpty() || prefix.length > MAX_WORD_LENGTH || limit <= 0 ||
                prefix.any { it !in 'a'..'z' && it !in 'A'..'Z' }
        ) return emptyList()
        val key = prefix.lowercase(Locale.ROOT)
        val start = lowerBound(key)
        // ASCII letters only: '{' sorts after every possible suffix character.
        val end = lowerBound(key + '{')
        if (start == end) return emptyList()
        val capacity = minOf(limit, end - start)
        // Keep just the best requested results instead of sorting/allocating a whole prefix bucket.
        val best = PriorityQueue<Entry>(capacity, RANKING.reversed())
        for (index in start until end) {
            val entry = entries[index]
            if (entry.word.length == key.length) continue
            if (best.size < capacity) best.add(entry)
            else if (RANKING.compare(entry, best.peek()) < 0) {
                best.poll()
                best.add(entry)
            }
        }
        val allCaps = prefix.length > 1 && prefix.all { it in 'A'..'Z' }
        return best.sortedWith(RANKING).map {
            if (allCaps) it.word.uppercase(Locale.ROOT)
            else prefix + it.word.substring(prefix.length)
        }
    }

    private fun lowerBound(key: String): Int {
        var low = 0
        var high = entries.size
        while (low < high) {
            val mid = low + (high - low) / 2
            if (entries[mid].word < key) low = mid + 1 else high = mid
        }
        return low
    }

    companion object {
        const val MAX_WORD_LENGTH = 40
        private val RANKING = compareByDescending<Entry> { it.score }.thenBy { it.word }

        private fun parse(reader: Reader): List<Entry> = reader.buffered().useLines { lines ->
            val scores = HashMap<String, Int>()
            for (line in lines) {
                if (line.isBlank() || line.startsWith('#')) continue
                val tab = line.indexOf('\t')
                if (tab <= 0 || line.indexOf('\t', tab + 1) != -1) continue
                val word = line.substring(0, tab)
                if (word.length !in 2..MAX_WORD_LENGTH || word.any { it !in 'a'..'z' }) continue
                val score = line.substring(tab + 1).toIntOrNull() ?: continue
                if (score <= 0) continue
                if (score > (scores[word] ?: 0)) scores[word] = score
            }
            scores.map { Entry(it.key, it.value) }
        }
    }
}
