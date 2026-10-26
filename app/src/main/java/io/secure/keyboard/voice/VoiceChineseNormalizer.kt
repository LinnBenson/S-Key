package io.secure.keyboard.voice

import android.content.Context

/** Fixed dictionary cache only: transcripts are converted in memory and never retained. */
internal object VoiceChineseNormalizer {
    @Volatile private var table: Map<String, String>? = null
    private var maxLength = 1
    @Synchronized fun trimMemory() { table = null }

    private fun mappings(context: Context): Map<String, String> = table ?: synchronized(this) {
        table ?: buildMap {
            for (name in listOf("TSCharacters.txt", "TSPhrases.txt")) {
                context.assets.open("chinese/$name").bufferedReader().useLines { lines ->
                    lines.filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
                        val fields = line.split('\t', limit = 2)
                        require(fields.size == 2) { "繁简字典格式错误" }
                        put(fields[0], fields[1].substringBefore(' '))
                    }
                }
            }
        }.also { maxLength = it.keys.maxOf { key -> key.length }; table = it }
    }

    fun simplify(context: Context, text: String): String {
        if (text.none { it >= '\u2e80' }) return text
        val entries = mappings(context)
        return buildString(text.length) {
            var offset = 0
            while (offset < text.length) {
                var matched = false
                for (length in minOf(maxLength, text.length - offset) downTo 1) {
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
}
