package io.secure.keyboard.diagnostics

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.widget.LinearLayout
import android.widget.TextView
import io.secure.keyboard.MainActivity
import io.secure.keyboard.ui.CandidateGrid
import io.secure.keyboard.ui.CandidateStrip
import org.json.JSONObject

/** Full logical reachability plus actually visited strip/view memory; no eager bind shortcut. */
@Suppress("DEPRECATION")
class CandidateTraversalDiagnosticTest : InstrumentationTestCase() {
    fun testTenThousandCandidatesNaturalRecoveryThenSeparateCollectibility() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as Activity
        val probe = LifetimeMemoryProbe(instrumentation.targetContext, "candidate-traversal")
        lateinit var strip: CandidateStrip
        lateinit var row: LinearLayout
        lateinit var grid: CandidateGrid
        var chosen = ""
        var retiredLabels = emptyList<java.lang.ref.WeakReference<android.view.View>>()
        try {
            instrumentation.checkedMain {
                strip = CandidateStrip(activity, Color.BLACK, Color.BLUE) { chosen = it }
                grid = CandidateGrid(activity, Color.BLACK, Color.BLUE, Color.WHITE) { chosen = it }
                val values = List(10_000) { index ->
                    when (index) { 55 -> "\uD855\uDF5A\uD856\uDF07"; else -> "合成候选$index" }
                }
                strip.updateCandidates(values)
                grid.updateCandidates(values)
                row = strip.getChildAt(0) as LinearLayout
                activity.setContentView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(strip, LinearLayout.LayoutParams(-1, 100))
                    addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
                })
            }
            await("Initial strip/grid layout") { !strip.isLayoutRequested && row.width > 0 && grid.childCount > 0 }
            assertEquals(10_000, grid.adapter.count)
            probe.stage = "initial_40"
            probe.sample("initial", extra = JSONObject().put("stripLabels", row.childCount).put("gridAttachedCells", grid.childCount).put("logicalCount", 10_000))
            probe.start()
            val deadline = SystemClock.elapsedRealtime() + 300_000
            while (true) {
                var count = 0
                instrumentation.checkedMain { count = row.childCount }
                if (count == 10_000) break
                check(count < 10_000 && SystemClock.elapsedRealtime() < deadline) { "Full candidate traversal exceeded five-minute observation window" }
                instrumentation.checkedMain { strip.scrollTo(row.width, 0) }
                await("Next actual scroll batch after $count") { row.childCount > count && !row.isLayoutRequested }
                if (row.childCount in listOf(1000, 5000, 10_000)) {
                    probe.stage = "visited_${row.childCount}"
                    probe.sample("visited_checkpoint", extra = JSONObject().put("stripLabels", row.childCount).put("gridAttachedCells", grid.childCount))
                }
            }
            instrumentation.checkedMain {
                val last = row.getChildAt(9999) as TextView
                assertEquals("合成候选9999", last.text.toString())
                last.performClick()
                assertEquals("All 10,000 candidates remain selectable", "合成候选9999", chosen)
                strip.scrollTo(0, 0)
                grid.setSelection(9999)
            }
            await("Grid final row actually attached") { grid.lastVisiblePosition == 9999 }
            instrumentation.checkedMain {
                val last = grid.getChildAt(9999 - grid.firstVisiblePosition) as TextView
                assertEquals("合成候选9999", last.text.toString())
                chosen = ""
                last.performClick()
                assertEquals("Grid commits full logical last value", "合成候选9999", chosen)
                assertTrue("Grid recycles instead of attaching every logical item", grid.childCount < 200)
                grid.setSelection(0)
                (row.getChildAt(0) as TextView).performClick()
                assertEquals("Backward strip reachability", "合成候选0", chosen)
                retiredLabels = (0 until row.childCount).map { java.lang.ref.WeakReference(row.getChildAt(it)) }
                strip.updateCandidates(emptyList())
                grid.updateCandidates(emptyList())
                chosen = ""
            }
            instrumentation.waitForIdleSync()
            probe.stage = "presentation_cleared"
            probe.recover(final = true) { JSONObject().put("stripLabels", row.childCount).put("gridLogicalCount", grid.adapter.count).put("gridAttachedCells", grid.childCount) }
            assertEquals(0, row.childCount)
            assertEquals(0, grid.adapter.count)
            // Separate attribution diagnostic, after the natural 60-second recovery above.
            probe.observeWeakReferences("cleared_candidate_labels_forced_gc", retiredLabels, true)
            val memory = android.os.Debug.MemoryInfo().also(android.os.Debug::getMemoryInfo)
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putString("candidate_collectibility", JSONObject()
                    .put("labels", retiredLabels.size).put("stillReachable", retiredLabels.count { it.get() != null })
                    .put("forcedGcRequested", true).put("pssKiB", memory.totalPss)
                    .put("javaUsedBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                    .put("nativeAllocatedBytes", android.os.Debug.getNativeHeapAllocatedSize()).toString())
            })
            assertEquals("Cleared candidate labels must be collectible", 0, retiredLabels.count { it.get() != null })
        } finally {
            probe.stop()
            instrumentation.checkedMain { activity.finish() }
        }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            var ready = false
            instrumentation.checkedMain { ready = condition() }
            if (ready) return
            SystemClock.sleep(25)
        }
        fail(label)
    }
}
