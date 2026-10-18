package io.secure.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingResizeTest {
    @Test fun floatingKeyboardCanReachTopInPortraitAndLandscape() {
        assertEquals(540, FloatingResize.maxBottom(800, 260))
        assertEquals(160, FloatingResize.maxBottom(420, 260))
        assertEquals(0, FloatingResize.maxBottom(200, 260))
    }

    @Test fun resizingAtScreenTopPreservesTopAndRemainsVisible() {
        val top = FloatingResize.Bounds(280, 260, 40, 540)
        for (dy in -500..500 step 10) {
            val result = FloatingResize.resize(top, 0, dy, 540, 600, 800)
            assertEquals(800, result.height + result.bottom)
            assertEquals(result.bottom, FloatingResize.maxBottom(800, result.height))
        }
    }

    private val start = FloatingResize.Bounds(280, 260, 40, 120)
    private fun resize(dx: Int, dy: Int) = FloatingResize.resize(start, dx, dy, 540, 700, 320)

    @Test fun draggingDownRightGrowsAndKeepsTopLeftFixed() {
        assertEquals(FloatingResize.Bounds(330, 310, 40, 70), resize(50, 50))
    }

    @Test fun draggingUpLeftShrinksAndKeepsTopLeftFixed() {
        assertEquals(FloatingResize.Bounds(230, 210, 40, 170), resize(-50, -50))
    }

    @Test fun screenEdgesClampSizeWithoutMovingTheAnchor() {
        assertEquals(FloatingResize.Bounds(500, 380, 40, 0), resize(1000, 1000))
        assertEquals(FloatingResize.Bounds(220, 200, 40, 180), resize(-1000, -1000))
    }

    @Test fun bottomMarginLimitAndRepeatedMovesPreserveTopDistance() {
        val high = FloatingResize.Bounds(280, 260, 40, 320)
        val result = FloatingResize.resize(high, 0, -100, 540, 700, 320)
        assertEquals(high, result)
        for (dy in -500..500 step 10) {
            val bounds = resize(30, dy)
            assertEquals(start.height + start.bottom, bounds.height + bounds.bottom)
            assertEquals(start.left, bounds.left)
        }
    }
}
