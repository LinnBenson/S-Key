package io.secure.keyboard.data

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class DictionaryKindTest {
    @Test
    fun englishAcceptsOneFourFiveAndFortyAsciiLettersInEitherCase() {
        for (size in listOf(1, 4, 5, 40)) {
            DictionaryKind.ENGLISH.validateEntry("Literal Output", "a".repeat(size))
            DictionaryKind.ENGLISH.validateEntry("Literal Output", "Z".repeat(size))
        }
    }

    @Test
    fun englishRejectsEmptyOversizedAndNonAsciiCodesWithoutChineseNormalization() {
        for (code in listOf("", "a".repeat(41), " abc", "abc ", "ab cd", "a'b", "a-b", "code1",
                "ü", "İ", "ı", "ß", "中文", "ａｂ", "a\tb", "a\nb", "a\rb")) {
            assertRejected("Output", code)
        }
    }

    @Test
    fun englishAllowsLiteralUnicodePunctuationCapitalizationAndInternalSpaces() {
        for (word in listOf("OpenAI", "iPhone", "Hello, World!", "A  B", "中文输出", "𠀀𠮷",
                "é", "e\u0301", "😀", "a\u00a0b")) {
            DictionaryKind.ENGLISH.validateEntry(word, "LITERAL")
        }
    }

    @Test
    fun englishRejectsBlankBoundaryWhitespaceAndTsvDelimiters() {
        for (word in listOf("", " ", " leading", "trailing ", "\u00a0leading", "trailing\u2003",
                "\nleading", "trailing\r", "inner\ttab", "inner\nline", "inner\rline")) {
            assertRejected(word, "code")
        }
    }

    @Test
    fun chineseLegacyValidationDoesNotGainEnglishRestrictions() {
        for (code in listOf("a".repeat(100), "NI HAO", "n'i", "NÜ", "中文", "123", " code ")) {
            DictionaryKind.CHINESE.validateEntry("  保留原文  ", code)
        }
        for ((word, code) in listOf("" to "a", "word" to "", "word" to " ",
                "tab\there" to "a", "word" to "a\tb", "word" to "a\nb", "word" to "a\rb")) {
            try {
                DictionaryKind.CHINESE.validateEntry(word, code)
                fail("Chinese TSV and nonblank validation must stay in place")
            } catch (_: IllegalArgumentException) {}
        }
    }

    @Test
    fun asciiValidationDoesNotDependOnDefaultLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            DictionaryKind.ENGLISH.validateEntry("iPhone", "IPHONe")
            assertRejected("iPhone", "İPHONE")
        } finally {
            Locale.setDefault(original)
        }
    }

    private fun assertRejected(word: String, reading: String) {
        try {
            DictionaryKind.ENGLISH.validateEntry(word, reading)
            fail("Invalid English entry should be rejected: $word / $reading")
        } catch (_: IllegalArgumentException) {}
    }
}
