package io.secure.keyboard.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import io.secure.keyboard.MainActivity

@Suppress("DEPRECATION")
class ContentEntryRowTest : InstrumentationTestCase() {
    private lateinit var activity: Activity
    private lateinit var host: FrameLayout

    override fun setUp() {
        super.setUp()
        activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        )
        instrumentation.runOnMainSync {
            host = FrameLayout(activity)
            activity.setContentView(host)
        }
        instrumentation.waitForIdleSync()
    }

    override fun tearDown() {
        instrumentation.runOnMainSync { activity.finish() }
        super.tearDown()
    }

    fun testRecycledRowUsesOnlyNewTextAndCallbacks() {
        instrumentation.runOnMainSync {
            val events = mutableListOf<String>()
            val row = createRow("old", false, { events.add("old") }, { events.add("old") }, { events.add("old") })
            row.bind("new🙂", true, { events.add(it) }, { events.add("pin:$it") }, { events.add("delete:new") })
            assertEquals("new🙂", row.textView.text.toString())
            assertTrue(row.pinAction.isSelected)
            row.textView.performClick()
            row.pinAction.performClick()
            row.deleteAction.performClick()
            assertEquals(listOf("new🙂", "pin:false", "delete:new"), events)
        }
    }

    fun testSingleLinePreviewReservesBothIconTargetsAndInsertsFullStoredText() {
        val texts = listOf(
            "短句",
            "很长的完整剪贴板或常用语内容".repeat(100),
            "第一行\n第二行\r\n👨‍👩‍👧‍👦🙂𠀀".repeat(100),
        )
        for (text in texts) for (width in listOf(160, 220, 320, 480)) {
            val inserts = mutableListOf<String>()
            var pinCalls = 0
            var deleteCalls = 0
            lateinit var row: ContentEntryRow
            instrumentation.runOnMainSync {
                row = createRow(text, false, { inserts.add(it) }, { pinCalls++ }, { deleteCalls++ })
                attachAndMeasure(row, width)
                assertEquals(dp(52), row.height)
                assertEquals(1, row.textView.lineCount)
                assertEquals(TextUtils.TruncateAt.END, row.textView.ellipsize)
                assertEquals(text, row.textView.text.toString())
                assertEquals(dp(48), row.pinAction.width)
                assertEquals(dp(48), row.pinAction.height)
                assertEquals(dp(48), row.deleteAction.width)
                assertEquals(dp(48), row.deleteAction.height)
                assertTrue(row.pinAction.left >= row.textView.right)
                assertTrue(row.deleteAction.left >= row.pinAction.right)
                assertTrue(row.deleteAction.right <= row.width - row.paddingRight)
                assertTrue(row.textView.width > 0)
                assertFalse("Only the text and action areas are interactive", row.isClickable)
                if (text.length > 20) assertTrue(row.textView.layout.getEllipsisCount(0) > 0)
                assertEquals(0, textDescendants(row.pinAction))
                assertEquals(0, textDescendants(row.deleteAction))
            }
            tap(row, row.textView)
            assertEquals(listOf(text), inserts)
            assertEquals(0, pinCalls)
            assertEquals(0, deleteCalls)
            tap(row, row.pinAction)
            assertEquals(1, pinCalls)
            assertEquals(0, deleteCalls)
            assertEquals("Pin must never insert text", listOf(text), inserts)
            tap(row, row.deleteAction)
            assertEquals(1, deleteCalls)
            assertEquals("Delete must never insert text", listOf(text), inserts)
        }
    }

    fun testPinStateHasAccessibleIconOnlyActionAndCorrectNextState() {
        instrumentation.runOnMainSync {
            for (pinned in listOf(false, true)) {
                var nextState: Boolean? = null
                val row = createRow("完整短语", pinned, {}, { nextState = it }, {})
                attachAndMeasure(row, 320)
                assertEquals(pinned, row.pinAction.isSelected)
                assertEquals(if (pinned) "取消置顶常用语" else "置顶常用语", row.pinAction.contentDescription)
                assertEquals("删除常用语", row.deleteAction.contentDescription)
                for (action in listOf(row.textView, row.pinAction, row.deleteAction)) {
                    val info = action.createAccessibilityNodeInfo()
                    assertEquals("android.widget.Button", info.className)
                    assertTrue(info.isClickable)
                    assertTrue(action.isFocusable)
                    info.recycle()
                }
                assertTrue(row.pinAction.performClick())
                assertEquals(!pinned, nextState)
            }
        }
    }

    fun testLinkActionMatchesOtherIconsAndRecyclingClearsDestination() {
        instrumentation.runOnMainSync {
            val events = mutableListOf<String>()
            val row = createRow("text", false, {}, {}, {})
            row.bind("first link", false, {}, {}, {}, { events.add("first") })
            attachAndMeasure(row, 320)
            assertEquals(View.VISIBLE, row.openLinkAction.visibility)
            assertEquals(row.pinAction.width, row.openLinkAction.width)
            assertEquals(row.pinAction.height, row.openLinkAction.height)
            assertTrue(row.openLinkAction.right <= row.pinAction.left)
            row.openLinkAction.performClick()
            row.bind("second link", false, {}, {}, {}, { events.add("second") })
            row.openLinkAction.performClick()
            row.bind("plain text", false, {}, {}, {})
            assertEquals(View.GONE, row.openLinkAction.visibility)
            row.openLinkAction.performClick()
            assertEquals(listOf("first", "second"), events)
        }
    }

    private fun createRow(
        text: String,
        pinned: Boolean,
        insert: (String) -> Unit,
        pin: (Boolean) -> Unit,
        delete: () -> Unit,
    ) = ContentEntryRow(
        activity, text, pinned, Color.BLACK, Color.WHITE, Color.GRAY, Color.BLUE, Color.RED,
        "常用语", insert, pin, delete,
    )

    private fun attachAndMeasure(row: ContentEntryRow, widthDp: Int) {
        host.removeAllViews()
        host.addView(row, FrameLayout.LayoutParams(dp(widthDp), dp(52)))
        row.measure(
            View.MeasureSpec.makeMeasureSpec(dp(widthDp), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(52), View.MeasureSpec.EXACTLY),
        )
        row.layout(0, 0, row.measuredWidth, row.measuredHeight)
    }

    private fun tap(row: ContentEntryRow, target: View) {
        instrumentation.runOnMainSync {
            val now = SystemClock.uptimeMillis()
            val x = (target.left + target.right) / 2f
            val y = (target.top + target.bottom) / 2f
            for ((action, time) in listOf(MotionEvent.ACTION_DOWN to now, MotionEvent.ACTION_UP to now + 10)) {
                val event = MotionEvent.obtain(now, time, action, x, y, 0)
                try {
                    assertTrue("Reserved target consumes its touch", row.dispatchTouchEvent(event))
                } finally {
                    event.recycle()
                }
            }
        }
        // Android posts click delivery from ACTION_UP; let it finish before reading callbacks.
        instrumentation.waitForIdleSync()
    }

    private fun textDescendants(view: View): Int = when (view) {
        is TextView -> 1
        is ViewGroup -> (0 until view.childCount).sumOf { textDescendants(view.getChildAt(it)) }
        else -> 0
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
