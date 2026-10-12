package io.secure.keyboard.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.text.Editable
import android.text.TextWatcher
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.secure.keyboard.MainActivity

@Suppress("DEPRECATION")
class CandidateGridTest : InstrumentationTestCase() {
    private lateinit var activity: Activity
    private lateinit var grid: CandidateGrid
    private var version: Int? = 1
    private val chosen = mutableListOf<String>()

    override fun setUp() {
        super.setUp()
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.runOnMainSync {
            grid = CandidateGrid(activity, Color.DKGRAY, Color.BLUE, Color.WHITE, { version }) { chosen.add(it) }
            val height = (200 * activity.resources.displayMetrics.density).toInt()
            activity.setContentView(grid, ViewGroup.LayoutParams(-1, height))
        }
    }

    override fun tearDown() {
        instrumentation.runOnMainSync { activity.finish() }
        super.tearDown()
    }

    fun testTenThousandCandidatesRecyclePreserveSameResultsAndCommitCompleteLongText() {
        val longText = "这是完整的自定义长句，网格只显示两行，但输入时必须保留全部内容。".repeat(5)
        val values = List(10_040) { if (it == 10_039) longText else "候选$it" }
        instrumentation.runOnMainSync { grid.updateCandidates(values) }
        instrumentation.waitForIdleSync()
        val seen = mutableSetOf<View>()
        instrumentation.runOnMainSync {
            assertEquals(10_040, grid.adapter.count)
            assertTrue("Only the visible viewport should be attached", grid.childCount in 1..64)
            repeat(grid.childCount) { seen.add(grid.getChildAt(it)) }
        }
        for (position in listOf(300, 5_000, 9_996, 100, 10_039)) {
            instrumentation.runOnMainSync { grid.setSelection(position) }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue("Large jumps must keep attached cells bounded", grid.childCount in 1..64)
                repeat(grid.childCount) { seen.add(grid.getChildAt(it)) }
            }
        }
        instrumentation.runOnMainSync {
            assertTrue("GridView must recycle rather than allocate every visited candidate", seen.size < 100)
            assertEquals(values.lastIndex, grid.lastVisiblePosition)
            val firstPosition = grid.firstVisiblePosition
            val offset = grid.getChildAt(0).top
            val labels = (0 until grid.childCount).map { grid.getChildAt(it) as TextView }
            val texts = labels.map { it.text }
            var rebindings = 0
            val watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { rebindings++ }
                override fun afterTextChanged(s: Editable?) {}
            }
            labels.forEach { it.addTextChangedListener(watcher) }
            grid.updateCandidates(values.toList())
            assertEquals(firstPosition, grid.firstVisiblePosition)
            assertEquals(offset, grid.getChildAt(0).top)
            assertEquals(0, rebindings)
            labels.forEachIndexed { index, view ->
                assertSame(view, grid.getChildAt(index))
                assertSame(texts[index], view.text)
                view.removeTextChangedListener(watcher)
            }
            val last = grid.getChildAt(values.lastIndex - grid.firstVisiblePosition) as TextView
            assertEquals(2, last.maxLines)
            assertEquals(TextUtils.TruncateAt.END, last.ellipsize)
            assertTrue(last.layout.lineCount <= 2)
            assertTrue("The long display must actually ellipsize", (0 until last.layout.lineCount).any {
                last.layout.getEllipsisCount(it) > 0
            })
            last.performClick()
            assertEquals(listOf(longText), chosen)
        }
    }

    fun testChangedVisibleValueRebindsOnlyChangedTextAndOffscreenTailStaysCheap() {
        val values = List(10_040) { "候选$it" }
        instrumentation.runOnMainSync { grid.updateCandidates(values) }
        instrumentation.waitForIdleSync()
        var rebindings = 0
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { rebindings++ }
            override fun afterTextChanged(s: Editable?) {}
        }
        val updated = values.toMutableList()
        instrumentation.runOnMainSync {
            repeat(grid.childCount) { (grid.getChildAt(it) as TextView).addTextChangedListener(watcher) }
            updated[2] = "新候选"
            grid.updateCandidates(updated)
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertEquals("Unchanged visible cells must retain their text/font spans", 1, rebindings)
            assertEquals("新候选", (grid.getChildAt(2) as TextView).text.toString())
            grid.getChildAt(2).performClick()
            assertEquals(listOf("新候选"), chosen)
            updated[10_000] = "屏幕外变化"
            grid.updateCandidates(updated)
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { assertEquals("Offscreen changes must not rebind visible labels", 1, rebindings) }
    }

    fun testLoadingChangedGenerationCanceledGestureRecycledValueAndClosedGridCannotSelect() {
        instrumentation.runOnMainSync { grid.updateCandidates(List(80) { "候选$it" }) }
        instrumentation.waitForIdleSync()
        lateinit var cell: View
        instrumentation.runOnMainSync {
            cell = grid.getChildAt(0)
            version = null
            touch(cell, MotionEvent.ACTION_DOWN)
            version = 2
            touch(cell, MotionEvent.ACTION_UP)
            assertTrue(chosen.isEmpty())
            touch(cell, MotionEvent.ACTION_DOWN)
            version = 3
            touch(cell, MotionEvent.ACTION_UP)
            assertTrue(chosen.isEmpty())
            touch(cell, MotionEvent.ACTION_DOWN)
            touch(cell, MotionEvent.ACTION_CANCEL)
            touch(cell, MotionEvent.ACTION_UP)
            assertTrue("Canceled scroll gesture must be silent", chosen.isEmpty())
            cell.performClick()
            assertEquals("A fresh accessibility click after cancellation must still work",
                listOf("候选0"), chosen)
            chosen.clear()
            touch(cell, MotionEvent.ACTION_DOWN)
            grid.updateCandidates(List(80) { "更新$it" })
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            touch(cell, MotionEvent.ACTION_UP)
            assertTrue("A rebound cell cannot finish an old press", chosen.isEmpty())
            version = null
            grid.getChildAt(0).performClick()
            assertTrue("Closed/loading grid cannot use accessibility clicks", chosen.isEmpty())
            version = 4
            grid.getChildAt(0).performClick()
            assertEquals(listOf("更新0"), chosen)
            grid.updateCandidates(emptyList())
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertEquals(0, grid.childCount)
            assertEquals(0, grid.adapter.count)
            cell.performClick()
            assertEquals(1, chosen.size)
        }
    }

    private fun touch(view: View, action: Int) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, 8f, 8f, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    fun testDetachAndReattachCannotCompleteAnOldGesture() {
        instrumentation.runOnMainSync { grid.updateCandidates(List(40) { "候选$it" }) }
        instrumentation.waitForIdleSync()
        lateinit var oldCell: View
        instrumentation.runOnMainSync {
            oldCell = grid.getChildAt(0)
            touch(oldCell, MotionEvent.ACTION_DOWN)
            activity.setContentView(View(activity))
            oldCell.performClick()
            assertTrue("Detached cells cannot select even if the query is unchanged", chosen.isEmpty())
            activity.setContentView(grid, ViewGroup.LayoutParams(-1, 400))
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            touch(oldCell, MotionEvent.ACTION_UP)
            assertTrue("A collapse/reopen cannot finish the earlier touch", chosen.isEmpty())
            grid.getChildAt(0).performClick()
            assertEquals(listOf("候选0"), chosen)
        }
    }
}
