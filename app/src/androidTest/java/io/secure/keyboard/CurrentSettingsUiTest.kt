package io.secure.keyboard

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.accessibility.AccessibilityNodeInfo

/** Current Compose screens: use their accessible labels rather than retired View tags. */
@Suppress("DEPRECATION")
class CurrentSettingsUiTest : InstrumentationTestCase() {
    private var activity: Activity? = null
    override fun tearDown() {
        activity?.let { instrumentation.runOnMainSync { it.finish() } }
        instrumentation.waitForIdleSync()
        super.tearDown()
    }
    private fun open(route: String) {
        activity?.let { instrumentation.runOnMainSync { it.finish() } }
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .putExtra("page", route).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
    }
    private fun find(label: String, node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
        node ?: return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (i in 0 until node.childCount) find(label, node.getChild(i))?.let { return it }
        return null
    }
    private fun await(label: String): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < end) {
            find(label)?.let { return it }
            SystemClock.sleep(100)
        }
        throw AssertionError("Missing accessible label: $label")
    }
    private fun click(label: String) {
        var node: AccessibilityNodeInfo? = await(label)
        while (node != null) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            node = node.parent
        }
        throw AssertionError("Not clickable: $label")
    }
    fun testAllCurrentSettingsRoutesOpen() {
        val routes = mapOf(
            "dashboard" to "S Key", "home" to "偏好设置", "input" to "输入方式",
            "keyboard" to "键盘操作", "behavior" to "主题与外观", "layout" to "布局配置",
            "toolbar" to "定制工具栏", "api" to "AI 设置", "voice" to "语音输入",
            "custom_ai" to "自定义 AI", "cleanup" to "内容清理", "licenses" to "开源声明",
            "clipboard" to "剪贴板", "phrases" to "常用语", "dictionary" to "中文自定义词库",
            "english_dictionary" to "英文自定义词库", "emoji" to "最近表情",
            "builtin_dictionaries" to "内置词库", "builtin_ai_prompts" to "内置 AI 提示词",
            "symbol_keyboard" to "符号键盘", "symbol_closure" to "符号自动闭合",
            "theme_light" to "自定义白色主题", "theme_dark" to "自定义黑色主题",
            "input_english" to "英文设置", "profile_outer_portrait" to "外屏 · 竖屏",
            "profile_outer_landscape" to "外屏 · 横屏", "profile_inner_portrait" to "内屏 · 竖屏",
            "profile_inner_landscape" to "内屏 · 横屏",
        )
        for ((route, title) in routes) { open(route); await(title) }
    }
    fun testLetterAlternatesEditorAndLanguageTabs() {
        open("symbol_keyboard")
        click("字母长按")
        await("q")
        click("q")
        await("长按符号（最多 8 个字符，可留空）")
        click("取消")
        click("英文键盘")
        click("q")
        await("英文 · 字母 q 长按")
        click("取消")
        click("第二页")
        await("恢复当前页默认符号")
    }
}
