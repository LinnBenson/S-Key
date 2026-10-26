package io.secure.keyboard.ui

/** Groups only variants present in the bundled emoji catalog; never invents sequences. */
internal class EmojiVariants(emojis: List<String>) {
    private fun hasTone(value: String) = value.codePoints().anyMatch { it in 0x1F3FB..0x1F3FF }
    private fun key(value: String): String {
        val plain = buildString {
            value.codePoints().filter { it !in 0x1F3FB..0x1F3FF && it != 0xFE0F }
                .forEach { appendCodePoint(it) }
        }
        return when (plain) {
            "🫱‍🫲" -> "🤝"
            "👩‍🤝‍👩" -> "👭"
            "👨‍🤝‍👨" -> "👬"
            "👩‍🤝‍👨" -> "👫"
            "🧑‍❤‍💋‍🧑" -> "💏"
            "🧑‍❤‍🧑" -> "💑"
            else -> plain
        }
    }
    val defaults: List<String> = emojis.filterNot(::hasTone).distinct()
    private val families = emojis.groupBy(::key)
    fun choices(emoji: String): List<String> {
        val family = families[key(emoji)] ?: return emptyList()
        if (family.none(::hasTone)) return emptyList()
        return family.sortedBy { if (hasTone(it)) 1 else 0 }.distinct()
    }
}
