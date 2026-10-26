package io.secure.keyboard.engine

/** Replayable, lazy ranking: saved order and Extension B tail survive page boundaries. */
internal object CandidateSources {
    fun <T> merge(query: String, baseline: Sequence<String>, custom: List<T>, chinese: Boolean,
                  reading: (T)->String, text: (T)->String): Sequence<String> {
        fun englishCode(value: String) = value.length in 1..40 && value.all { it in 'a'..'z' || it in 'A'..'Z' }
        if (!chinese && !englishCode(query)) return baseline.distinct()
        fun normalize(value: String): String = if (chinese)
            value.lowercase(java.util.Locale.ROOT).replace('ü','v').filterNot { it.isWhitespace() || it=='\'' }
        else if (englishCode(value)) value.lowercase(java.util.Locale.ROOT) else ""

        val key=normalize(query)
        val ranked=sequence {
            val remaining=baseline.iterator()
            if(remaining.hasNext()) yield(remaining.next())
            if(key.isNotEmpty()) yieldAll(custom.asSequence().filter { normalize(reading(it))==key }.map(text))
            yieldAll(remaining.asSequence())
            if(key.isNotEmpty()) yieldAll(custom.asSequence().filter { val code=normalize(reading(it)); code!=key && code.startsWith(key) }.map(text))
        }
        if(!chinese) return ranked.distinct()
        fun extension(value: String) = value.codePoints().anyMatch { it in 0x20000..0x2A6DF }
        return sequence {
            yieldAll(ranked.filterNot(::extension).distinct())
            yieldAll(ranked.filter(::extension).distinct())
        }
    }
}
