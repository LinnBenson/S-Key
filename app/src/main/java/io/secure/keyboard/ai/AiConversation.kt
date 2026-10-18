package io.secure.keyboard.ai

/** No transcript or source text is retained here. Expiry is checked on access. */
internal data class AiConversation(val id: String, val scope: String, val updatedAt: Long, val type: String) {
    fun valid(now: Long, expectedScope: String = scope): Boolean =
        id.matches(Regex("[A-Za-z0-9_-]{1,256}")) && scope == expectedScope &&
            updatedAt > 0 && now >= updatedAt && now - updatedAt < TTL

    companion object {
        const val TTL = 12 * 60 * 60 * 1000L
        fun scope(context: String, type: String, instruction: String): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest("$context\u0000$type\u0000$instruction".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
