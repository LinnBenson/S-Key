package io.secure.keyboard.ui

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class BottomRowGeometryTest {
    @Test
    fun mirroredPairsCoverEveryPixelAndKeepTheExactCenterAcrossWidths() {
        for (width in (0..16_384).toList() + listOf(Int.MAX_VALUE - 1, Int.MAX_VALUE)) {
            val cells = BottomRowGeometry.widths(width)
            assertEquals("Mode/Enter at width $width", cells[0], cells[4])
            assertEquals("Punctuation/globe at width $width", cells[1], cells[3])
            assertTrue("Nonnegative cells at width $width", cells.all { it >= 0 })
            assertEquals("Complete row at width $width", width.toLong(), cells.sumOf { it.toLong() })
            assertEquals("Space center at width $width", width.toLong(),
                2L * (cells[0] + cells[1]) + cells[2])
            for (index in listOf(0, 1, 3, 4)) {
                val weight = if (index == 0 || index == 4) 17 else 9
                assertTrue("Paired widths are nearest-pixel 17/94 and 9/94 at width $width",
                    abs(cells[index] * 94L - width * weight.toLong()) <= 47L)
            }
            assertTrue("Only the center absorbs rounding, by at most two pixels",
                abs(cells[2] * 94L - width * 42L) <= 188L)
        }
    }

    @Test
    fun formerlyAsymmetricWidthAndExactWeightMultiplesHaveFixedAllocations() {
        assertArrayEquals(intArrayOf(37, 19, 90, 19, 37), BottomRowGeometry.widths(202))
        assertArrayEquals(intArrayOf(17, 9, 42, 9, 17), BottomRowGeometry.widths(94))
        assertArrayEquals(intArrayOf(170, 90, 420, 90, 170), BottomRowGeometry.widths(940))
    }
}
