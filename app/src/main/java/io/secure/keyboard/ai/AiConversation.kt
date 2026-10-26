package io.secure.keyboard.ai

/** Latest continuation per conversation, with a bounded first-message preview. */
internal data class AiConversation(val id: String, val scope: String, val updatedAt: Long, val type: String,
    val key: String = id,
    val startedAt: Long = updatedAt,
    val firstMessage: String = "",
    val title: String = type,
    val tone: String = "自然",
    val presetName: String = "",
    val prompt: String = "",
    val reasoning: String = "low",
    val model: String = "",
    val retentionHours: Int = DEFAULT_HOURS,
    val webSearch: Boolean = false,
    val xSearch: Boolean = false,
) {
    fun normalizeSearchScope(context: String, instruction: String): AiConversation {
        if (!type.startsWith("custom:") || webSearch || xSearch) return this
        val transitional = scope(context, type, instruction + "\u0000reasoning:" + reasoning + "\u0000search:false:false")
        return if (scope == transitional) copy(scope = scope(context, type, instruction, reasoning)) else this
    }

    fun valid(now: Long, expectedScope: String = scope): Boolean =
        id.matches(Regex("[A-Za-z0-9_-]{1,256}")) && scope == expectedScope &&
            updatedAt > 0 && now >= updatedAt && now - updatedAt < retentionMillis

    val retentionMillis: Long get() = retentionHours.coerceIn(0, 720) * 3_600_000L

    companion object {
        const val DEFAULT_HOURS = 4
        const val TTL = DEFAULT_HOURS * 60 * 60 * 1000L
        // Preserve pre-search continuation scopes when both tools are disabled.
        fun scope(context: String, type: String, instruction: String, reasoning: String,
            webSearch: Boolean = false, xSearch: Boolean = false): String =
            scope(context, type, instruction + "\u0000reasoning:" + reasoning +
                if (webSearch || xSearch) "\u0000search:$webSearch:$xSearch" else "")

        fun scope(context: String, type: String, instruction: String): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest("$context\u0000$type\u0000$instruction".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
