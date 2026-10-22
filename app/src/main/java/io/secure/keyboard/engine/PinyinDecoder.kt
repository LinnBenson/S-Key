package io.secure.keyboard.engine

import kotlin.math.ln

/** Bounded word-lattice decoder. Query-local caches never retain typed text after a request. */
class PinyinDecoder(
    private val lookup: (String) -> List<DictionaryEngine.Entry>,
    private val predict: (String) -> List<String> = { emptyList() },
    private val cancelled: () -> Boolean = { false },
) {
    data class Option(val text: String, val consumed: Int)
    private data class Path(val text: String, val score: Double)

    fun candidates(raw: String, context: String = "", custom: List<Pair<String, String>> = emptyList()): List<Option> {
        if (cancelled()) return emptyList()
        if (raw.isEmpty()) return predictions(context)
        if (raw.length > 128 || raw.any { it !in 'a'..'z' && it != '\'' }) return emptyList()
        val customByCode = custom.groupBy { it.first.replace(" ", "").replace("'", "") }
        val cache = HashMap<String, List<DictionaryEngine.Entry>>()
        val forecasts = HashMap<String, List<String>>()
        fun entries(token: String): List<DictionaryEngine.Entry> = cache.getOrPut(token) {
            val user = customByCode[token.replace("'", "")].orEmpty()
                .map { DictionaryEngine.Entry(it.second, token, 1_000_000_000L) }
            (user + lookup(token)).distinctBy { it.text }.take(12)
        }
        fun bonus(previous: String, text: String): Double {
            if (previous.isEmpty()) return 0.0
            val key = previous.takeLast(4)
            val next = forecasts[key] ?: if (forecasts.size < 128) predict(key).also { forecasts[key] = it } else emptyList()
            return if (next.any { it.startsWith(text) || text.startsWith(it) }) 5.0 else 0.0
        }
        val paths = Array(raw.length + 1) { emptyList<Path>() }
        paths[0] = listOf(Path("", 0.0))
        val prefixes = ArrayList<Pair<Option, Double>>()
        for (start in raw.indices) {
            if (cancelled() || Thread.currentThread().isInterrupted) return emptyList()
            if (paths[start].isEmpty()) continue
            if (raw[start] == '\'') {
                paths[start + 1] = (paths[start + 1] + paths[start]).sortedByDescending { it.score }.take(8)
                continue
            }
            for (end in start + 1..minOf(raw.length, start + 64)) {
                if (cancelled() || Thread.currentThread().isInterrupted) return emptyList()
                if (raw[end - 1] == '\'') continue
                val token = raw.substring(start, end)
                val matches = entries(token)
                if (matches.isEmpty()) continue
                val next = paths[start].flatMap { path -> matches.take(5).map { entry ->
                    Path(path.text + entry.text, path.score + ln(entry.weight.toDouble() + 1) - 20.0 + bonus(context + path.text, entry.text))
                } }
                paths[end] = (paths[end] + next).sortedByDescending { it.score }.distinctBy { it.text }.take(8)
                if (start == 0) matches.forEach { prefixes.add(Option(it.text, end) to ln(it.weight.toDouble() + 1) + bonus(context, it.text)) }
            }
        }
        val full = paths[raw.length].map { Option(it.text, raw.length) }
        // Full sentences first, then longest selectable words; partial choices leave the remainder.
        val priorityExact = entries(raw).filter { it.priority!=Long.MAX_VALUE }
            .sortedBy { it.priority }.map { Option(it.text,raw.length) }
        return (priorityExact + full + prefixes.sortedWith(compareByDescending<Pair<Option, Double>> { it.first.consumed }.thenByDescending { it.second }).map { it.first })
            .distinctBy { it.text }.take(60)
    }

    fun predictions(context: String): List<Option> = if (context.isBlank()) emptyList() else
        predict(context.takeLast(8)).distinct().take(12).map { Option(it, 0) }
}

/** Selected segments remain preedit until the final segment is accepted. Backspace can undo one. */
class PinyinComposition {
    data class Segment(val text: String, val raw: String)
    private val segments = ArrayList<Segment>()
    val rawLength: Int get() = segments.sumOf { it.raw.length }
    val text: String get() = segments.joinToString("") { it.text }
    fun select(text: String, raw: String) { segments.add(Segment(text, raw)) }
    fun undo(): String? = if (segments.isEmpty()) null else segments.removeAt(segments.lastIndex).raw
    fun clear() = segments.clear()
}
