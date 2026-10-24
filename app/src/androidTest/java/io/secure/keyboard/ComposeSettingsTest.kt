package io.secure.keyboard

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.accessibility.AccessibilityNodeInfo
import io.secure.keyboard.diagnostics.checkedMain

/** Production Compose semantics and isolated ViewModels; no retired View tags or fields. */
@Suppress("DEPRECATION")
abstract class ComposeSettingsTest : InstrumentationTestCase() {
    internal lateinit var fixture: SettingsUiFixture
    override fun setUp() { super.setUp(); fixture = SettingsUiFixture(instrumentation) }
    override fun tearDown() { try { fixture.close() } finally { super.tearDown() } }
    protected fun open(route: String) {
        instrumentation.startActivitySync(Intent(instrumentation.targetContext, SettingsActivity::class.java)
            .putExtra("page", route).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.waitForIdleSync()
    }
    protected fun nodes(): List<AccessibilityNodeInfo> {
        fun all(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null) emptyList() else
            listOf(node) + (0 until node.childCount).flatMap { all(node.getChild(it)) }
        return all(instrumentation.uiAutomation.rootInActiveWindow)
    }
    protected fun find(label: String) = nodes().firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }
    protected fun waitFor(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(50)
        }
        val visible = nodes().mapNotNull { if (it.isEditable) null else it.text?.toString() ?: it.contentDescription?.toString() }.distinct().take(30)
        runCatching {
            val image = instrumentation.uiAutomation.takeScreenshot()
            if (image != null) {
                val file = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "failure-${name}.png")
                try { file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
                finally { image.recycle() }
            }
        }
        fail("$message; visible labels: $visible")
    }
    protected fun await(label: String): AccessibilityNodeInfo {
        waitFor("Missing current Compose label: $label") { find(label) != null }
        return requireNotNull(find(label))
    }
    protected fun scroll(forward: Boolean = true): Boolean = nodes().firstOrNull { it.isScrollable }?.performAction(
        if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) ?: false
    protected fun reveal(label: String): AccessibilityNodeInfo {
        repeat(30) {
            find(label)?.let { return it }
            scroll(); SystemClock.sleep(100)
        }
        return await(label)
    }
    protected fun click(label: String) {
        reveal(label)
        waitFor("Enabled action: $label") {
            var node: AccessibilityNodeInfo? = find(label)
            var clicked = false
            while (node != null && !clicked) {
                if (node.isEnabled && node.isClickable) clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node = node.parent
            }
            clicked
        }
        instrumentation.waitForIdleSync()
    }
    protected fun fill(index: Int, value: String) {
        val inputs = nodes().filter { it.isEditable }
        assertTrue("Editable field $index", inputs.size > index)
        assertTrue(inputs[index].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        waitFor("Editable field $index reflects new value") { nodes().filter { it.isEditable }.getOrNull(index)?.text?.toString() == value }
        instrumentation.waitForIdleSync()
    }
    protected fun recreate() {
        val old = fixture.current
        instrumentation.checkedMain { requireNotNull(old).recreate() }
        waitFor("Activity recreation") { fixture.current != null && fixture.current !== old }
        instrumentation.waitForIdleSync()
    }
}
