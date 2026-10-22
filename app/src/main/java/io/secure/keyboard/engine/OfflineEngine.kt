package io.secure.keyboard.engine

import android.content.Context

/** Read-only packaged tables shared across service lifecycles. Call load() off the UI thread. */
class OfflineEngine(context: Context) {
    private val app = context.applicationContext
    private val assets = app.assets
    // Repository migration/cleanup can copy SQLite assets; defer it to load()'s worker.
    private val repository by lazy { io.secure.keyboard.dictionaries.DictionaryRepository(app) }
    private val prefs = app.getSharedPreferences("settings",0)
    val revision: String get() = prefs.getString("builtin_dictionary_revision", "default-v2")!!
    val isLoaded: Boolean
        get() = packagedDictionary != null

    fun load() {
        synchronized(initializationLock) {
            val version = repository.identity + revision
            if (loadedVersion == version && packagedDictionary != null) return
                // A single process-wide parser prevents overlapping startup work when Android
            // destroys/recreates the IME while an earlier service is still loading assets.
            val file = repository.activeFile() ?: repository.defaultRuntime()
            if (loadedPath == file.path && packagedDictionary != null) { loadedVersion=version; return }
            val replacement = io.secure.keyboard.dictionaries.CompiledDictionary(file)
            var fallback: io.secure.keyboard.dictionaries.CompiledDictionary? = null
            try {
                fallback = if (!replacement.scopedPinyin)
                    io.secure.keyboard.dictionaries.CompiledDictionary(repository.defaultRuntime()) else null
                val mixed = fallback?.let { DictionaryEngine(replacement.wubi, it.mixedPinyin, replacement.english) }
                    ?: replacement.mixedEngine
                // Prepare every fallible resource before publishing or closing the old snapshot.
                val emoji = packagedEmoji ?: assets.open("dictionaries/emoji_keywords.tsv")
                    .reader(Charsets.UTF_8).use { EmojiLexicon(it) }
                val previous = compiled
                val previousFallback = legacyFallback
                compiled = replacement
                legacyFallback = fallback
                mixedDictionary = mixed
                packagedEmoji = emoji
                packagedDictionary = replacement.engine
                loadedPath = file.path
                loadedVersion = version
                previous?.close()
                previousFallback?.close()
            } catch (error: Throwable) {
                // Once published, ownership belongs to the process-wide snapshot.
                if (compiled !== replacement) { replacement.close(); fallback?.close() }
                throw error
            }
            repository.cleanupSnapshots(file)
        }
    }

    fun appendEmoji(words: List<String>): List<String> = packagedEmoji?.appendTo(words) ?: words

    fun exactWubi(code: String, limit: Int = 40): List<Candidate> =
        withDictionary { it.exactWubi(code, limit) }

    fun candidates(
        code: String,
        fuzzyPinyin: Boolean = true,
        limit: Int = 40,
        fuzzyPairs: Set<String> = DictionaryEngine.DEFAULT_FUZZY_PAIRS,
        mode: ChineseInputMode = ChineseInputMode.MIXED,
    ): List<Candidate> = withDictionary { (if(mode==ChineseInputMode.MIXED) checkNotNull(mixedDictionary) else it).candidates(code, fuzzyPinyin, limit, fuzzyPairs, allExactWubi = true, mode = mode) }

    fun decodePinyin(raw: String, context: String, custom: List<Pair<String, String>>, fuzzy: Boolean, pairs: Set<String>, cancelled: () -> Boolean = { false }) =
        withDictionary { it.decodePinyin(raw, context, custom, fuzzy, pairs, cancelled) }

    fun completeEnglish(prefix: String, limit: Int = 8): List<Candidate> =
        withDictionary { it.completeEnglish(prefix, limit) }

    private fun <T> withDictionary(block: (DictionaryEngine)->T): T = synchronized(initializationLock) {
        load()
        block(checkNotNull(packagedDictionary))
    }
    companion object {
        private var loadedVersion: String? = null
        private var loadedPath: String? = null
        private var compiled: io.secure.keyboard.dictionaries.CompiledDictionary? = null
        private var legacyFallback: io.secure.keyboard.dictionaries.CompiledDictionary? = null
        private var mixedDictionary: DictionaryEngine? = null
        private val initializationLock = Any()
        // Only immutable bundled dictionary tables live here. Never editor text, a query,
        // candidates, user frequencies, imported words, Context, or a learning/history model.
        @Volatile private var packagedEmoji: EmojiLexicon? = null
        @Volatile private var packagedDictionary: DictionaryEngine? = null
    }
}
