package io.secure.keyboard.engine

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class EnglishCustomCandidateRankingTest {
    private data class Entry(val text: String, val code: String)

    private fun merge(query: String, baseline: List<String>, vararg custom: Entry): List<String> =
        EnglishCustomCandidateRanking.merge(query, baseline, custom.toList(), { it.code }, { it.text })

    @Test
    fun firstBuiltinExactBlockRemainingBuiltinsThenPrefixBlockAreStable() {
        assertEquals(listOf("hello", "Exact One", "Exact Two", "help", "held", "Prefix One", "Prefix Two"),
            merge("He", listOf("hello", "help", "held"),
                Entry("Prefix One", "HELLO"), Entry("Exact One", "he"),
                Entry("Other", "hi"), Entry("Exact Two", "HE"), Entry("Prefix Two", "help")))
    }

    @Test
    fun absentBuiltinsStartWithExactMatchesFollowedByPrefixes() {
        assertEquals(listOf("Exact One", "Exact Two", "Prefix"),
            merge("qqqqq", emptyList(), Entry("Prefix", "qqqqqa"),
                Entry("Exact One", "QQQQQ"), Entry("Exact Two", "qqqqq")))
        assertEquals(listOf("Prefix"), merge("q", emptyList(), Entry("Prefix", "qq")))
    }

    @Test
    fun exactDuplicatesKeepTheirHighestPriorityOccurrence() {
        assertEquals(listOf("hello", "held", "Exact", "help", "Prefix"),
            merge("he", listOf("hello", "help", "held", "hello"),
                Entry("Prefix", "hello"), Entry("held", "he"), Entry("hello", "he"),
                Entry("Exact", "he"), Entry("Exact", "HE"), Entry("help", "hey"),
                Entry("Prefix", "hell")))
    }

    @Test
    fun exactMatchPromotesPrefixDuplicateRegardlessOfSavedOrder() {
        assertEquals(listOf("hello", "Same", "help", "Other"),
            merge("he", listOf("hello", "help"), Entry("Same", "hello"),
                Entry("Other", "help"), Entry("Same", "he")))
    }

    @Test
    fun customOutputCasingIsLiteralAndCaseVariantsAreDistinct() {
        assertEquals(listOf("HELLO", "eBay", "Hello", "hello", "HeLLo", "HELP"),
            merge("HE", listOf("HELLO", "HELP"), Entry("eBay", "he"),
                Entry("Hello", "he"), Entry("hello", "he"), Entry("HeLLo", "he")))
    }

    @Test
    fun unicodeEqualityIsExactAndEnglishDoesNotApplyChineseExtensionBPartition() {
        val supplementary = "\uD840\uDC00 phrase \uD83D\uDE00"
        val composed = "Caf\u00e9"
        val decomposed = "Cafe\u0301"
        assertEquals(listOf("hello", supplementary, composed, decomposed, "help"),
            merge("he", listOf("hello", "help"), Entry(supplementary, "he"),
                Entry(composed, "he"), Entry(decomposed, "he"), Entry(supplementary, "help")))
    }

    @Test
    fun codeMatchingIsLocaleIndependent() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(listOf("iPhone"), merge("I", emptyList(), Entry("iPhone", "i")))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun validOneFourFiveAndFortyLetterCodesHaveNoFourCodeRestriction() {
        for (length in listOf(1, 4, 5, 40)) {
            val code = "q".repeat(length)
            assertEquals(listOf("Literal $length"),
                merge(code, emptyList(), Entry("Literal $length", code.uppercase(Locale.ROOT))))
        }
    }

    @Test
    fun blankInvalidAndOverlongQueriesNeverExpandPersonalContent() {
        for (query in listOf("", " ", "h e", "h'e", "h’e", "h_", "h1", "hü", "q".repeat(41))) {
            assertEquals(listOf("builtin"),
                merge(query, listOf("builtin", "builtin"), Entry("Private", "hello")))
        }
    }

    @Test
    fun chineseNormalizationAndInvalidCustomCodesCannotLeakIntoEnglishMatches() {
        assertEquals(listOf("Valid"), merge("n", emptyList(),
            Entry("Spaced", " n "), Entry("Apostrophe", "n'i"), Entry("Umlaut", "nü"),
            Entry("Digit", "n1"), Entry("Too long", "n".repeat(41)), Entry("Valid", "ni")))
        assertTrue(merge("nv", emptyList(), Entry("Umlaut", "nü")).isEmpty())
    }

    @Test
    fun allTenThousandCustomPrefixesRemainReachableAfterTwelveBuiltins() {
        val baseline = (1..12).map { "Builtin $it" }
        val custom = (1..10_000).map { Entry("Custom $it", "hello") }
        val actual = EnglishCustomCandidateRanking.merge("he", baseline, custom, { it.code }, { it.text })
        assertEquals(10_012, actual.size)
        assertEquals(baseline, actual.take(12))
        assertEquals(custom.map { it.text }, actual.drop(12))
    }

    @Test
    fun editsAndDeletesDoNotRetainOrLearnPreviousMatches() {
        val rows = mutableListOf(Entry("Exact", "he"), Entry("Prefix", "hello"))
        fun current() = EnglishCustomCandidateRanking.merge("he", listOf("help"), rows,
            { it.code }, { it.text })
        val original = current()
        repeat(5) { assertEquals(original, current()) }
        rows[0] = Entry("Edited", "xx")
        assertEquals(listOf("help", "Prefix"), current())
        rows.clear()
        assertEquals(listOf("help"), current())
        assertEquals(listOf("help", "Exact", "Prefix"), original)
    }
}
