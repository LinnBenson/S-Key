package io.secure.keyboard.ui

import android.content.Intent
import android.graphics.Color
import android.test.InstrumentationTestCase
import android.text.TextUtils
import android.view.View
import android.widget.FrameLayout
import io.secure.keyboard.MainActivity
import io.secure.keyboard.data.ContentStore

@Suppress("DEPRECATION")
class DictionaryEntryRowTest : InstrumentationTestCase() {
    fun testLongCodeContentAndEmojiKeepBothActionsVisible() {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        )
        lateinit var host: FrameLayout
        instrumentation.runOnMainSync {
            host = FrameLayout(activity)
            activity.setContentView(host)
        }
        instrumentation.waitForIdleSync()
        try {
        runTestOnUiThread {
            val context = activity
            val density = context.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()
            val entries =
                listOf(
                    ContentStore.DictionaryEntry("short", "好的", "vbr"),
                    ContentStore.DictionaryEntry(
                        "long",
                        "很长的完整词条内容".repeat(100),
                        "abcdefghijklmnopqrstuvwxyz".repeat(100),
                    ),
                    ContentStore.DictionaryEntry(
                        "emoji",
                        "👨‍👩‍👧‍👦🙂𠀀".repeat(100),
                        "nihao".repeat(200),
                    ),
                )
            for (entry in entries) for (width in listOf(160, 220, 320, 480)) {
                var edited: ContentStore.DictionaryEntry? = null
                var deleted: ContentStore.DictionaryEntry? = null
                val row =
                    DictionaryEntryRow(
                        context,
                        entry,
                        Color.BLACK,
                        Color.GRAY,
                        Color.WHITE,
                        Color.BLUE,
                        Color.RED,
                        onEdit = { edited = it },
                        onDelete = { deleted = it },
                    )
                host.removeAllViews()
                host.addView(row)
                row.measure(
                    View.MeasureSpec.makeMeasureSpec(dp(width), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(dp(52), View.MeasureSpec.EXACTLY),
                )
                row.layout(0, 0, row.measuredWidth, row.measuredHeight)
                assertEquals(dp(48), row.editAction.width)
                assertEquals(dp(48), row.deleteAction.width)
                assertEquals(dp(48), row.editAction.height)
                assertEquals(dp(48), row.deleteAction.height)
                assertTrue(row.editAction.left >= row.wordView.right)
                assertTrue(row.deleteAction.left >= row.editAction.right)
                assertTrue(row.deleteAction.right <= row.width - row.paddingRight)
                assertEquals(row.codeView.left, row.wordView.left)
                assertTrue(row.codeView.top >= row.wordView.bottom)
                assertEquals(1, row.codeView.lineCount)
                assertEquals(1, row.wordView.lineCount)
                assertEquals(TextUtils.TruncateAt.END, row.codeView.ellipsize)
                assertEquals(TextUtils.TruncateAt.END, row.wordView.ellipsize)
                assertEquals(entry.reading, row.codeView.text.toString())
                assertEquals(entry.word, row.wordView.text.toString())
                if (entry.id != "short") {
                    assertTrue(row.codeView.layout.getEllipsisCount(0) > 0)
                    assertTrue(row.wordView.layout.getEllipsisCount(0) > 0)
                }
                assertEquals("编辑词条", row.editAction.contentDescription)
                assertEquals("删除词条", row.deleteAction.contentDescription)
                val info = row.editAction.createAccessibilityNodeInfo()
                assertEquals("android.widget.Button", info.className)
                assertTrue("Attached action View must be clickable", row.editAction.isClickable)
                assertTrue("Attached action accessibility node must be clickable", info.isClickable)
                info.recycle()
                assertTrue(row.editAction.performClick())
                assertTrue(row.deleteAction.performClick())
                assertSame(entry, edited)
                assertSame(entry, deleted)
            }
        }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
