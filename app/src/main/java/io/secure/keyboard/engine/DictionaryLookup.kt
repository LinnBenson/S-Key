package io.secure.keyboard.engine

interface WubiLookup {
    val entryCount: Int
    fun exactCandidateCount(code: String): Int
    fun exact(code: String, limit: Int): List<Candidate>
    fun prefix(code: String, limit: Int): List<Candidate>
}
interface PinyinLookup {
    val entryCount: Int
    fun index(kind: String): Map<String, List<DictionaryEngine.Entry>>
    fun lookup(kind: String, key: String, limit: Int): List<DictionaryEngine.Entry> = index(kind)[key].orEmpty().take(limit)
    fun lookupPage(kind: String, key: String, offset: Int, limit: Int): List<DictionaryEngine.Entry> = index(kind)[key].orEmpty().drop(offset).take(limit)
    fun predictions(context: String): List<String> = emptyList()
    fun predictions(context: String, signal: android.os.CancellationSignal?): List<String> = predictions(context)
}
interface EnglishLookup { fun complete(prefix: String, limit: Int): List<String> }
