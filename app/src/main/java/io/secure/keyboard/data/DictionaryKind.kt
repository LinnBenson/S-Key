package io.secure.keyboard.data

/** Independent user dictionaries stored in the same private atomic content file. */
enum class DictionaryKind(internal val storageKey: String) {
    CHINESE("dictionary"),
    ENGLISH("englishDictionary");

    /** Pure validation shared by manual changes and every imported row. */
    internal fun validateEntry(word: String, reading: String) {
        if (this == CHINESE) {
            // Keep legacy Chinese validation, including long and non-ASCII readings, unchanged.
            require(
                word.isNotBlank() &&
                    reading.isNotBlank() &&
                    listOf(word, reading).none { value ->
                        value.any { it == '\t' || it == '\n' || it == '\r' }
                    }
            ) {
                "词语和拼音／编码不能为空，且不能包含制表符或换行"
            }
            return
        }
        require(reading.length in 1..40 && reading.all { it in 'a'..'z' || it in 'A'..'Z' }) {
            "英文编码须为 1–40 个英文字母（A–Z 或 a–z），不能包含空格或其他字符"
        }
        require(word.isNotBlank() && word.none { it == '\t' || it == '\n' || it == '\r' }) {
            "英文词条内容不能为空，且不能包含制表符或换行"
        }
        require(!word.first().isWhitespace() && !word.last().isWhitespace()) {
            "英文词条内容的开头和结尾不能包含空白；中间可以保留空格"
        }
    }
}
