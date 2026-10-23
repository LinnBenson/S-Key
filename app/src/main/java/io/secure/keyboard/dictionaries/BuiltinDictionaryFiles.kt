package io.secure.keyboard.dictionaries

/** The six current built-in files share protection and priority rules. */
object BuiltinDictionaryFiles {
    private val base = mapOf("skey_wubi86.tsv" to "wubi", "skey_pinyin.tsv" to "pinyin", "skey_english.tsv" to "english")
    private val high = mapOf("skey_wubi86_high.tsv" to "wubi", "skey_pinyin_high.tsv" to "pinyin", "skey_english_high.tsv" to "english")
    fun isProtected(filename: String) = filename in base || filename in high
    fun isHighPriority(filename: String, kind: String) = high[filename] == kind
}
