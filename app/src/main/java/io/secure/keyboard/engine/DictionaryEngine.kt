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
    private val compactWubi: WubiLookup?,
    private val indexedPinyin: PinyinLookup? = null,
    private val indexedEnglish: EnglishLookup? = null,
    private val initials: (String)->List<Entry> = { emptyList() },
) {
    /** Text constructor retained for reference validation and small test fixtures. */
    constructor(wubi: Reader, pinyin: Reader, englishLexicon: EnglishLexicon? = null) :
        this(wubi, pinyin, englishLexicon, null)

    data class Entry(val text: String, val code: String, val weight: Long, val priority: Long = Long.MAX_VALUE)

    constructor(wubi: WubiLookup, pinyin: PinyinLookup, english: EnglishLookup, initials: (String)->List<Entry> = { emptyList() }) :
        this(null, java.io.StringReader("...\n"), null, wubi, pinyin, english, initials)

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
        pinyinEntryCount = indexedPinyin?.entryCount ?: pinyinEntries.size
        pinyinExact = indexedPinyin?.index("exact") ?: groupEntries(pinyinEntries) { compact(it.code) }
        fuzzyExact = indexedPinyin?.index("fuzzy") ?: groupEntries(pinyinEntries) { fuzzyKey(it.code) }
        syllables = indexedPinyin?.index("syllable") ?:
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

    private fun matchingPinyin(kind: String, key: String, limit: Int, cancelled: () -> Boolean = { false }, predicate: (Entry)->Boolean): List<Entry> {
        if (cancelled() || Thread.currentThread().isInterrupted) return emptyList()
        if(indexedPinyin==null) return (if(kind=="exact") pinyinExact[key] else fuzzyExact[key]).orEmpty().asSequence().takeWhile { !cancelled() && !Thread.currentThread().isInterrupted }.filter(predicate).distinctBy { it.text }.take(limit).toList()
        val matches=ArrayList<Entry>()
        val seen=HashSet<String>()
        var offset=0
        while(matches.size<limit && !cancelled() && !Thread.currentThread().isInterrupted) {
            val page=indexedPinyin.lookupPage(kind,key,offset,40)
            for(entry in page) {
                if (cancelled() || Thread.currentThread().isInterrupted) return emptyList()
                if(predicate(entry) && seen.add(entry.text)) { matches.add(entry); if(matches.size==limit) break }
            }
            if(page.size<40) break
            offset+=page.size
        }
        return matches
    }

    fun candidates(
        code: String,
        fuzzyPinyin: Boolean = true,
        limit: Int = 40,
        fuzzyPairs: Set<String> = DEFAULT_FUZZY_PAIRS,
        allExactWubi: Boolean = false,
        mode: ChineseInputMode = ChineseInputMode.MIXED,
        cancelled: () -> Boolean = { false },
        separateMixedGroups: Boolean = false,
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
        val resultLimit = if (mode != ChineseInputMode.PINYIN && allExactWubi && key.length == 4) {
            maxOf(limit, compactWubi?.exactCandidateCount(key) ?: wubiExact[key].orEmpty().size)
        } else limit
        var groupLimit = resultLimit
        val separateGroups = separateMixedGroups && mode == ChineseInputMode.MIXED
        val result = LinkedHashMap<String, Candidate>()
        BuiltinExactDictionary.lookup(raw)?.let {
            result[it] = Candidate(it, key, Source.BUILTIN)
        }
        fun add(entries: List<Entry>?, source: Source) {
            entries?.forEach {
                if (result.size < groupLimit)
                    result.putIfAbsent(it.text, Candidate(it.text, it.code, source))
            }
        }
        if (mode != ChineseInputMode.PINYIN) {
            if (compactWubi == null) add(wubiExact[key], Source.WUBI)
            else compactWubi.exact(key, resultLimit).forEach { result.putIfAbsent(it.text, it) }
        }
        if (mode != ChineseInputMode.WUBI) {
            if (separateGroups) groupLimit = result.size + limit
            val segmented = raw.contains('\'') || raw.contains(' ')
            val syllableCode = raw.split(' ', '\'').filter(String::isNotEmpty).joinToString(" ")
            val exactPinyin = matchingPinyin("exact",key,resultLimit,cancelled) { !segmented || it.code == syllableCode }
            add(exactPinyin, Source.PINYIN)
            if (fuzzyPinyin && fuzzyPairs.isNotEmpty()) {
                if (separateGroups) groupLimit = result.size + limit
                val exactTexts = exactPinyin.map { it.text }.toSet()
                // Preserve the existing pairs' matching rules. The optional r/l pair has a
                // stricter, syllable-aware check below so it cannot turn a final er into el.
                val selectedPairs = fuzzyPairs - "r_l"
                val selectedKey = fuzzyInputKey(raw, selectedPairs)
                val selectedSyllables =
                    if (segmented) syllableCode.split(' ').map { fuzzySyllable(it, selectedPairs) }
                    else emptyList()
                add(
                    matchingPinyin("fuzzy",fuzzyInputKey(raw),resultLimit,cancelled) {
                        compact(it.code) != key && it.text !in exactTexts && ((fuzzyInputKey(it.code, selectedPairs) == selectedKey &&
                            (!segmented ||
                                it.code.split(' ').map { part -> fuzzySyllable(part, selectedPairs) } ==
                                    selectedSyllables)) ||
                            ("r_l" in fuzzyPairs && matchesRL(raw, it.code, selectedPairs)))
                    },
                    Source.PINYIN,
                )
            }
            if (separateGroups) groupLimit = result.size + limit
            if (fuzzyPinyin && result.size < groupLimit && !cancelled()) add(extendedFuzzyEntries(raw, fuzzyPairs, resultLimit, cancelled), Source.PINYIN)
            // A dictionary phrase is preferred; otherwise segment into known words/syllables locally.
            if (exactPinyin.isNullOrEmpty() && key.length >= 4) {
                for (text in composePinyin(raw, cancelled)) if (result.size < groupLimit) {
                    result.putIfAbsent(text, Candidate(text, raw, Source.PINYIN))
                }
            }
        }
        if (separateGroups) groupLimit = result.size + limit
        if (mode != ChineseInputMode.PINYIN) {
            if (compactWubi == null) add(wubiPrefixes[key], Source.WUBI)
            else if (result.size < groupLimit) {
                compactWubi.prefix(key, resultLimit).forEach {
                    if (result.size < groupLimit) result.putIfAbsent(it.text, it)
                }
            }
        }
        return result.values.toList()
    }

    fun decodePinyin(raw: String, context: String, custom: List<Pair<String, String>>, fuzzy: Boolean, pairs: Set<String>, cancelled: () -> Boolean = { false }, includeInitials: Boolean = true, predictionSignal: android.os.CancellationSignal? = null): List<PinyinDecoder.Option> {
        val decoder = PinyinDecoder(cancelled = cancelled, lookup = { token ->
            val segmented = token.contains('\'')
            val syllables = token.split('\'').filter(String::isNotEmpty)
            val exact = matchingPinyin("exact", compact(token), 24, cancelled) {
                !segmented || it.code.split(' ') == syllables
            }
            if (!fuzzy || pairs.isEmpty()) exact else {
                val selected = pairs - "r_l"
                val selectedKey = fuzzyInputKey(token, selected)
                val selectedSyllables = syllables.map { fuzzySyllable(it, selected) }
                val alternatives = matchingPinyin("fuzzy", fuzzyInputKey(token), 24, cancelled) {
                    compact(it.code) != compact(token) && ((fuzzyInputKey(it.code, selected) == selectedKey &&
                        (!segmented || it.code.split(' ').map { part -> fuzzySyllable(part, selected) } == selectedSyllables)) ||
                        ("r_l" in pairs && matchesRL(token, it.code, selected)))
                }
                (exact + alternatives + extendedFuzzyEntries(token, pairs, 24, cancelled)).distinctBy { it.text }
            }
        }, predict = { contextText ->
            indexedPinyin?.predictions(contextText, predictionSignal) ?: pinyinExact.values.asSequence().flatten()
                .filter { it.text.startsWith(contextText) && it.text.length > contextText.length }
                .sortedByDescending { it.weight }.take(12).map { it.text.removePrefix(contextText) }.toList()
        })
        val decoded = decoder.candidates(raw, context, custom)
        if (cancelled()) return emptyList()
        return if(raw.length==1 && includeInitials) {
            val customInitials = custom.filter { it.first == raw }.map { PinyinDecoder.Option(it.second,1) }
            val priorityInitials = indexedPinyin?.lookup("exact",compact(raw),64).orEmpty()
                .filter { it.priority!=Long.MAX_VALUE }.map { PinyinDecoder.Option(it.text,1) }
            (priorityInitials + customInitials + (if(includeInitials) initials(raw) else emptyList()).map { PinyinDecoder.Option(it.text,1) } + decoded).distinctBy { it.text }
        } else decoded
    }

    /** Complete only the supplied current word. Never accepts surrounding editor text. */
    fun completeEnglish(prefix: String, limit: Int = 8): List<Candidate> =
        (indexedEnglish?.complete(prefix, limit) ?: englishLexicon?.complete(prefix, limit))
            ?.map { Candidate(it, prefix, Source.ENGLISH) }
            .orEmpty()

    private data class Path(val text: String, val score: Double, val count: Int)

    private fun composePinyin(raw: String, cancelled: () -> Boolean): List<String> {
        // Explicit apostrophes/spaces force syllable boundaries (xi'an versus xian).
        val tokens = raw.split(' ', '\'').filter(String::isNotEmpty)
        if (tokens.size > 1) {
            var paths = listOf(Path("", 0.0, 0))
            for (token in tokens) {
                if (cancelled() || Thread.currentThread().isInterrupted) return emptyList()
                val entries = if (indexedPinyin != null) indexedPinyin.lookup("syllable", token, 3)
                    else syllables[token]?.take(3).orEmpty()
                if (entries.isEmpty()) return emptyList()
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
            if (cancelled() || Thread.currentThread().isInterrupted) return emptyList()
            if (paths[start].isEmpty()) continue
            for (end in start + 1..minOf(key.length, start + 24)) {
                if (cancelled() || Thread.currentThread().isInterrupted) return emptyList()
                val entries = matchingPinyin("exact", key.substring(start, end), 3, cancelled) { true }
                if (entries.isEmpty()) continue
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

    /** New optional pairs reuse indexed lookups; no table scan or startup index rebuild. */
    private fun extendedFuzzyEntries(raw: String, pairs: Set<String>, limit: Int, cancelled: () -> Boolean): List<Entry> {
        val rules = EXTENDED_FUZZY_RULES.filter { it.id in pairs }
        if (rules.isEmpty()) return emptyList()
        val legacy = pairs - EXTENDED_FUZZY_RULES.map { it.id }.toSet()
        fun legacySyllable(value: String): String {
            val initial = if ("r_l" in legacy && value in R_L_N_SYLLABLES && value.startsWith('r')) "l" + value.drop(1) else value
            return fuzzySyllable(initial, legacy)
        }
        val syllableVariants = HashMap<String, List<String>>()
        fun equivalent(typed: String, stored: String): Boolean =
            syllableVariants.getOrPut(typed) { (extendedVariants(typed, rules, syllableOnly = true) +
                extendedVariants(legacySyllable(typed), rules, syllableOnly = true)).distinct() }.any {
                fuzzySyllable(it, legacy) == fuzzySyllable(stored, legacy) ||
                    ("r_l" in legacy && matchesRLSyllable(it, stored, legacy - "r_l"))
            }
        fun matches(code: String): Boolean {
            val stored = code.split(' ').filter(String::isNotEmpty)
            if (raw.contains(' ') || raw.contains('\'')) {
                val typed = raw.split(' ', '\'').filter(String::isNotEmpty)
                return typed.size == stored.size && typed.indices.all { equivalent(typed[it], stored[it]) }
            }
            var reachable = setOf(0)
            for (syllable in stored) {
                if (cancelled() || Thread.currentThread().isInterrupted) return false
                reachable = reachable.flatMap { start ->
                    (start + 1..minOf(raw.length, start + 6)).filter { end -> equivalent(raw.substring(start, end), syllable) }
                }.toSet()
                if (reachable.isEmpty()) return false
            }
            return raw.length in reachable
        }
        return sequenceOf(raw, fuzzyInputKey(raw, legacy)).distinct()
            .flatMap { extendedVariants(it, rules, syllableOnly = false).asSequence() }
            .map { fuzzyInputKey(it) }.distinct().take(64)
            .takeWhile { !cancelled() && !Thread.currentThread().isInterrupted }
            .flatMap { key -> matchingPinyin("fuzzy", key, limit, cancelled) { matches(it.code) }.asSequence() }
            .distinctBy { it.text }.sortedByDescending { it.weight }.take(limit).toList()
    }

    private fun score(entry: Entry) = kotlin.math.ln(entry.weight.toDouble() + 1.0)

    companion object {
        val DEFAULT_FUZZY_PAIRS: Set<String> =
            setOf("zh_z", "ch_c", "sh_s", "n_l", "f_h", "an_ang", "en_eng", "in_ing")
        private val ALL_FUZZY_PAIRS = DEFAULT_FUZZY_PAIRS + "r_l"
        private data class FuzzyRule(val id: String, val initial: Boolean) {
            val forms = id.split('_')
        }
        private val EXTENDED_FUZZY_RULES = listOf(
            FuzzyRule("l_h", true), FuzzyRule("r_y", true), FuzzyRule("g_k", true),
            FuzzyRule("ai_ei", false), FuzzyRule("ao_ou", false), FuzzyRule("an_ian", false),
            FuzzyRule("en_in", false), FuzzyRule("eng_ing", false),
        )
        private val PINYIN_INITIAL = Regex("^(zh|ch|sh|[bpmfdtnlgkhjqxrzcsyw])")
        private fun extendedVariants(raw: String, rules: List<FuzzyRule>, syllableOnly: Boolean): List<String> {
            val found = linkedSetOf(raw)
            val queue = java.util.ArrayDeque<String>().apply { add(raw) }
            while (queue.isNotEmpty() && found.size < 64) {
                val value = queue.removeFirst()
                for (rule in rules) for ((from, to) in listOf(rule.forms[0] to rule.forms[1], rule.forms[1] to rule.forms[0])) {
                    var at = value.indexOf(from)
                    while (at >= 0 && found.size < 64) {
                        val initial = PINYIN_INITIAL.find(value)?.value.orEmpty()
                        val validPart = if (rule.initial) at == 0 && initial == from
                            else at == initial.length && value.substring(at) == from
                        // Avoid repeatedly expanding an -> ian -> iian in broad phrase probes.
                        val duplicateI = rule.id == "an_ian" && from == "an" && at > 0 && value[at - 1] == 'i'
                        if ((!syllableOnly || validPart) && !duplicateI) {
                            val changed = value.replaceRange(at, at + from.length, to)
                            if (changed.length <= 64 && found.add(changed)) queue.add(changed)
                        }
                        at = value.indexOf(from, at + 1)
                    }
                }
            }
            return found.toList()
        }
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

        fun fuzzyInputKey(raw: String, pairs: Set<String> = ALL_FUZZY_PAIRS): String {
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
