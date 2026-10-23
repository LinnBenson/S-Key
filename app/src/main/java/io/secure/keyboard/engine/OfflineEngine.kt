package io.secure.keyboard.engine

import android.content.Context

/** Read-only packaged tables shared across service lifecycles. Call load() off the UI thread. */
class OfflineEngine(context: Context) {
    private val app = context.applicationContext
    private val shortcuts = io.secure.keyboard.data.EmojiShortcutStore(app)
    // Repository initialization/cleanup can copy SQLite assets; defer it to load()'s worker.
    private val repository by lazy { io.secure.keyboard.dictionaries.DictionaryRepository(app) }
    private val prefs = app.getSharedPreferences("settings",0)
    val revision: String get() = prefs.getString("builtin_dictionary_revision", "default-v2")!! + ":emoji:" + shortcuts.revision
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
            try {
                require(replacement.scopedPinyin) { "词库索引格式不受支持，请重新编译" }
                shortcuts.lexicon()
                val previous = compiled
                compiled = replacement
                mixedDictionary = replacement.mixedEngine
                packagedDictionary = replacement.engine
                loadedPath = file.path
                loadedIdentity = repository.identity
                loadedVersion = version
                previous?.close()
            } catch (error: Throwable) {
                if (compiled !== replacement) replacement.close()
                throw error
            }
            repository.cleanupSnapshots(file)
        }
    }

    fun appendPinyinEmoji(options: Sequence<PinyinDecoder.Option>): Sequence<PinyinDecoder.Option> =
        shortcuts.lexicon().appendToOptions(options)

    fun appendEmojiKeyword(options: Sequence<PinyinDecoder.Option>, keyword: String): Sequence<PinyinDecoder.Option> =
        shortcuts.lexicon().appendForKeyword(options, keyword)

    fun appendEmoji(words: List<String>): List<String> = shortcuts.lexicon().appendTo(words)

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

    internal fun boundedCandidates(code: String, fuzzy: Boolean, limit: Int, pairs: Set<String>, mode: ChineseInputMode, cancelled: () -> Boolean = { false }) =
        withDictionary { (if(mode==ChineseInputMode.MIXED) checkNotNull(mixedDictionary) else it).candidates(code,fuzzy,limit,pairs,mode=mode,cancelled=cancelled) }
    /** Each SQL page is materialized under the snapshot lock; no cursor survives between pages. */
    internal fun wordPages(kind: String, query: String, expectedRevision: String): Sequence<String> = sequence {
        var offset = 0
        while (true) {
            val page = withDictionary {
                check(revision == expectedRevision) { "词库已更新" }
                when(kind) {
                    "wubi" -> checkNotNull(compiled).exactWubiPage(query,offset,40)
                    "initials" -> checkNotNull(compiled).initialsPage(query,offset,40)
                    "pinyin" -> checkNotNull(compiled).pinyinPage(query,offset,40)
                    "priority_pinyin" -> checkNotNull(compiled).pinyinPage(query,offset,40,priorityOnly=true)
                    "english" -> checkNotNull(compiled).englishPage(query,offset,40)
                    else -> error("Unknown candidate source")
                }
            }
            yieldAll(page)
            if(page.size < 40) break
            offset += page.size
        }
    }
    internal fun decodePinyinWithoutInitials(raw: String, context: String, custom: List<Pair<String,String>>, fuzzy: Boolean, pairs: Set<String>, predictionSignal: android.os.CancellationSignal? = null, cancelled: () -> Boolean) =
        withDictionary { it.decodePinyin(raw,context,custom,fuzzy,pairs,cancelled,includeInitials=false,predictionSignal=predictionSignal) }

    private fun <T> withDictionary(block: (DictionaryEngine)->T): T = synchronized(initializationLock) {
        load()
        block(checkNotNull(packagedDictionary))
    }
    companion object {
        private var loadedVersion: String? = null
        private var loadedPath: String? = null
        private var compiled: io.secure.keyboard.dictionaries.CompiledDictionary? = null
        private var loadedIdentity: String? = null
        private var mixedDictionary: DictionaryEngine? = null
        /** Called after publication, outside the repository lock to avoid lock inversion. */
        internal fun releaseSnapshot(identity: String) = synchronized(initializationLock) {
            if(loadedIdentity == identity) {
                compiled?.close()
                compiled = null
                packagedDictionary = null
                mixedDictionary = null
                loadedPath = null
                loadedVersion = null
                loadedIdentity = null
            }
        }
        private val initializationLock = Any()
        // Process-wide read-only SQLite snapshot handles, including compiled user libraries.
        // No editor text, query, candidate list, Context, or learned input history is retained.
        @Volatile private var packagedDictionary: DictionaryEngine? = null
    }
}
