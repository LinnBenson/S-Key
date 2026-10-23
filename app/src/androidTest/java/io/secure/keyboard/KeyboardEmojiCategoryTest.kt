package io.secure.keyboard

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.test.ServiceTestCase
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import kotlin.math.roundToInt
import io.secure.keyboard.data.ContentStore
import io.secure.keyboard.data.SettingsStore
import io.secure.keyboard.ui.EmojiGrid
import io.secure.keyboard.ui.ToolIcon

/** Production emoji views measured at wide/floating widths, with isolated recent-emoji storage. */
@Suppress("DEPRECATION")
class KeyboardEmojiCategoryTest : InstrumentationTestCase() {
    private lateinit var service: OfflineKeyboardService
    private lateinit var harness: Harness
    private lateinit var sandbox: IsolatedContext
    private lateinit var store: ContentStore
    private lateinit var connection: RecordingConnection
    private val context get() = instrumentation.targetContext
    private val body get() = field<FrameLayout>("body")
    private val column get() = body.getChildAt(0) as LinearLayout
    private val bar get() = column.getChildAt(0) as HorizontalScrollView
    private val categories get() = bar.getChildAt(0) as LinearLayout
    private val grid get() = column.getChildAt(2) as EmojiGrid

    private class Harness : ServiceTestCase<OfflineKeyboardService>(OfflineKeyboardService::class.java) {
        fun create(context: Context): OfflineKeyboardService {
            setContext(context)
            setApplication(context.applicationContext as Application)
            setUp()
            startService(Intent(context, OfflineKeyboardService::class.java))
            return service
        }
        private var closed = false
        fun close() { if (!closed) { closed = true; tearDown() } }
    }

