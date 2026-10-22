package io.secure.keyboard.data

import android.content.Context

/** Language-specific alternate characters, shared by preview, corner hints and long press. */
internal class LetterSymbolStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun key(chinese: Boolean, letter: Char) = "letter_symbols_${if (chinese) "zh" else "en"}_$letter"
    fun symbols(chinese: Boolean, letter: Char): String =
        prefs.getString(key(chinese, letter), null)?.takeIf { valid(it) } ?: defaults(chinese, letter)
    fun save(chinese: Boolean, letter: Char, value: String) {
        require(letter in 'a'..'z' && valid(value)) { "最多填写 8 个字符，不能包含空白或换行；留空可取消此键长按符号" }
        prefs.edit().putString(key(chinese, letter), value).apply()
    }
    fun restore(chinese: Boolean) {
        prefs.edit().apply { ('a'..'z').forEach { remove(key(chinese, it)) } }.apply()
    }
    private fun valid(value: String) = value.codePointCount(0, value.length) <= 8 &&
        value.none { it.isWhitespace() || it.isISOControl() } &&
        value.codePoints().toArray().none { it in 0xD800..0xDFFF }
    fun defaults(chinese: Boolean, c: Char): String =
        (if (chinese) chineseDefaults else englishDefaults)[c] ?: ""

    companion object {
        private val digitStyles = listOf(
            "1①❶１𝟭Ⅰ", "2②❷２𝟮Ⅱ", "3③❸３𝟯Ⅲ", "4④❹４𝟰Ⅳ", "5⑤❺５𝟱Ⅴ",
            "6⑥❻６𝟲Ⅵ", "7⑦❼７𝟳Ⅶ", "8⑧❽８𝟴Ⅷ", "9⑨❾９𝟵Ⅸ", "0⓪⓿０𝟬",
        )
        private fun defaultSymbols(chinese: Boolean): Map<Char, String> {
            val rows = io.secure.keyboard.ui.SymbolKeyLayout.rows(chinese, false)
            val middle = rows[1].map { it.toString() }.toMutableList()
            // Nine letter keys: put both parentheses on K and keep slash on L.
            middle[7] += middle.removeAt(8)
            return ("qwertyuiop".toList().zip(digitStyles) +
                "asdfghjkl".toList().zip(middle) +
                "zxcvbnm".toList().zip(rows[2].map { it.toString() })).toMap()
        }
        private val chineseDefaults = defaultSymbols(true)
        private val englishDefaults = defaultSymbols(false)
    }
}
