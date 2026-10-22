package io.secure.keyboard.ui

import org.junit.Assert.*
import org.junit.Test

class SymbolKeyLayoutTest {
    @Test
    fun allFourPagesKeepExactCodePointsAndOrder() {
        val expected = listOf(
            Triple(true, false, listOf("1234567890", "@#${'$'}_&-+（）/", ".,、：；！？")),
            Triple(true, true, listOf("~`|•《》÷×*§", "“”€¥^°=「」\\", "%‘’™✓［］")),
            Triple(false, false, listOf("1234567890", "@#${'$'}_&-+()/", ".,*:;!?")),
            Triple(false, true, listOf("~`|•<>÷×§∆", "\"'€¥^°={}\\", "%©®™✓[]")),
        )
        for ((chinese, secondary, rows) in expected) {
            val actual = SymbolKeyLayout.rows(chinese, secondary)
            assertEquals(listOf(10, 10, 7), actual.map { it.codePointCount(0, it.length) })
            rows.zip(actual).forEach { (wanted, found) ->
                assertArrayEquals(wanted.codePoints().toArray(), found.codePoints().toArray())
            }
            assertTrue("Every symbol is its own BMP key", actual.all { row ->
                row.map { it.toString() }.all { it.codePointCount(0, it.length) == 1 }
            })
        }
    }

    @Test
    fun visuallySimilarBracketsQuotesAndDeltaKeepTheirSpecifiedCharacters() {
        val chineseFirst = SymbolKeyLayout.rows(true, false)
        val chineseSecond = SymbolKeyLayout.rows(true, true)
        val englishFirst = SymbolKeyLayout.rows(false, false)
        val englishSecond = SymbolKeyLayout.rows(false, true)
        assertEquals(listOf(0xff08, 0xff09), chineseFirst[1].drop(7).take(2).map { it.code })
        assertEquals(listOf(0x201c, 0x201d), chineseSecond[1].take(2).map { it.code })
        assertEquals(listOf(0x2018, 0x2019), chineseSecond[2].drop(1).take(2).map { it.code })
        assertEquals(listOf(0xff3b, 0xff3d), chineseSecond[2].takeLast(2).map { it.code })
        assertEquals(listOf(0x22, 0x27), englishSecond[1].take(2).map { it.code })
        assertEquals(listOf(0x5b, 0x5d), englishSecond[2].takeLast(2).map { it.code })
        assertEquals(listOf(0x300a, 0x300b), chineseSecond[0].drop(4).take(2).map { it.code })
        assertEquals(listOf(0x3c, 0x3e), englishSecond[0].drop(4).take(2).map { it.code })
        assertEquals(0x2206, englishSecond[0].last().code)
        assertFalse(englishSecond[0].contains('\u0394'))
        assertFalse(chineseFirst.joinToString("").contains('。'))
    }

    @Test
    fun thirdRowsUseLetterRowWeightsWithoutExtraGaps() {
        assertEquals(0f, SymbolKeyLayout.sideSpacerWeight(10, false), 0.0001f)
        val gap = SymbolKeyLayout.sideSpacerWeight(7, true)
        assertEquals(0f, gap, 0.0001f)
        assertEquals(9.6f, 1.3f + gap + 7f + gap + 1.3f, 0.0001f)
    }
}
