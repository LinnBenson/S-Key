package io.secure.keyboard.engine

import android.content.Context

/** Read-only packaged tables shared across service lifecycles. Call load() off the UI thread. */
class OfflineEngine(context: Context) {
    private val assets = context.applicationContext.assets
    private val repository = io.secure.keyboard.dictionaries.DictionaryRepository(context)
    private val prefs = context.applicationContext.getSharedPreferences("settings",0)
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
            val previous = compiled
            compiled = replacement
            loadedPath = file.path
            loadedVersion = version
            val parsed = replacement.engine
            previous?.close()
            repository.cleanupSnapshots(file)
            packagedEmoji = assets.open("dictionaries/emoji_keywords.tsv").reader(Charsets.UTF_8).use { EmojiLexicon(it) }
            packagedDictionary = parsed
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
    ): List<Candidate> = withDictionary { it.candidates(code, fuzzyPinyin, limit, fuzzyPairs, allExactWubi = true) }

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
        private val initializationLock = Any()
        // Only immutable bundled dictionary tables live here. Never editor text, a query,
        // candidates, user frequencies, imported words, Context, or a learning/history model.
        @Volatile private var packagedEmoji: EmojiLexicon? = null
        @Volatile private var packagedDictionary: DictionaryEngine? = null
    }
}
