package io.secure.keyboard

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ScrollView
import io.secure.keyboard.ui.DictionaryEntryRow

/** Actual companion-activity screenshots, using temporary synthetic content only. */
@Suppress("DEPRECATION")
class CompanionScreensTest : InstrumentationTestCase() {
    private var activity: Activity? = null
    private lateinit var fixture: SettingsUiFixture
    private lateinit var evidence: SettingsUiEvidence
    private var route = "home"

    override fun setUp() {
        super.setUp()
        fixture = SettingsUiFixture(instrumentation)
        evidence = SettingsUiEvidence(instrumentation, "companion-regression")
    }

    override fun tearDown() {
        try { fixture.close() } finally { super.tearDown() }
    }

    fun testThemeEditorsAndCompactDictionaryActivity() {
        val store = fixture.content
        val addedIds = mutableListOf<String>()
        fun addSynthetic(word: String, reading: String) {
            val before = store.dictionary().map { it.id }.toSet()
            store.addDictionary(word, reading)
            addedIds.addAll(store.dictionary().filter { it.id !in before }.map { it.id })
        }
        try {
            openPage("theme_light")
            saveScreenshot("18-custom-theme-editor-light.png")
            scrollToBottom()
            saveScreenshot("18-custom-theme-editor-light-roles.png")
            openPage("theme_dark")
            saveScreenshot("18-custom-theme-editor-dark.png")

            addSynthetic("好的", "vbr")
            addSynthetic("很长的词条内容仅用于测试".repeat(8), "abcdefghijklmnopqrstuvwxyz".repeat(8))
            val fullEmojiWord = "👨‍👩‍👧‍👦🙂𠀀".repeat(8)
            val fullEmojiReading = "nihao".repeat(40)
            addSynthetic(fullEmojiWord, fullEmojiReading)
            openPage("dictionary")
            scrollToBottom()
            instrumentation.runOnMainSync {
                val rows = descendants(requireNotNull(activity).window.decorView).filterIsInstance<DictionaryEntryRow>()
                assertTrue("Real dictionary activity must render the synthetic rows", rows.size >= 3)
                for (row in rows.takeLast(3)) {
                    assertTrue("Edit must fit inside actual activity row", row.editAction.right <= row.width)
                    assertTrue("Delete must fit inside actual activity row", row.deleteAction.right <= row.width)
                    val editBounds = Rect()
                    val deleteBounds = Rect()
                    assertTrue("Edit action must be visible in actual activity", row.editAction.getGlobalVisibleRect(editBounds))
                    assertTrue("Delete action must be visible in actual activity", row.deleteAction.getGlobalVisibleRect(deleteBounds))
                    assertTrue("Actual actions must not overlap", editBounds.right <= deleteBounds.left)
                }
            }
            saveScreenshot("19-compact-dictionary-activity.png")
            instrumentation.runOnMainSync {
                val row = descendants(requireNotNull(activity).window.decorView).filterIsInstance<DictionaryEntryRow>().last()
                assertTrue("Actual edit action must open its dialog", row.editAction.performClick())
            }
            instrumentation.waitForIdleSync()
            val deadline = SystemClock.elapsedRealtime() + 5_000
            var values = emptyList<String>()
            while (SystemClock.elapsedRealtime() < deadline) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                values = if (root == null) emptyList() else try { editorValues(root) } finally { root.recycle() }
                if (values.contains(fullEmojiWord) && values.contains(fullEmojiReading)) break
                SystemClock.sleep(100)
            }
            assertTrue("Editing must retain the entire untruncated emoji value", values.contains(fullEmojiWord))
            assertTrue("Editing must retain the entire untruncated code", values.contains(fullEmojiReading))
            saveScreenshot("19-dictionary-full-value-edit.png")
        } finally {
            activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
            addedIds.forEach { store.deleteDictionary(it) }
        }
    }

    private fun openPage(page: String) {
        route = page
        activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
        activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, SettingsActivity::class.java).apply {
                putExtra("page", page)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        )
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var focused = false
            instrumentation.runOnMainSync { focused = requireNotNull(activity).hasWindowFocus() }
            if (focused) {
                instrumentation.waitForIdleSync()
                SystemClock.sleep(300)
                return
            }
            SystemClock.sleep(100)
        }
        fail("Companion activity did not receive focus for $page")
    }

    private fun editorValues(node: AccessibilityNodeInfo): List<String> {
        val values = mutableListOf<String>()
        if (node.className?.toString() == "android.widget.EditText") values.add(node.text?.toString().orEmpty())
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try { values.addAll(editorValues(child)) } finally { child.recycle() }
        }
        return values
    }

    private fun descendants(view: View): List<View> {
        val all = mutableListOf(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) all.addAll(descendants(view.getChildAt(index)))
        return all
    }

    private fun scrollToBottom() {
        instrumentation.runOnMainSync {
            descendants(requireNotNull(activity).window.decorView).filterIsInstance<ScrollView>().firstOrNull()?.fullScroll(View.FOCUS_DOWN)
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(350)
    }

    private fun saveScreenshot(name: String) {
        evidence.capture(requireNotNull(activity), route, name.removeSuffix(".png"), fixture.config.theme, name.removeSuffix(".png"))
    }
}
