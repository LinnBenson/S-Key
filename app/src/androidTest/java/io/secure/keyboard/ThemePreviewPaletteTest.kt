package io.secure.keyboard

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.secure.keyboard.data.SettingsStore

@Suppress("DEPRECATION")
class ThemePreviewPaletteTest : InstrumentationTestCase() {
    private var activity: Activity? = null
    private fun find(text: String, prefix: Boolean = false, node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
        if (node == null) return null
        val strings = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
        if (node.isVisibleToUser && strings.any { if (prefix) it.startsWith(text) else it == text }) return node
        for (i in 0 until node.childCount) find(text, prefix, node.getChild(i))?.let { return it }
        return null
    }
    private fun scroll(node: AccessibilityNodeInfo?, forward: Boolean): Boolean {
        if (node == null) return false
        for (i in 0 until node.childCount) if (scroll(node.getChild(i), forward)) return true
        return node.isScrollable && node.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }
    private fun reach(text: String, prefix: Boolean = false): AccessibilityNodeInfo {
        repeat(12) {
            find(text,prefix)?.let { return it }
            scroll(instrumentation.uiAutomation.rootInActiveWindow,true)
            instrumentation.waitForIdleSync(); SystemClock.sleep(120)
        }
        error("Missing $text")
    }
    private fun tap(text: String) {
        var node: AccessibilityNodeInfo? = reach(text)
        while (node != null && !node.isClickable) node = node.parent
        assertNotNull(text,node); assertTrue(node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync(); SystemClock.sleep(150)
    }
    private fun open() {
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, SettingsActivity::class.java)
            .putExtra("page", "theme_light").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
    }
    override fun tearDown() {
        instrumentation.runOnMainSync { activity?.finish() }
        super.tearDown()
    }
    fun testUiDesignSlidersPersistAcrossRecreation() {
        val store = SettingsStore(instrumentation.targetContext)
        val original = listOf(store.keyBorderWidth, store.keyHorizontalGap, store.keyVerticalGap, store.keyCornerRadius, store.enterCornerRadius)
        try {
            open()
            for ((label, value) in listOf("边框宽度" to 2, "横向间距" to 10, "竖向间距" to 12, "按钮圆角" to 3, "回车键圆角" to 22)) {
                val slider = reach(label)
                assertTrue(slider.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                    android.os.Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value.toFloat()) }))
                instrumentation.waitForIdleSync()
            }
            assertEquals(2, store.keyBorderWidth)
            assertEquals(10, store.keyHorizontalGap)
            assertEquals(12, store.keyVerticalGap)
            assertEquals(3, store.keyCornerRadius)
            assertEquals(22, store.enterCornerRadius)
            repeat(4) { scroll(instrumentation.uiAutomation.rootInActiveWindow, false) }
            instrumentation.waitForIdleSync(); SystemClock.sleep(150)
            val bounds = Rect().also { reach("边框宽度").getBoundsInScreen(it) }
            val time = SystemClock.uptimeMillis()
            for ((action, fraction) in listOf(MotionEvent.ACTION_DOWN to .5f, MotionEvent.ACTION_MOVE to .65f, MotionEvent.ACTION_MOVE to .75f, MotionEvent.ACTION_UP to .75f)) {
                val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action,
                    bounds.left + bounds.width() * fraction, bounds.exactCenterY(), 0)
                instrumentation.sendPointerSync(event); event.recycle()
            }
            instrumentation.waitForIdleSync(); SystemClock.sleep(150)
            val draggedBorder = store.keyBorderWidth
            assertTrue("Dragging increases the border", draggedBorder in 3..4)
            assertEquals(10, store.keyHorizontalGap)
            assertEquals(12, store.keyVerticalGap)
            instrumentation.runOnMainSync { activity?.recreate() }
            instrumentation.waitForIdleSync(); SystemClock.sleep(500)
            assertEquals(draggedBorder, store.keyBorderWidth)
            assertEquals(10, store.keyHorizontalGap)
            assertEquals(12, store.keyVerticalGap)
            assertEquals(3, store.keyCornerRadius)
            assertEquals(22, store.enterCornerRadius)
        } finally {
            store.keyBorderWidth = original[0]
            store.keyHorizontalGap = original[1]
            store.keyVerticalGap = original[2]
            store.keyCornerRadius = original[3]
            store.enterCornerRadius = original[4]
            instrumentation.targetContext.getSharedPreferences("settings", 0).edit().commit()
        }
    }

    fun testFullPreviewPaletteDraftCancelAndSave() {
        val store = SettingsStore(instrumentation.targetContext)
        val original = store.themeTokens(false)
        val dark = store.themeTokens(true)
        try {
            open()
            reach("预览按键：q"); reach("预览按键：m"); reach("预览按键：回车")
            tap("候选栏"); reach("nihao"); reach("你好")
            tap("页面与键盘背景")
            // Actual palette touch; selecting a draft must not mutate saved settings.
            val palette = reach("饱和度 ", true)
            val rect = Rect(); palette.getBoundsInScreen(rect)
            val start = SystemClock.uptimeMillis()
            for ((action, x, y) in listOf(Triple(MotionEvent.ACTION_DOWN, .3f, .3f), Triple(MotionEvent.ACTION_MOVE, .7f, .5f), Triple(MotionEvent.ACTION_UP, .7f, .5f))) {
                val event = MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,rect.left+rect.width()*x,rect.top+rect.height()*y,0)
                instrumentation.sendPointerSync(event); event.recycle()
            }
            instrumentation.waitForIdleSync()
            assertEquals(original,store.themeTokens(false))
            tap("选择颜色 #336699")
            assertEquals(original,store.themeTokens(false))
            tap("取消")
            assertEquals(original,store.themeTokens(false))
            tap("页面与键盘背景")
            tap("选择颜色 #336699")
            tap("保存颜色")
            assertEquals("#336699",store.themeTokens(false).keyboardBackground)
            assertEquals(dark,store.themeTokens(true))
            instrumentation.runOnMainSync { activity?.recreate() }
            instrumentation.waitForIdleSync()
            assertEquals("#336699",store.themeTokens(false).keyboardBackground)
        } finally { store.saveThemeTokens(false,original) }
    }
}
