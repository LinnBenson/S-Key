package io.secure.keyboard

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.accessibility.AccessibilityNodeInfo
import io.secure.keyboard.data.SettingsStore

@Suppress("DEPRECATION")
class AiSettingsThemeTest : InstrumentationTestCase() {
    private var activity: Activity? = null
    private fun open(page: String) {
        instrumentation.runOnMainSync { activity?.finish() }
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, SettingsActivity::class.java)
            .putExtra("page", page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
    }
    private fun find(text: String, node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
        for (i in 0 until node.childCount) find(text, node.getChild(i))?.let { return it }
        return null
    }
    private fun waitFor(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) {
            find(text)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Missing UI: $text")
    }
    private fun tap(text: String) {
        var node: AccessibilityNodeInfo? = waitFor(text)
        while (node != null && !node.isClickable) node = node.parent
        assertNotNull(text, node)
        assertTrue(node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
    }
    override fun tearDown() {
        instrumentation.runOnMainSync { activity?.finish() }
        super.tearDown()
    }
    fun testManualAndSystemThemeAndOriginalInputUi() {
        val settings = SettingsStore(instrumentation.targetContext)
        val theme = settings.theme
        val manual = settings.manualDark
        try {
            open("behavior")
            tap("黑色主题"); assertEquals("dark", settings.theme)
            tap("跟随系统深浅色"); assertEquals("system", settings.theme)
            assertTrue(settings.isDark(true)); assertFalse(settings.isDark(false))
            tap("跟随系统深浅色"); assertEquals("dark", settings.theme)
            tap("白色主题"); assertEquals("light", settings.theme)
            assertFalse(settings.isDark(true))
            tap("自定义白色主题"); waitFor("主题预览")
            open("input"); waitFor("原版输入偏好")
            assertNull(find("纯五笔")); assertNull(find("九键拼音")); assertNull(find("拼音设置"))
        } finally {
            settings.theme = theme; settings.manualDark = manual
        }
    }
}
