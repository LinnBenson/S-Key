package io.secure.keyboard.ui

import android.graphics.Color
import android.test.InstrumentationTestCase
import android.widget.LinearLayout
import android.widget.TextView

@Suppress("DEPRECATION")
class CandidateTextSizeTest : InstrumentationTestCase() {
    fun testCustomSizesApplyToLabelsMeasurementAndRecycledGridCells() {
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val scale = context.resources.displayMetrics.scaledDensity
            for (size in listOf(12f, 20f, 32f)) {
                val strip = CandidateStrip(context, Color.BLACK, Color.BLUE, textSizeSp = size) {}
                strip.updateCandidates(listOf("字号测试", "完整候选词"))
                val row = strip.getChildAt(0) as LinearLayout
                assertEquals(size * scale, (row.getChildAt(0) as TextView).textSize, .01f)
                assertEquals(size * scale, strip.normalPaintSnapshot().textSize, .01f)
                val grid = CandidateGrid(context, Color.BLACK, Color.BLUE, Color.WHITE, textSizeSp = size) {}
                grid.updateCandidates(listOf("字号测试", "完整候选词"))
                val cell = grid.adapter.getView(0, null, grid) as TextView
                assertEquals(size * scale, cell.textSize, .01f)
                val required = kotlin.math.ceil((cell.paint.fontMetrics.bottom - cell.paint.fontMetrics.top) * 2).toInt()
                assertTrue(cell.layoutParams.height >= required + cell.paddingTop + cell.paddingBottom)
                val recycled = grid.adapter.getView(1, cell, grid) as TextView
                assertSame(cell, recycled)
                assertEquals(size * scale, recycled.textSize, .01f)
                assertEquals("完整候选词", recycled.text.toString())
            }
        }
    }
}
