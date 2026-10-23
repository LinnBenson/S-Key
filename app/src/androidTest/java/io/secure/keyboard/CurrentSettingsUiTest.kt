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
    private fun awaitMissing(label: String) {
        val end = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < end) {
            if (find(label) == null) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Unexpected accessible label: $label")
    }
    private fun click(label: String) {
        var node: AccessibilityNodeInfo? = await(label)
        while (node != null) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            node = node.parent
        }
        throw AssertionError("Not clickable: $label")
    }
    fun testEmojiShortcutAddEditAndDelete() {
        val prefs = instrumentation.targetContext.getSharedPreferences("settings", 0)
        val key = io.secure.keyboard.data.EmojiShortcutStore.KEY
        val previous = prefs.getString(key, null)
        try {
            prefs.edit().putString(key, "[]").commit()
            open("emoji_shortcuts"); click("添加快捷表情")
            fun fill(index: Int, value: String) {
                val inputs = ArrayList<AccessibilityNodeInfo>()
                fun visit(node: AccessibilityNodeInfo?) {
                    node ?: return
                    if (node.isEditable) inputs.add(node)
                    for (child in 0 until node.childCount) visit(node.getChild(child))
                }
                visit(instrumentation.uiAutomation.rootInActiveWindow)
                assertEquals(2, inputs.size)
                assertTrue(inputs[index].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                    android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
                instrumentation.waitForIdleSync()
            }
            await("保存"); fill(0, "🧑‍🔬"); fill(1, "科学家, science,科学家"); click("保存")
            await("科学家,science")
            click("🧑‍🔬"); await("删除快捷表情")
            fill(1, "实验,lab"); click("保存"); await("实验,lab")
            val store = io.secure.keyboard.data.EmojiShortcutStore(instrumentation.targetContext)
            assertEquals(listOf("实验", "lab"), store.entries().single().keywords)
            click("🧑‍🔬"); click("删除快捷表情"); await("暂无快捷表情")
            assertTrue(store.entries().isEmpty())
        } finally {
            prefs.edit().apply { if (previous == null) remove(key) else putString(key, previous) }.commit()
        }
    }

    fun testShortcutCorruptionRecoveryAndSearch() {
        val prefs = instrumentation.targetContext.getSharedPreferences("settings", 0)
        val key = io.secure.keyboard.data.EmojiShortcutStore.KEY
        val previous = prefs.getString(key, null)
        try {
            prefs.edit().putString(key, "broken-json").commit()
            open("emoji_shortcuts"); click("恢复默认快捷表情"); click("取消")
            assertEquals("broken-json", prefs.getString(key, null))
            click("恢复默认快捷表情"); click("恢复默认"); await("开心,高兴,笑脸")
            fun search(value: String) {
                fun editable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                    node ?: return null
                    if (node.isEditable) return node
                    for (child in 0 until node.childCount) editable(node.getChild(child))?.let { return it }
                    return null
                }
                val input = requireNotNull(editable(instrumentation.uiAutomation.rootInActiveWindow))
                assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                    android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
                instrumentation.waitForIdleSync()
            }
            search("笑哭"); await("😂"); awaitMissing("😀")
            search("👍"); await("点赞,赞"); awaitMissing("😂")
            search("no-such-keyword"); await("没有匹配的表情")
            search(""); await("开心,高兴,笑脸")
        } finally {
            prefs.edit().apply { if (previous == null) remove(key) else putString(key, previous) }.commit()
        }
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
            "emoji_shortcuts" to "快捷 emoji 表情", "symbol_keyboard" to "符号键盘", "symbol_closure" to "符号自动闭合",
            "theme_light" to "自定义白色主题", "theme_dark" to "自定义黑色主题",
            "input_english" to "英文设置", "profile_outer_portrait" to "外屏 · 竖屏",
            "profile_outer_landscape" to "外屏 · 横屏", "profile_inner_portrait" to "内屏 · 竖屏",
            "profile_inner_landscape" to "内屏 · 横屏",
        )
        for ((route, title) in routes) { open(route); await(title) }
    }
    fun testEditorDialogsShareInsetsAndActionAlignment() {
        val cases=listOf(
            Triple("custom_ai",listOf("添加自定义 AI"),"添加自定义 AI"),
            Triple("emoji_shortcuts",listOf("添加快捷表情"),"添加快捷表情"),
            Triple("symbol_closure",listOf("添加规则"),"添加规则"),
            Triple("builtin_ai_prompts",listOf("自定义 AI 统一提示词"),"自定义 AI 统一提示词"),
            Triple("symbol_keyboard",listOf("字母长按","q"),"中文 · 字母 q")
        )
        var expectedLeft: Int?=null
        var expectedRight: Int?=null
        for((index,test) in cases.withIndex()) {
            open(test.first);test.second.forEach(::click)
            val cancel=await("取消")
            val save=await("保存")
            // Buttons may expose text children: ascend to their clickable semantic bounds.
            fun button(node: AccessibilityNodeInfo): android.graphics.Rect {
                var current=node
                while(!current.isClickable && current.parent!=null) current=current.parent
                return android.graphics.Rect().also { current.getBoundsInScreen(it) }
            }
            val l=button(cancel);val r=button(save)
            assertTrue("Actions must share a baseline",kotlin.math.abs(l.centerY()-r.centerY())<=2)
            assertTrue("Actions must be equally wide",kotlin.math.abs(l.width()-r.width())<=2)
            expectedLeft?.let { assertTrue("Consistent left inset",kotlin.math.abs(it-l.left)<=2) }
            expectedRight?.let { assertTrue("Consistent right inset",kotlin.math.abs(it-r.right)<=2) }
            expectedLeft=l.left;expectedRight=r.right
            instrumentation.waitForIdleSync()
            SystemClock.sleep(600) // Let the dialog's window fade finish before visual evidence.
            // Capture visual evidence with adb screencap separately; some emulator GPU
            // drivers crash the UiAutomation binder during takeScreenshot().
            click("取消")
        }
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
