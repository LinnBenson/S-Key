package io.secure.keyboard.data

import java.net.URI

/** Extract the first web address without changing the clipboard's original text. */
internal object ClipboardWebLink {
    private val link = Regex("(?:https?://|www\\.)[^\\s\\p{Z}<>\"'\\[\\]（），。！？；：]+", RegexOption.IGNORE_CASE)

    fun parse(text: String): String? {
        for (match in link.findAll(text)) {
            var candidate = match.value.trimEnd('.', ',', '!', '?', ';', ':')
            // Markdown/prose may close a surrounding parenthesis after the URL.
            val extraClosing = candidate.count { it == ')' } - candidate.count { it == '(' }
            if (extraClosing > 0) {
                candidate = candidate.dropLast(minOf(extraClosing, candidate.takeLastWhile { it == ')' }.length))
            }
            val normalized = if (candidate.startsWith("www.", true)) "https://$candidate" else candidate
            val uri = runCatching { URI(normalized) }.getOrNull() ?: continue
            if (uri.host.isNullOrBlank() || uri.port < -1 || uri.port > 65535) continue
            return uri.scheme.lowercase() + uri.toASCIIString().substring(uri.scheme.length)
        }
        return null
    }
}
