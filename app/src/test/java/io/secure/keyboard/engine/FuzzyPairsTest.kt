package io.secure.keyboard.engine

import java.io.StringReader
import org.junit.Assert.*
import org.junit.Test

class FuzzyPairsTest {
    private fun fixture(code: String) =
        DictionaryEngine(StringReader("...\n"), StringReader("...\n目标\t$code\t100\n"))

    @Test
    fun eachPairCanBeEnabledIndependentlyInBothDirections() {
        val cases =
            mapOf(
                "zh_z" to ("zha" to "za"),
                "ch_c" to ("cha" to "ca"),
                "sh_s" to ("sha" to "sa"),
                "n_l" to ("na" to "la"),
                "r_l" to ("ran" to "lan"),
                "f_h" to ("fa" to "ha"),
                "an_ang" to ("ban" to "bang"),
                "en_eng" to ("ben" to "beng"),
                "in_ing" to ("bin" to "bing"),
            )
        for ((pair, forms) in cases) for ((stored, typed) in
            listOf(forms, forms.second to forms.first)) {
            val engine = fixture(stored)
            assertTrue(
                "$pair enabled",
                engine.candidates(typed, fuzzyPairs = setOf(pair)).any { it.text == "目标" },
            )
            assertFalse(
                "$pair disabled",
                engine
                    .candidates(typed, fuzzyPairs = DictionaryEngine.DEFAULT_FUZZY_PAIRS - pair)
                    .any { it.text == "目标" },
            )
            assertFalse(
                "master disabled",
                engine.candidates(typed, false, fuzzyPairs = setOf(pair)).any { it.text == "目标" },
            )
            assertFalse(
                "empty pair selection",
                engine.candidates(typed, fuzzyPairs = emptySet()).any { it.text == "目标" },
            )
            assertTrue(
                "exact reading survives",
                engine.candidates(stored, fuzzyPairs = emptySet()).any { it.text == "目标" },
            )
        }
    }

    @Test
    fun multipleChangedPairsRequireEveryRelevantSwitch() {
        val engine = fixture("zheng")
        assertTrue(
            engine.candidates("zen", fuzzyPairs = setOf("zh_z", "en_eng")).any { it.text == "目标" }
        )
        for (pair in listOf("zh_z", "en_eng")) assertFalse(
            engine.candidates("zen", fuzzyPairs = setOf(pair)).any { it.text == "目标" }
        )
    }

    @Test
    fun explicitSyllableBoundariesRemainMandatory() {
        val engine = fixture("xi ang")
        assertTrue(engine.candidates("xi'an", fuzzyPairs = setOf("an_ang")).any { it.text == "目标" })
        assertFalse(engine.candidates("xian", fuzzyPairs = emptySet()).any { it.text == "目标" })
        assertFalse(
            fixture("xiang").candidates("xi'an", fuzzyPairs = setOf("an_ang")).any {
                it.text == "目标"
            }
        )
    }

    @Test
    fun rLPairStartsOffAndDoesNotEnableNL() {
        assertFalse("r_l" in DictionaryEngine.DEFAULT_FUZZY_PAIRS)
        assertFalse(fixture("ran").candidates("lan").any { it.text == "目标" })
        for ((stored, typed) in listOf("na" to "la", "la" to "na", "ran" to "nan")) {
            assertFalse(
                "$typed must not match $stored with only r/l",
                fixture(stored).candidates(typed, fuzzyPairs = setOf("r_l")).any {
                    it.text == "目标"
                },
            )
        }
        assertTrue(
            fixture("ran").candidates("nan", fuzzyPairs = setOf("r_l", "n_l")).any {
                it.text == "目标"
            }
        )
    }

    @Test
    fun rLMatchesValidInitialsInCompactAndExplicitPhrasesBothWays() {
        for ((stored, typed) in listOf("ruan luo" to "luan ruo", "luan ruo" to "ruan luo")) {
            val engine = fixture(stored)
            for (input in listOf(typed, typed.replace(' ', '\''), typed.replace(" ", ""))) {
                assertTrue(
                    "$input must match $stored with r/l enabled",
                    engine.candidates(input, fuzzyPairs = setOf("r_l")).any { it.text == "目标" },
                )
                assertFalse(
                    "$input must not match $stored with r/l disabled",
                    engine.candidates(input, fuzzyPairs = emptySet()).any { it.text == "目标" },
                )
            }
        }
        assertTrue(
            fixture("er li").candidates("erri", fuzzyPairs = setOf("r_l")).any {
                it.text == "目标"
            }
        )
        assertFalse(
            fixture("er li").candidates("e'rri", fuzzyPairs = setOf("r_l")).any {
                it.text == "目标"
            }
        )
    }

    @Test
    fun rLNeverRewritesFinalsInteriorLettersOrInvalidSyllables() {
        val invalidChanges =
            listOf(
                "er" to "el",
                "er li" to "elri",
                "er li" to "elli",
                "er" to "e'l",
                "ren" to "len",
                "rui" to "lui",
                "la" to "ra",
                "lian" to "rian",
            )
        for ((stored, typed) in invalidChanges.flatMap { listOf(it, it.second to it.first) }) {
            assertFalse(
                "$typed must not match $stored with r/l",
                fixture(stored).candidates(typed, fuzzyPairs = setOf("r_l")).any {
                    it.text == "目标"
                },
            )
        }
    }

    @Test
    fun rLCombinesOnlyWithOtherEnabledPairs() {
        val engine = fixture("rang")
        assertTrue(
            engine.candidates("lan", fuzzyPairs = setOf("r_l", "an_ang")).any {
                it.text == "目标"
            }
        )
        for (pairs in listOf(setOf("r_l"), setOf("an_ang"), emptySet())) {
            assertFalse(engine.candidates("lan", fuzzyPairs = pairs).any { it.text == "目标" })
        }
        // Both actual syllables are valid even when the combined canonical key is len.
        assertTrue(
            fixture("ren").candidates("leng", fuzzyPairs = setOf("r_l", "en_eng")).any {
                it.text == "目标"
            }
        )
    }
}
