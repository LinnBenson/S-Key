package io.secure.keyboard.engine

import android.content.Context
import java.io.FileInputStream
import java.nio.channels.FileChannel

/** Read-only packaged tables shared across service lifecycles. Call load() off the UI thread. */
class OfflineEngine(context: Context) {
    private val assets = context.applicationContext.assets
    val isLoaded: Boolean
        get() = packagedDictionary != null

    fun load() {
        if (packagedDictionary != null) return
        synchronized(initializationLock) {
            if (packagedDictionary != null) return
            // A single process-wide parser prevents overlapping startup work when Android
            // destroys/recreates the IME while an earlier service is still loading assets.
            // .skwb is stored uncompressed inside the signed APK. Map its asset byte range
            // directly; do not copy it to heap, extract a disk cache, or deserialize all words.
            val wubi = assets.openFd("dictionaries/compact-wubi.skwb").use { descriptor ->
                require(descriptor.length in 64..Int.MAX_VALUE.toLong()) { "Invalid Wubi asset size" }
                FileInputStream(descriptor.fileDescriptor).use { stream ->
                    CompactWubiTable(stream.channel.map(
                        FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.length,
                    ))
                }
            }
            val parsed = assets.open("dictionaries/pinyin_simp.dict.yaml").reader(Charsets.UTF_8).use { p ->
                assets.open("dictionaries/english_frequency.tsv").reader(Charsets.UTF_8).use { e ->
                    DictionaryEngine(wubi, p, EnglishLexicon(e))
                }
            }
            packagedDictionary = parsed
        }
    }

    fun exactWubi(code: String, limit: Int = 40): List<Candidate> =
        packagedDictionary?.exactWubi(code, limit).orEmpty()

    fun candidates(
        code: String,
        fuzzyPinyin: Boolean = true,
        limit: Int = 40,
        fuzzyPairs: Set<String> = DictionaryEngine.DEFAULT_FUZZY_PAIRS,
    ): List<Candidate> = packagedDictionary?.candidates(code, fuzzyPinyin, limit, fuzzyPairs, allExactWubi = true).orEmpty()

    fun completeEnglish(prefix: String, limit: Int = 8): List<Candidate> =
        packagedDictionary?.completeEnglish(prefix, limit).orEmpty()

    companion object {
        private val initializationLock = Any()
        // Only immutable bundled dictionary tables live here. Never editor text, a query,
        // candidates, user frequencies, imported words, Context, or a learning/history model.
        @Volatile private var packagedDictionary: DictionaryEngine? = null
    }
}
