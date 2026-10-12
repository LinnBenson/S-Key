package io.secure.keyboard.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView
import io.secure.keyboard.MainActivity

/** The complete 10,000-entry custom list is retained, without 10,000 eager TextViews. */
@Suppress("DEPRECATION")
class CandidateStripPagingTest : InstrumentationTestCase() {
    fun testNewResultDoesNotPrefetchAgainstAnEmptyPreLayoutRowWidth() {
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as Activity
        lateinit var strip: CandidateStrip
        lateinit var row: LinearLayout
        fun awaitLayout(description: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 10_000
            var ready = false
            while (SystemClock.uptimeMillis() < deadline && !ready) {
                instrumentation.runOnMainSync { ready = condition() }
                if (!ready) SystemClock.sleep(25)
            }
            assertTrue(description, ready)
        }
        try {
            instrumentation.runOnMainSync {
                strip = CandidateStrip(activity, Color.BLACK, Color.BLUE) {}
                activity.setContentView(strip)
                row = strip.getChildAt(0) as LinearLayout
            }
            awaitLayout("Empty fixture viewport must finish its first layout") {
                strip.width > 0 && !strip.isLayoutRequested && !row.isLayoutRequested
            }
            instrumentation.runOnMainSync {
                assertTrue("Fixture must have an already measured viewport", strip.width > 0)
                assertEquals("Fixture starts with the empty row's measured width", 0, row.width)
                strip.updateCandidates(List(10_040) { "候选$it" })
                assertEquals(40, row.childCount)
                assertTrue("The newly bound first batch still needs measurement", row.isLayoutRequested)
                val append = CandidateStrip::class.java.getDeclaredField("appendNearEnd")
                    .apply { isAccessible = true }.get(strip) as Runnable
                strip.removeCallbacks(append)
                // Choreographer's animation phase precedes its layout traversal. Exercise
                // that ordering deterministically rather than depending on worker timing.
                append.run()
                assertEquals("A stale zero width must not eagerly append another batch", 40, row.childCount)
            }
            awaitLayout("New first batch must finish measurement before its next paging check") {
                !strip.isLayoutRequested && !row.isLayoutRequested && row.width > strip.width * 2
            }
            instrumentation.runOnMainSync {
                assertEquals("Settled initial result must retain only its first batch", 40, row.childCount)
                assertTrue(row.width > strip.width * 2)
                strip.scrollTo(row.width, 0)
            }
            val deadline = SystemClock.uptimeMillis() + 10_000
            var appended = false
            while (SystemClock.uptimeMillis() < deadline && !appended) {
                instrumentation.runOnMainSync { appended = row.childCount == 80 }
                if (!appended) SystemClock.sleep(25)
            }
            assertTrue("Real scrolling must still make the next full batch reachable", appended)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { assertEquals(80, row.childCount) }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    fun testLargeResultAppendsOnlyOneBatchAndPreservesFontsViewsScrollAndTouchGuards() {
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as Activity
        lateinit var strip: CandidateStrip
        lateinit var row: LinearLayout
        lateinit var first: TextView
        lateinit var firstText: CharSequence
        var version = 1
        val chosen = mutableListOf<String>()
        val rare = "\uD855\uDF5A\uD856\uDF07"
        val values = (0 until 10_040).map { if (it == 55) rare else "候选$it" }
        try {
            instrumentation.runOnMainSync {
                strip = CandidateStrip(activity, Color.BLACK, Color.BLUE, { version }) { chosen.add(it) }
                activity.setContentView(strip)
                strip.updateCandidates(values)
                row = strip.getChildAt(0) as LinearLayout
                assertEquals(40, row.childCount)
                first = row.getChildAt(0) as TextView
                firstText = first.text
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { strip.scrollTo(row.width, 0) }
            val end = SystemClock.uptimeMillis() + 10_000
            var appended = false
            while (SystemClock.uptimeMillis() < end && !appended) {
                instrumentation.runOnMainSync { appended = row.childCount >= 80 }
                if (!appended) SystemClock.sleep(25)
            }
            assertTrue("Scrolling must append the next batch", appended)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals("One approach to the end must not eagerly bind all 10,040 labels", 80, row.childCount)
                val x = strip.scrollX
                strip.updateCandidates(values.toList())
                assertSame(first, row.getChildAt(0))
                assertSame(firstText, first.text)
                assertEquals(x, strip.scrollX)
                assertEquals(80, row.childCount)
                val appendedLabel = row.getChildAt(55) as TextView
                assertEquals(rare, appendedLabel.text.toString())
                for (index in listOf(0, 2)) {
                    val paint = TextPaint(appendedLabel.paint)
                    (appendedLabel.text as? Spanned)
                        ?.getSpans(index, index + 2, MetricAffectingSpan::class.java)
                        ?.forEach { it.updateDrawState(paint) }
                    assertTrue("Appended rare candidates must retain their font spans",
                        paint.hasGlyph(rare.substring(index, index + 2)))
                }
                val down = SystemClock.uptimeMillis()
                val press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, 8f, 8f, 0)
                appendedLabel.dispatchTouchEvent(press)
                press.recycle()
                version++
                val release = MotionEvent.obtain(down, down + 10, MotionEvent.ACTION_UP, 8f, 8f, 0)
                appendedLabel.dispatchTouchEvent(release)
                release.recycle()
                assertTrue("An appended row must also reject a stale gesture", chosen.isEmpty())
                appendedLabel.performClick()
                assertEquals(listOf(rare), chosen)
                strip.updateCandidates(emptyList())
                assertEquals(0, row.childCount)
                assertEquals(0, strip.scrollX)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
