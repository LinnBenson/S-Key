package io.secure.keyboard.engine

import java.io.Reader
import java.util.Locale

/** Static, deterministic dictionary lookup. No user model, context, persistence, or network. */
enum class Source {
    WUBI,
    PINYIN,
    ENGLISH,
    BUILTIN,
}

data class Candidate(val text: String, val code: String, val source: Source)

class DictionaryEngine private constructor(
    wubi: Reader?,
    pinyin: Reader,
    private val englishLexicon: EnglishLexicon?,
    private val compactWubi: CompactWubiTable?,
) {
    /** Text constructor retained for reference validation and small test fixtures. */
    constructor(wubi: Reader, pinyin: Reader, englishLexicon: EnglishLexicon? = null) :
        this(wubi, pinyin, englishLexicon, null)

    /** Production Wubi data stays in an immutable read-only file mapping. */
    constructor(wubi: CompactWubiTable, pinyin: Reader, englishLexicon: EnglishLexicon? = null) :
        this(null, pinyin, englishLexicon, wubi)

    private data class Entry(val text: String, val code: String, val weight: Long)

    private val wubiExact: Map<String, List<Entry>>
    private val pinyinExact: Map<String, List<Entry>>
    private val fuzzyExact: Map<String, List<Entry>>
    private val syllables: Map<String, List<Entry>>
    private val wubiPrefixes: Map<String, List<Entry>>

    val wubiEntryCount: Int
    val pinyinEntryCount: Int

    init {
        // Entries remain owned by the indexes. Keep only counts, not redundant master lists.
        val wubiEntries = if (compactWubi == null) parse(requireNotNull(wubi)) else emptyList()
        wubiEntryCount = compactWubi?.entryCount ?: wubiEntries.size
        wubiExact = groupEntries(wubiEntries) { compact(it.code) }
        wubiPrefixes = prefixEntries(wubiEntries)

        val pinyinEntries = parse(pinyin)
        pinyinEntryCount = pinyinEntries.size
        pinyinExact = groupEntries(pinyinEntries) { compact(it.code) }
        fuzzyExact = groupEntries(pinyinEntries) { fuzzyKey(it.code) }
        syllables =
            groupEntries(
                pinyinEntries,
                include = {
                    !it.code.contains(' ') && it.text.codePointCount(0, it.text.length) == 1
                },
            ) {
                compact(it.code)
            }
    }

    /** Indexed exact table lookup for deterministic fourth-keystroke commit. */
    fun exactWubi(code: String, limit: Int = 40): List<Candidate> {
        if (limit <= 0 || code.isEmpty() || code.length > 4 || code.any { it !in 'a'..'z' })
            return emptyList()
        compactWubi?.let { return it.exact(code, limit) }
        return wubiExact[code]
            .orEmpty()
            .asSequence()
            .distinctBy { it.text }
            .take(limit)
            .map { Candidate(it.text, it.code, Source.WUBI) }
            .toList()
    }

    fun candidates(
        code: String,
        fuzzyPinyin: Boolean = true,
        limit: Int = 40,
        fuzzyPairs: Set<String> = DEFAULT_FUZZY_PAIRS,
        allExactWubi: Boolean = false,
    ): List<Candidate> {
        if (limit <= 0) return emptyList()
        val raw = code.lowercase(Locale.ROOT).replace('ü', 'v')
        if (raw.length > 64 || raw.any { it !in 'a'..'z' && it != '\'' && it != ' ' })
            return emptyList()
        val key = compact(raw)
        if (key.isEmpty()) return emptyList()
        // Full four-code matches remain reachable even when a bucket exceeds one UI page.
        // Only the explicit production option lifts the supplied limit; short prefixes retain
        // their existing bound, and automatic commit still requests one exact candidate.
        val resultLimit = if (allExactWubi && key.length == 4) {
            maxOf(limit, compactWubi?.exactCandidateCount(key) ?: wubiExact[key].orEmpty().size)
        } else limit
        val result = LinkedHashMap<String, Candidate>()
        BuiltinExactDictionary.lookup(raw)?.let {
            result[it] = Candidate(it, key, Source.BUILTIN)
        }
        fun add(entries: List<Entry>?, source: Source) {
            entries?.forEach {
                if (result.size < resultLimit)
                    result.putIfAbsent(it.text, Candidate(it.text, it.code, source))
            }
        }
        if (compactWubi == null) add(wubiExact[key], Source.WUBI)
        else compactWubi.exact(key, resultLimit).forEach { result.putIfAbsent(it.text, it) }
        val segmented = raw.contains('\'') || raw.contains(' ')
        val syllableCode = raw.split(' ', '\'').filter(String::isNotEmpty).joinToString(" ")
        val exactPinyin = pinyinExact[key]?.filter { !segmented || it.code == syllableCode }
        add(exactPinyin, Source.PINYIN)
        if (fuzzyPinyin && fuzzyPairs.isNotEmpty()) {
            // Preserve the existing pairs' matching rules. The optional r/l pair has a
            // stricter, syllable-aware check below so it cannot turn a final er into el.
            val selectedPairs = fuzzyPairs - "r_l"
            val selectedKey = fuzzyInputKey(raw, selectedPairs)
            val selectedSyllables =
                if (segmented) syllableCode.split(' ').map { fuzzySyllable(it, selectedPairs) }
                else emptyList()
            add(
                fuzzyExact[fuzzyInputKey(raw)]?.filter {
                    (fuzzyInputKey(it.code, selectedPairs) == selectedKey &&
                        (!segmented ||
                            it.code.split(' ').map { part -> fuzzySyllable(part, selectedPairs) } ==
                                selectedSyllables)) ||
                        ("r_l" in fuzzyPairs && matchesRL(raw, it.code, selectedPairs))
                },
                Source.PINYIN,
            )
        }
        // A dictionary phrase is preferred; otherwise segment into known words/syllables locally.
        if (exactPinyin.isNullOrEmpty() && key.length >= 4) {
            for (text in composePinyin(raw)) if (result.size < resultLimit) {
                result.putIfAbsent(text, Candidate(text, raw, Source.PINYIN))
            }
        }
        if (compactWubi == null) add(wubiPrefixes[key], Source.WUBI)
        else if (result.size < resultLimit) {
            compactWubi.prefix(key, resultLimit).forEach {
                if (result.size < resultLimit) result.putIfAbsent(it.text, it)
            }
        }
        return result.values.toList()
    }

    /** Complete only the supplied current word. Never accepts surrounding editor text. */
    fun completeEnglish(prefix: String, limit: Int = 8): List<Candidate> =
        englishLexicon
            ?.complete(prefix, limit)
            ?.map { Candidate(it, prefix, Source.ENGLISH) }
            .orEmpty()

    private data class Path(val text: String, val score: Double, val count: Int)

    private fun composePinyin(raw: String): List<String> {
        // Explicit apostrophes/spaces force syllable boundaries (xi'an versus xian).
        val tokens = raw.split(' ', '\'').filter(String::isNotEmpty)
        if (tokens.size > 1) {
            var paths = listOf(Path("", 0.0, 0))
            for (token in tokens) {
                val entries = syllables[token]?.take(3) ?: return emptyList()
                paths =
                    paths
                        .flatMap { p ->
                            entries.map { Path(p.text + it.text, p.score + score(it), p.count + 1) }
                        }
                        .sortedByDescending { it.score }
                        .take(6)
            }
            return paths.map { it.text }
        }
        val key = compact(raw)
        val paths = Array(key.length + 1) { emptyList<Path>() }
        paths[0] = listOf(Path("", 0.0, 0))
        for (start in key.indices) {
            if (paths[start].isEmpty()) continue
            for (end in start + 1..minOf(key.length, start + 24)) {
                val entries = pinyinExact[key.substring(start, end)]?.take(3) ?: continue
                val next =
                    paths[start].flatMap { p ->
                        entries.map {
                            Path(p.text + it.text, p.score + score(it) - 20.0, p.count + 1)
                        }
                    }
                paths[end] =
                    (paths[end] + next)
                        .sortedByDescending { it.score }
                        .distinctBy { it.text }
                        .take(6)
            }
        }
        return paths[key.length].filter { it.count > 1 }.map { it.text }
    }

    private fun score(entry: Entry) = kotlin.math.ln(entry.weight.toDouble() + 1.0)

    companion object {
        val DEFAULT_FUZZY_PAIRS: Set<String> =
            setOf("zh_z", "ch_c", "sh_s", "n_l", "f_h", "an_ang", "en_eng", "in_ing")
        private val ALL_FUZZY_PAIRS = DEFAULT_FUZZY_PAIRS + "r_l"
        private val N_BEFORE_VOWEL = Regex("n(?=[aeiouv])")
        // Only complete, valid syllables can participate in an r/l change. The n
        // syllables also allow r/l and n/l to work together when BOTH are selected.
        private val R_L_N_SYLLABLES =
            ("ran rang rao re ren reng ri rong rou ru ruan rui run ruo " +
                    "la lai lan lang lao le lei leng li lia lian liang liao lie lin ling liu " +
                    "lo long lou lu luan lue lun luo lv " +
                    "na nai nan nang nao ne nei nen neng ni nian niang niao nie nin ning " +
                    "niu nong nou nu nuan nue nuo nv")
                .split(' ')
                .toSet()

        private fun compact(code: String) = code.replace(" ", "").replace("'", "")

        private fun groupEntries(
            entries: List<Entry>,
            include: (Entry) -> Boolean = { true },
            key: (Entry) -> String,
        ): Map<String, List<Entry>> {
            val groups = LinkedHashMap<String, ArrayList<Entry>>()
            for (entry in entries) {
                if (include(entry)) groups.getOrPut(key(entry)) { ArrayList(1) }.add(entry)
            }
            // Most codes have very few entries. Avoid a default ten-slot array per bucket,
            // and retain no unused slots after construction. Entry order is unchanged.
            groups.values.forEach { it.trimToSize() }
            return groups
        }

        private fun prefixEntries(entries: List<Entry>): Map<String, List<Entry>> =
            buildMap<String, ArrayList<Entry>> {
                // Build directly into the final map, without a temporary map and putAll copy.
                // The stable frequency sort precedes indexing; retain only the first 30.
                for (entry in entries) for (length in 1 until entry.code.length) {
                    val list = getOrPut(entry.code.take(length)) { ArrayList(1) }
                    if (list.size < 30) list.add(entry)
                }
                values.forEach { it.trimToSize() }
            }

        private fun parse(reader: Reader): List<Entry> =
            reader.buffered().useLines { lines ->
                var started = false
                lines
                    .mapNotNull { line ->
                        if (line.trim() == "...") {
                            started = true
                            null
                        } else if (!started || line.startsWith('#') || line.isBlank()) null
                        else {
                            val parts = line.split('\t')
                            if (parts.size < 2 || parts[1].any { it !in 'a'..'z' && it != ' ' })
                                null
                            else Entry(parts[0], parts[1], parts.getOrNull(2)?.toLongOrNull() ?: 0L)
                        }
                    }
                    .toCollection(ArrayList<Entry>())
                    .apply {
                        // Stable, like sortedByDescending, without a second full entry array.
                        sortByDescending { it.weight }
                    }
            }

        private fun fuzzySyllable(s: String, pairs: Set<String>): String {
            var v = s
            for ((pair, from, to) in
                listOf(
                    Triple("zh_z", "zh", "z"),
                    Triple("ch_c", "ch", "c"),
                    Triple("sh_s", "sh", "s"),
                    Triple("n_l", "n", "l"),
                    Triple("f_h", "f", "h"),
                )) {
                if (pair in pairs && v.startsWith(from)) v = to + v.drop(from.length)
            }
            for ((pair, from, to) in
                listOf(
                    Triple("an_ang", "ang", "an"),
                    Triple("en_eng", "eng", "en"),
                    Triple("in_ing", "ing", "in"),
                )) {
                if (pair in pairs && v.endsWith(from)) v = v.dropLast(from.length) + to
            }
            return v
        }

        private fun fuzzyKey(code: String) = fuzzyInputKey(code)

        private fun matchesRLSyllable(typed: String, stored: String, pairs: Set<String>): Boolean {
            if (fuzzySyllable(typed, pairs) == fuzzySyllable(stored, pairs)) return true
            if (typed !in R_L_N_SYLLABLES || stored !in R_L_N_SYLLABLES) return false
            fun canonical(s: String) =
                fuzzySyllable(if (s.startsWith('r')) "l" + s.drop(1) else s, pairs)
            return canonical(typed) == canonical(stored)
        }

        private fun matchesRL(raw: String, code: String, pairs: Set<String>): Boolean {
            val stored = code.split(' ').filter(String::isNotEmpty)
            if (raw.contains(' ') || raw.contains('\'')) {
                val typed = raw.split(' ', '\'').filter(String::isNotEmpty)
                return typed.size == stored.size &&
                    typed.indices.all { matchesRLSyllable(typed[it], stored[it], pairs) }
            }
            // Try only boundaries in the dictionary reading. This bounded dynamic
            // program handles compact phrases without rewriting interior/final r.
            var reachable = BooleanArray(raw.length + 1).apply { this[0] = true }
            for (syllable in stored) {
                val next = BooleanArray(raw.length + 1)
                for (start in raw.indices) {
                    if (!reachable[start]) continue
                    for (end in start + 1..minOf(raw.length, start + 6)) {
                        if (matchesRLSyllable(raw.substring(start, end), syllable, pairs))
                            next[end] = true
                    }
                }
                reachable = next
            }
            return reachable[raw.length]
        }

        private fun fuzzyInputKey(raw: String, pairs: Set<String> = ALL_FUZZY_PAIRS): String {
            // Broad index gives a bounded shortlist. The query filters this shortlist with
            // the selected pairs; explicit syllable boundaries are checked separately.
            var v = compact(raw)
            if ("zh_z" in pairs) v = v.replace("zh", "z")
            if ("ch_c" in pairs) v = v.replace("ch", "c")
            if ("sh_s" in pairs) v = v.replace("sh", "s")
            if ("n_l" in pairs) v = v.replace(N_BEFORE_VOWEL, "l")
            // Deliberately broad ONLY for the shortlist; matchesRL validates every
            // actual change against syllable boundaries and valid full syllables.
            if ("r_l" in pairs) v = v.replace('r', 'l')
            if ("f_h" in pairs) v = v.replace("f", "h")
            if ("an_ang" in pairs) v = v.replace("ang", "an")
            if ("en_eng" in pairs) v = v.replace("eng", "en")
            if ("in_ing" in pairs) v = v.replace("ing", "in")
            return v
        }
    }
}
