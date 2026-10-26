package io.secure.keyboard.data

/** Longest-match conversion using pinned OpenCC ST phrases and characters. */
internal class TraditionalConverter(lines: Sequence<String>) {
    companion object {
        @Volatile private var shared: TraditionalConverter? = null
        @Synchronized fun trimMemory() { shared = null }
        fun shared(load: () -> TraditionalConverter): TraditionalConverter = shared ?: synchronized(this) {
            shared ?: load().also { shared = it }
        }
    }
    private val entries = HashMap<String, String>()
    private val lengths: Map<Char, List<Int>>
    init {
        val sizes = HashMap<Char, MutableSet<Int>>()
        lines.filter { it.isNotBlank() && !it.startsWith('#') }.forEach { line ->
            val parts = line.split('\t', limit = 2)
            require(parts.size == 2)
            val key = parts[0]
            entries[key] = parts[1].substringBefore(' ')
            sizes.getOrPut(key[0]) { mutableSetOf() }.add(key.length)
        }
        lengths = sizes.mapValues { it.value.sortedDescending() }
    }
    fun convert(text: String): String = buildString(text.length) {
        var offset = 0
        while (offset < text.length) {
            var matched = false
            for (length in lengths[text[offset]].orEmpty()) {
                if (offset + length > text.length) continue
                val replacement = entries[text.substring(offset, offset + length)] ?: continue
                append(replacement)
                offset += length
                matched = true
                break
            }
            if (!matched) append(text[offset++])
        }
    }
}
