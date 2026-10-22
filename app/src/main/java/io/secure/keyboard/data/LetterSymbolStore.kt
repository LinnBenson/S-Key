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
    fun defaults(chinese: Boolean, c: Char): String {
        val idx = "qwertyuiop".indexOf(c)
        if (idx >= 0) return (idx + 1).let { if (it == 10) "0" else "$it" }
        return (if (chinese) chineseDefaults else englishDefaults)[c] ?: ""
    }
    companion object {
        private val chineseDefaults = mapOf(
                    'a' to "！",
                    's' to "？",
                    'd' to "、",
                    'f' to "；",
                    'g' to "：",
                    'h' to "（",
                    'j' to "）",
                    'k' to "“”",
                    'l' to "‘’",
                    'z' to "～",
                    'x' to "—",
                    'c' to "…",
                    'v' to "《》",
                    'b' to "【】",
                    'n' to "@",
                    'm' to "&",
                )
        private val englishDefaults = mapOf(
                    'a' to "@",
                    's' to "#",
                    'd' to "$",
                    'f' to "%",
                    'g' to "&",
                    'h' to "*",
                    'j' to "(",
                    'k' to ")",
                    'l' to "'",
                    'z' to "-",
                    'x' to "_",
                    'c' to "/",
                    'v' to ":",
                    'b' to ";",
                    'n' to "!",
                    'm' to "?",
                )
    }
}
