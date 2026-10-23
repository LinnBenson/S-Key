package io.secure.keyboard.engine

import java.io.File
import java.io.StringReader
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class EnglishLexiconTest {
    private fun fixture() = EnglishLexicon(
        ("# static fixture\n" +
            "help\t650\nhello\t520\nhelpful\t430\nhelmet\t390\n" +
            "computer\t470\ncomputers\t420\ncomputing\t410\n" +
            "world\t620\nwould\t670\nwork\t630\n").reader()
    )

    @Test
    fun completionsUseFrequencyInsteadOfAlphabeticalOrder() {
        assertEquals(listOf("help", "hello", "helpful", "helmet"), fixture().complete("he"))
        assertEquals(listOf("would", "work", "world"), fixture().complete("w"))
        assertEquals(listOf("computer", "computers", "computing"), fixture().complete("compu"))
    }

    @Test
    fun onlyStrictExtensionsOfCurrentWordAreReturned() {
        assertEquals(listOf("helpful"), fixture().complete("help"))
        assertTrue(fixture().complete("hello").isEmpty())
        for (input in listOf("", "hello ", "hi he", "he\n", "he!", "he2", "he_", "hé", "中", "can't")) {
            assertTrue(input, fixture().complete(input).isEmpty())
        }
        assertTrue(fixture().complete("h".repeat(41)).isEmpty())
        assertTrue(fixture().complete("zzzz").isEmpty())
    }

    @Test
    fun caseIsPreservedWithoutUsingDefaultLocale() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("Help", fixture().complete("H").first())
            assertEquals("Help", fixture().complete("He").first())
            assertEquals("HELP", fixture().complete("HE").first())
            assertEquals("hElp", fixture().complete("hE").first())
            assertEquals("COMPUTER", fixture().complete("COMPU").first())
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun limitsAndTiesAreDeterministic() {
        val lexicon = EnglishLexicon("bark\t3\nbare\t3\nbase\t4\nbarn\t3\nbar\t9\n".reader())
        assertEquals(listOf("bar", "base", "bare", "bark", "barn"), lexicon.complete("b"))
        assertEquals(listOf("bare", "bark", "barn"), lexicon.complete("bar", Int.MAX_VALUE))
        assertEquals(listOf("bar", "base"), lexicon.complete("b", 2))
        assertTrue(lexicon.complete("b", 0).isEmpty())
        assertTrue(lexicon.complete("b", -1).isEmpty())
        repeat(20) { assertEquals(listOf("bar", "base"), lexicon.complete("b", 2)) }
    }

    @Test
    fun malformedRowsAreRejectedAndDuplicatesUseHighestScore() {
        val lexicon = EnglishLexicon(
            ("hello\t2\nhelp\t4\nhello\t6\nhello\t3\n" +
                "x\t3\nBad\t3\ntwo words\t3\ncan't\t3\nabc1\t3\n" +
                "missing\nbroken\tNaN\nnegative\t-1\nzero\t0\n" +
                "overflow\t2147483648\nextra\t2\t3\n" +
                "h".repeat(41) + "\t5\n").reader()
        )
        assertEquals(2, lexicon.entryCount)
        assertEquals(listOf("hello", "help"), lexicon.complete("h"))
        assertTrue(EnglishLexicon("# empty\n".reader()).complete("h").isEmpty())
    }

    @Test
    fun suppliedReaderIsClosedAfterLoading() {
        var closed = false
        val reader = object : StringReader("hello\t3\n") {
            override fun close() {
                closed = true
                super.close()
            }
        }
        val lexicon = EnglishLexicon(reader)
        assertTrue(closed)
        assertEquals(listOf("hello"), lexicon.complete("he"))
    }

    @Test
    fun boundedRankingMatchesAnExhaustiveSort() {
        val words = (0 until 2_000).map { index ->
            val word = "co" + ('a' + index / 676) + ('a' + (index / 26) % 26) + ('a' + index % 26)
            word to (index * 19 % 101 + 1)
        }
        val lexicon = EnglishLexicon(words.reversed().joinToString("\n") { "${it.first}\t${it.second}" }.reader())
        for (prefix in listOf("c", "co", "coa", "cob", "coc", "coaz", "zz")) {
            for (limit in listOf(1, 3, 8, 12, 100, Int.MAX_VALUE)) {
                val expected = words.filter { it.first.startsWith(prefix) && it.first != prefix }
                    .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
                    .take(limit).map { it.first }
                assertEquals("$prefix, limit $limit", expected, lexicon.complete(prefix, limit))
            }
        }
    }

    @Test
    fun packagedVocabularyCoversCommonDailyAndTechnicalWords() {
        val lexicon = EnglishLexicon(File("src/test/resources/dictionaries/english_frequency.tsv").reader())
        assertEquals(25_000, lexicon.entryCount)
        for ((prefix, word) in listOf(
            "hel" to "hello", "compu" to "computer", "keyb" to "keyboard",
            "restau" to "restaurant", "appoint" to "appointment", "tomor" to "tomorrow",
            "avail" to "available", "devel" to "development", "bec" to "because",
            "rec" to "received", "docum" to "document", "transl" to "translation",
        )) {
            val results = lexicon.complete(prefix, 12)
            assertTrue("$word missing for $prefix: $results", word in results)
            assertTrue(results.all { it.startsWith(prefix) && it != prefix })
            assertEquals(results.distinct(), results)
        }
        assertEquals("what", lexicon.complete("w").first())
        assertEquals("computer", lexicon.complete("compu").first())
        assertTrue(lexicon.complete("Hel").contains("Hello"))
        assertTrue(lexicon.complete("HEL").contains("HELLO"))
        assertTrue(lexicon.complete("").isEmpty())
        assertTrue(lexicon.complete("hello ").isEmpty())
    }

    @Test
    fun packagedVocabularyRetainsWeekdaysAndMonthsWithoutPersonalNameLists() {
        val lexicon = EnglishLexicon(File("src/test/resources/dictionaries/english_frequency.tsv").reader())
        val calendar = ("monday tuesday wednesday thursday friday saturday sunday " +
            "january february march april may june july august september october november december").split(' ')
        for (word in calendar) {
            assertTrue(word, word in lexicon.complete(word.dropLast(1), 12))
        }
        assertFalse("jennifer" in lexicon.complete("jen", 100))
        assertFalse("michael" in lexicon.complete("mich", 100))
    }
}
