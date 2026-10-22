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
}
interface EnglishLookup { fun complete(prefix: String, limit: Int): List<String> }
