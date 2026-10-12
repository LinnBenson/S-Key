package io.secure.keyboard.engine

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class BuiltinExactDictionaryTest {
    private val text = "由 Linn Benson 开发的输入法"
    private fun engine() = DictionaryEngine("...\n".reader(), "...\n".reader(),
        EnglishLexicon("hello\t10\nuseskeyboard\t5\n".reader()))

    @Test
    fun completeCodeIsCaseInsensitiveAndOutputIsLiteral() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            for (code in listOf("useskey", "USESKEY", "UseSkey")) {
                assertEquals(text, BuiltinExactDictionary.lookup(code))
                assertEquals(text, engine().candidates(code).first().text)
                assertFalse(engine().completeEnglish(code).any { it.text == text })
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun prefixesSuffixesAndSeparatedCodesNeverExposeTheSystemEntry() {
        val queries = (0 until "useskey".length).map { "useskey".take(it) } +
            listOf("useskeya", " useskey", "useskey ", "use'skey", "use skey", "uſeskey")
        val engine = engine()
        for (query in queries) {
            assertNull(query, BuiltinExactDictionary.lookup(query))
            assertFalse(query, engine.candidates(query).any { it.text == text })
            assertFalse(query, engine.completeEnglish(query).any { it.text == text })
        }
    }

    @Test
    fun builtinIsNotWubiAutoCommitDataAndRespectsLookupLimits() {
        val engine = engine()
        assertTrue(engine.exactWubi("useskey").isEmpty())
        assertTrue(engine.candidates("useskey", limit = 0).isEmpty())
        assertEquals(listOf(text), engine.candidates("useskey", limit = 1).map { it.text })
    }

    @Test
    fun userExactMatchesRemainSecondAndClearingThemCannotRemoveBuiltin() {
        val engine = engine()
        val user = listOf("用户词" to "useskey")
        val chinese = engine.candidates("useskey").map { it.text }
        assertEquals(listOf(text, "用户词"),
            CustomCandidateRanking.merge("useskey", chinese, user, { it.second }, { it.first }))
        assertEquals(chinese,
            CustomCandidateRanking.merge("useskey", chinese, emptyList<Pair<String, String>>(),
                { it.second }, { it.first }))
    }

    @Test
    fun englishCompletionNeverIncludesChineseBuiltinAndKeepsItsExistingCaseBehavior() {
        assertEquals(listOf("useskeyboard"), engine().completeEnglish("useskey").map { it.text })
        assertEquals(listOf("USESKEYBOARD"), engine().completeEnglish("USESKEY").map { it.text })
        assertEquals(listOf("Hello"), engine().completeEnglish("Hel").map { it.text })
        assertEquals(Source.BUILTIN, engine().candidates("useskey").first().source)
        val noLexicon = DictionaryEngine("...\n".reader(), "...\n".reader())
        assertTrue(noLexicon.completeEnglish("useskey").isEmpty())
        assertEquals(text, noLexicon.candidates("useskey").single().text)
    }
}
