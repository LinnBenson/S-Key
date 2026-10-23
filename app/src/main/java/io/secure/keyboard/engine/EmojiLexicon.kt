package io.secure.keyboard.engine

import java.io.Reader

/** Immutable shortcut configuration. Never retains input queries or usage history. */
class EmojiLexicon(reader: Reader) {
    private val byKeyword: Map<String, List<String>> = buildMap {
        reader.buffered().useLines { lines ->
            lines.filter { it.isNotBlank() }.forEach { line ->
                val fields = line.split('\t')
                require(fields.size >= 2)
                val emoji = fields.first()
                fields.drop(1).forEach { keyword ->
                    put(keyword, (get(keyword).orEmpty() + emoji).distinct())
                }
            }
        }
    }

    /** Match only the first three texts; insert deduplicated emoji immediately after them.
     * With fewer than three texts, append after the available texts without empty slots.
     * The first text stays first, so implicit confirmation cannot select an inserted emoji.
     */
    fun appendTo(words: List<String>): List<String> {
        val leading = words.take(3)
        val emoji = LinkedHashSet<String>()
        leading.forEach { word -> byKeyword[word]?.let(emoji::addAll) }
        emoji.removeAll(words.toSet())
        if (emoji.isEmpty()) return words
        return leading + emoji + words.drop(3)
    }
    /** Preserve the reading consumed by each matching word for segmented pinyin selection. */
    fun appendToOptions(options: Sequence<PinyinDecoder.Option>): Sequence<PinyinDecoder.Option> = sequence {
        val iterator = options.iterator()
        val leading = ArrayList<PinyinDecoder.Option>(3)
        repeat(3) { if (iterator.hasNext()) leading.add(iterator.next()) }
        yieldAll(leading)
        leading.forEach { option ->
            byKeyword[option.text].orEmpty().forEach { emoji -> yield(option.copy(text = emoji)) }
        }
        while (iterator.hasNext()) yield(iterator.next())
    }.distinctBy { it.text }

    /** Direct shortcuts also work when the keyword is absent from the dictionaries. */
    fun appendForKeyword(options: Sequence<PinyinDecoder.Option>, keyword: String): Sequence<PinyinDecoder.Option> {
        val matches = byKeyword[keyword].orEmpty()
        if (matches.isEmpty()) return options
        return sequence {
            val iterator = options.iterator()
            repeat(3) { if (iterator.hasNext()) yield(iterator.next()) }
            matches.forEach { yield(PinyinDecoder.Option(it, keyword.length)) }
            while (iterator.hasNext()) yield(iterator.next())
        }.distinctBy { it.text }
    }

}