    private class RecordingConnection(view: View) : BaseInputConnection(view, true) {
        var accepts = true
        val committed = mutableListOf<String>()
        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            if (!accepts) return false
            committed.add(text.toString())
            return true
        }
    }

    override fun setUp() {
        super.setUp()
        sandbox = IsolatedContext(context)
        sandbox.root.deleteRecursively()
        sandbox.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        store = ContentStore(sandbox)
        instrumentation.runOnMainSync {
            harness = Harness()
            service = harness.create(context)
            setField("settings", SettingsStore(sandbox).apply { vibration = false })
            setField("content", store)
            connection = RecordingConnection(View(context))
            service.onCreateInputMethodInterface().startInput(
                connection,
                EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT },
            )
            service.onCreateInputView()
        }
        val deadline = SystemClock.uptimeMillis() + 60_000
        var loaded = false
        while (!loaded && SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync { loaded = field<Boolean>("loaded") }
            if (!loaded) SystemClock.sleep(20)
        }
        assertTrue("Packaged emoji groups must finish loading", loaded)
    }

    override fun tearDown() {
        try {
            instrumentation.runOnMainSync { if (::harness.isInitialized) harness.close() }
            sandbox.root.deleteRecursively()
            sandbox.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        } finally {
            super.tearDown()
        }
    }

    fun testWideCategoryCellsFillExactlyTheEmojiGridBounds() {
        instrumentation.runOnMainSync {
            for (width in listOf(600, 841)) {
                showEmoji(width)
                assertEquals(10, categories.childCount)
                assertTrue(bar.isFillViewport)
                assertEquals(grid.left, bar.left)
                assertEquals(grid.right, bar.right)
                assertEquals(grid.width, categories.width)
                assertEquals(0, categories.getChildAt(0).left)
                assertEquals(grid.width, categories.getChildAt(9).right)
                val widths = (0 until categories.childCount).map { categories.getChildAt(it).width }
                assertTrue("Extra width is spread evenly", widths.max() - widths.min() <= 1)
                assertTrue(widths.all { it >= dp(42) })
                assertFalse(bar.canScrollHorizontally(1))
                assertSingleIconRow()
            }
        }
    }

    fun testNarrowCategoryCellsOverflowHorizontallyWithoutWrappingOrShrinking() {
        instrumentation.runOnMainSync {
            for (width in listOf(280, 200)) {
                showEmoji(width)
                assertEquals(grid.left, bar.left)
                assertEquals(grid.right, bar.right)
                assertEquals(categories.childCount * dp(42), categories.width)
                assertTrue(bar.canScrollHorizontally(1))
                for (index in 0 until categories.childCount)
                    assertEquals(dp(42), categories.getChildAt(index).width)
                assertSingleIconRow()
            }
        }
    }

    fun testRebuildAndFarEndCategoryTapRetainTheScrolledViewport() {
        instrumentation.runOnMainSync {
            showEmoji(280)
            bar.scrollTo(categories.width - bar.width, 0)
            val farEnd = bar.scrollX
            assertTrue("The smiley shortcut starts offscreen", categories.getChildAt(1).right < farEnd)
            invoke("renderBody")
            layoutBody(280)
            assertEquals("A rebuild must not snap back to the selected smiley", farEnd, bar.scrollX)
            assertEquals(1, field<Int>("emojiCategory"))
            categories.getChildAt(9).performClick()
            layoutBody(280)
            assertEquals(9, field<Int>("emojiCategory"))
            assertEquals("Tapping the revealed flag shortcut must not jump to the start", farEnd, bar.scrollX)
            assertSelectedFullyVisible()
            val groups = field<MutableList<Pair<String, List<String>>>>("emojiGroups")
            assertEquals(groups.last().second.first(), grid.adapter.getItem(0))
            assertTrue(connection.committed.isEmpty())
        }
    }

    fun testGridSwipesRevealEachSelectedCategoryOnlyAsNeeded() {
        instrumentation.runOnMainSync {
            setField("emojiCategory", 3)
            showEmoji(210)
            assertEquals(0, bar.scrollX)
            for (selected in 4..9) {
                swipeGrid(left = true)
                layoutBody(210)
                assertEquals(selected, field<Int>("emojiCategory"))
                assertSelectedFullyVisible()
                assertSingleIconRow()
            }
            assertTrue(bar.scrollX > 0)
            for (selected in 8 downTo 0) {
                swipeGrid(left = false)
                layoutBody(210)
                assertEquals(selected, field<Int>("emojiCategory"))
                assertSelectedFullyVisible()
            }
            assertEquals(0, bar.scrollX)
            assertTrue("Category swipes must not commit an emoji", connection.committed.isEmpty())
        }
    }

    fun testExplicitReopeningRestoresSmileysAndTheBeginningOfTheStrip() {
        instrumentation.runOnMainSync {
            showEmoji(280)
            bar.scrollTo(categories.width - bar.width, 0)
            assertTrue(bar.scrollX > 0)
            invokeTool("emoji")
            assertEquals("keys", field<String>("panel"))
            invokeTool("emoji")
            layoutBody(280)
            assertEquals(1, field<Int>("emojiCategory"))
            assertEquals(0, bar.scrollX)
            assertEquals("😀", grid.adapter.getItem(0))
            assertSelectedFullyVisible()
        }
    }

    fun testCategorySelectionAndEmojiInsertionStillUseTheSameGridData() {
        instrumentation.runOnMainSync {
            showEmoji(600)
            categories.getChildAt(3).performClick()
            layoutBody(600)
            val groups = field<MutableList<Pair<String, List<String>>>>("emojiGroups")
            val expected = groups[2].second
            assertEquals(expected.size, grid.adapter.count)
            for (index in expected.indices) assertEquals(expected[index], grid.adapter.getItem(index))
            val item = requireNotNull(grid.getChildAt(0))
            grid.performItemClick(item, 0, grid.adapter.getItemId(0))
            assertEquals(listOf(expected.first()), connection.committed)
        }
        awaitHistory { store.recentEmoji().isNotEmpty() }
        instrumentation.runOnMainSync { categories.getChildAt(0).performClick(); layoutBody(600) }
        awaitHistory {
            var ready = false
            instrumentation.runOnMainSync { ready = grid.adapter.count == 1 }
            ready
        }
        instrumentation.runOnMainSync { assertEquals(connection.committed.single(), grid.adapter.getItem(0)) }
    }

    fun testPasswordEmojiInsertionDoesNotRecordHistoryOrTouchDiskOnClick() {
        store.useEmoji("😎")
        instrumentation.runOnMainSync {
            setField("secret", true)
            setField("emojiCategory", 1)
            showEmoji(600)
            val old = android.os.StrictMode.getThreadPolicy()
            android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().penaltyDeath().build())
            try { grid.performItemClick(grid.getChildAt(0), 0, grid.adapter.getItemId(0)) }
            finally { android.os.StrictMode.setThreadPolicy(old) }
            assertEquals(listOf("😀"), connection.committed)
        }
        field<java.util.concurrent.ExecutorService>("worker").submit(object : Runnable { override fun run() {} }).get(10, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(listOf("😎"), store.recentEmoji())
    }

    fun testRejectedEmojiDoesNotRecordHistory() {
        instrumentation.runOnMainSync {
            connection.accepts = false
            setField("emojiCategory", 1)
            showEmoji(600)
            grid.performItemClick(grid.getChildAt(0), 0, grid.adapter.getItemId(0))
        }
        field<java.util.concurrent.ExecutorService>("worker").submit(object : Runnable { override fun run() {} }).get(10, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(store.recentEmoji().isEmpty())
    }

    fun testAcceptedHistoryWriteDrainsAfterServiceDestruction() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val writer = field<java.util.concurrent.ExecutorService>("emojiWorker")
        writer.execute(object : Runnable { override fun run() { gate.await(10, java.util.concurrent.TimeUnit.SECONDS) } })
        try {
            instrumentation.runOnMainSync {
                setField("emojiCategory", 1)
                showEmoji(600)
                grid.performItemClick(grid.getChildAt(0), 0, grid.adapter.getItemId(0))
                harness.close()
            }
        } finally { gate.countDown() }
        assertTrue(writer.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(listOf("😀"), store.recentEmoji())
    }

    private fun awaitHistory(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(20)
        }
        fail("Emoji history did not settle")
    }

    private fun assertSingleIconRow() {
        assertEquals(LinearLayout.HORIZONTAL, categories.orientation)
        assertEquals(dp(40), bar.height)
        assertEquals(dp(1), column.getChildAt(1).height)
        assertEquals(dp(40) + dp(1), grid.top)
        assertTrue("Recent remains the vector clock", categories.getChildAt(0) is ToolIcon)
        for (index in 0 until categories.childCount) {
            val cell = categories.getChildAt(index)
            assertEquals(0, cell.top)
            assertEquals(bar.height, cell.bottom)
            if (cell is TextView)
                assertTrue("Category names stay in accessibility labels, not captions", cell.text.length <= 2)
        }
    }

    private fun assertSelectedFullyVisible() {
        val cell = categories.getChildAt(field<Int>("emojiCategory"))
        assertTrue("Selected category starts within the viewport", cell.left >= bar.scrollX)
        assertTrue("Selected category ends within the viewport", cell.right <= bar.scrollX + bar.width)
    }

    private fun showEmoji(widthDp: Int) {
        setField("panel", "emoji")
        invoke("renderBody")
        layoutBody(widthDp)
    }

    private fun layoutBody(widthDp: Int) {
        body.measure(
            View.MeasureSpec.makeMeasureSpec(dp(widthDp), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(260), View.MeasureSpec.EXACTLY),
        )
        body.layout(0, 0, body.measuredWidth, body.measuredHeight)
    }

    private fun swipeGrid(left: Boolean) {
        val current = grid
        val from = current.width * if (left) .85f else .15f
        val to = current.width * if (left) .15f else .85f
        val now = SystemClock.uptimeMillis()
        for ((action, x, elapsed) in listOf(
            Triple(MotionEvent.ACTION_DOWN, from, 0L),
            Triple(MotionEvent.ACTION_MOVE, to, 30L),
            Triple(MotionEvent.ACTION_UP, to, 60L),
        )) {
            val event = MotionEvent.obtain(now, now + elapsed, action, x, dp(23).toFloat(), 0)
            try { current.dispatchTouchEvent(event) } finally { event.recycle() }
        }
    }

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).roundToInt()

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T = io.secure.keyboard.SessionTestAccess.field(name).apply { isAccessible = true }.get(service) as T

    private fun setField(name: String, value: Any) = io.secure.keyboard.SessionTestAccess.field(name).apply { isAccessible = true }.set(service, value)

    private fun invoke(name: String) = io.secure.keyboard.SessionTestAccess.method(name).apply { isAccessible = true }.invoke(service)

    private fun invokeTool(tool: String) = io.secure.keyboard.SessionTestAccess.method("openTool", String::class.java).apply { isAccessible = true }.invoke(service, tool)

    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        val root = File(base.noBackupFilesDir, "emoji-category-instrumentation-tests")
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getExternalCacheDirs(): Array<File> = emptyArray()
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("emoji-category-test-$name", mode)
    }
}
