package io.secure.keyboard

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import android.test.InstrumentationTestCase
import androidx.compose.ui.text.input.TextFieldValue
import io.secure.keyboard.data.ContentStore
import io.secure.keyboard.diagnostics.checkedMain
import io.secure.keyboard.settings.ContentEdit
import io.secure.keyboard.settings.SettingsModel
import java.io.File

@Suppress("DEPRECATION")
class CustomAiSettingsTest : InstrumentationTestCase() {
    fun testSettingsSaveEditSortDeleteAndClearUsingIsolatedContent() {
        val context = instrumentation.targetContext
        val folder = File(context.cacheDir, "custom-ai-settings-fixture").apply { deleteRecursively(); mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = File(folder, "private").apply { mkdirs() }
            override fun getCacheDir() = File(folder, "cache").apply { mkdirs() }
            override fun getExternalCacheDirs() = emptyArray<File>()
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("custom-ai-fixture-$name", mode)
        }
        val store = ContentStore(isolated)
        val activity = instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java)
            .putExtra("page", "custom_ai").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
        val model = SettingsActivity::class.java.getDeclaredMethod("getModel").apply { isAccessible = true }.invoke(activity) as SettingsModel
        val contentField = SettingsModel::class.java.getDeclaredField("content").apply { isAccessible = true }
        val original = model.content
        fun await(condition: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + 10000
            while (SystemClock.uptimeMillis() < end) {
                var ready = false
                instrumentation.checkedMain { ready = condition() }
                if (ready) { instrumentation.waitForIdleSync(); return }
                SystemClock.sleep(30)
            }
            fail("Custom AI settings did not settle")
        }
        fun screenshot(name: String) {
            instrumentation.waitForIdleSync()
            SystemClock.sleep(250)
            instrumentation.checkedMain {
                val view = activity.window.decorView
                val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                try {
                    view.draw(android.graphics.Canvas(bitmap))
                    File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                } finally { bitmap.recycle() }
            }
        }
        try {
            await { !model.busy }
            instrumentation.checkedMain { contentField.set(model, store); model.refresh() }
            await { !model.busy }
            for ((name, prompt) in listOf("邮件润色" to "保留原意，用自然、礼貌的语气润色邮件。", "简短回复" to "将原文缩短到两句话，只输出修改后的内容。")) {
                instrumentation.checkedMain { model.edit = ContentEdit("custom_ai", first = TextFieldValue(name), second = TextFieldValue(prompt)) }
                screenshot("custom-ai-editor.png")
                instrumentation.checkedMain { model.saveEdit() }
                await { !model.busy && model.edit == null }
            }
            assertEquals(2, store.aiPresets().size)
            screenshot("custom-ai-management.png")
            val first = store.aiPresets().first()
            instrumentation.checkedMain { model.edit = ContentEdit("custom_ai", first.id, TextFieldValue("正式邮件"), TextFieldValue("改为正式语气"), "high") }
            screenshot("custom-ai-reasoning-editor.png")
            instrumentation.checkedMain { model.saveEdit() }
            await { !model.busy && model.edit == null }
            assertEquals("改为正式语气", store.aiPresets().first().prompt)
            assertEquals("high", ContentStore(isolated).aiPresets().first().reasoningEffort)
            val backup = java.io.ByteArrayOutputStream().also { store.writeBackup(it) }.toByteArray()
            store.restoreBackup(backup.inputStream())
            assertEquals("high", store.aiPresets().first().reasoningEffort)
            val legacy = org.json.JSONObject(String(backup, Charsets.UTF_8))
            val rows = legacy.getJSONObject("data").getJSONArray("aiPresets")
            for (i in 0 until rows.length()) rows.getJSONObject(i).remove("reasoningEffort")
            store.restoreBackup(legacy.toString().byteInputStream())
            assertTrue(store.aiPresets().all { it.reasoningEffort == "low" })
            try { store.saveAiPreset(first.id, first.name, first.prompt, "invalid"); fail("Invalid effort") }
            catch (_: IllegalArgumentException) { }
            instrumentation.checkedMain { model.task { store.moveAiPreset(first.id, 1) } }
            await { !model.busy }
            assertEquals("正式邮件", model.aiPresets.last().name)
            instrumentation.checkedMain { model.task { store.deleteAiPreset(first.id) } }
            await { !model.busy }
            assertEquals(1, model.aiPresets.size)
            instrumentation.checkedMain { model.task { store.clearAll() } }
            await { !model.busy }
            assertTrue(model.aiPresets.isEmpty())
        } finally {
            instrumentation.checkedMain { contentField.set(model, original); activity.finish() }
            folder.deleteRecursively()
            context.deleteSharedPreferences("custom-ai-fixture-settings")
        }
    }
}
