package io.secure.keyboard

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.accessibility.AccessibilityNodeInfo
import io.secure.keyboard.data.*
import io.secure.keyboard.settings.SettingsModel
import java.io.File

/** Exercises actual DocumentsUI, real activity-result callbacks and production backup code. */
@Suppress("DEPRECATION")
class BackupFileUiTest : InstrumentationTestCase() {
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun nodes(): List<AccessibilityNodeInfo> {
        fun walk(n: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(n) +
            (0 until n.childCount).flatMap { n.getChild(it)?.let(::walk) ?: emptyList() }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::walk) ?: emptyList()
    }
    private fun awaitText(text: String): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + 12000
        while (SystemClock.uptimeMillis() < end) {
            nodes().firstOrNull { it.text?.toString() == text }?.let { return it }
            SystemClock.sleep(100)
        }
        error("Expected UI label missing: $text")
    }
    private fun click(text: String) {
        var node: AccessibilityNodeInfo? = awaitText(text)
        while (node != null && !node.isClickable) node = node.parent
        check(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Cannot click $text" }
        SystemClock.sleep(250)
    }
    private fun setFilename(name: String) {
        val end = SystemClock.uptimeMillis() + 12000
        while (SystemClock.uptimeMillis() < end) {
            val edit = nodes().firstOrNull { it.className == "android.widget.EditText" }
            if (edit != null) {
                check(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name)
                }))
                return
            }
            SystemClock.sleep(100)
        }
        error("System save filename field missing")
    }
    fun testActualSaveSelectRestoreCancelAndCorruptFile() {
        val base = instrumentation.targetContext
        val tag = "skey-ui-audit-${System.nanoTime()}"
        val name = "$tag.json"
        val root = File(base.noBackupFilesDir, tag).apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = root
            override fun getSharedPreferences(n: String, mode: Int) = base.getSharedPreferences("$tag-$n", mode)
        }
        val store = ContentStore(context)
        store.addDictionary("测试词", "ceshi")
        store.addDictionary("audit word", "audit", DictionaryKind.ENGLISH)
        store.addPhrase("SKEY_BACKUP_UI_PRIVATE_MARKER\nsecond line")
        store.setPhrasePinned(store.phrases().single().id, true)
        store.saveAiPreset(null, "审计", "保留正文")
        store.useEmoji("😀")
        var activity: SettingsActivity? = null
        try {
            activity = instrumentation.startActivitySync(Intent(base, SettingsActivity::class.java)
                .putExtra("page", "cleanup").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
            val screen = activity
            val getter = SettingsActivity::class.java.getDeclaredMethod("getModel").apply { isAccessible = true }
            lateinit var model: SettingsModel
            instrumentation.runOnMainSync {
                model = getter.invoke(screen) as SettingsModel
                SettingsModel::class.java.getDeclaredField("content").apply { isAccessible = true }.set(model, store)
            }
            awaitText("备份数据"); SystemClock.sleep(500)
            click("备份数据"); setFilename(name)
            val gate = java.util.concurrent.CountDownLatch(1)
            instrumentation.runOnMainSync { model.task { gate.await(5, java.util.concurrent.TimeUnit.SECONDS) } }
            click("保存"); SystemClock.sleep(500); gate.countDown()
            awaitText("数据备份完成"); click("知道了")
            val exported = shell("cat /sdcard/Download/$name")
            val json = org.json.JSONObject(exported)
            assertEquals(4, json.getJSONObject("data").length())
            assertTrue(exported.contains("SKEY_BACKUP_UI_PRIVATE_MARKER"))
            store.addPhrase("must be replaced")
            click("还原数据"); click("确认"); click(name)
            awaitText("数据还原完成"); click("知道了")
            assertEquals(1, store.phrases().size)
            assertTrue(store.phrases().single().pinned)
            assertEquals("测试词", store.dictionary().single().word)
            assertEquals("audit word", store.dictionary(DictionaryKind.ENGLISH).single().word)
            assertEquals("保留正文", store.aiPresets().single().prompt)
            assertEquals(listOf("😀"), store.recentEmoji())
            val before = File(root, "content/content.json").readBytes()
            click("还原数据"); click("取消")
            click("备份数据"); awaitText("保存"); shell("input keyevent 4"); SystemClock.sleep(500)
            awaitText("备份数据")
            click("还原数据"); click("确认"); awaitText(name); shell("input keyevent 4"); SystemClock.sleep(500)
            awaitText("备份数据")
            assertTrue(before.contentEquals(File(root, "content/content.json").readBytes()))
            val pipes = instrumentation.uiAutomation.executeShellCommandRw("tee /sdcard/Download/$name")
            ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { it.write("{".toByteArray()) }
            ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
            assertEquals("{", shell("cat /sdcard/Download/$name"))
            click("还原数据"); click("确认"); click(name)
            awaitText("还原失败，请检查备份文件格式和容量；未完成还原。")
            click("知道了")
            assertTrue(before.contentEquals(File(root, "content/content.json").readBytes()))
            assertFalse(shell("logcat -d -t 5000").contains("SKEY_BACKUP_UI_PRIVATE_MARKER"))
        } finally {
            activity?.let { screen -> instrumentation.runOnMainSync { screen.finish() } }
            shell("rm -f /sdcard/Download/$name")
            root.deleteRecursively(); base.deleteSharedPreferences("$tag-settings")
        }
    }
}
