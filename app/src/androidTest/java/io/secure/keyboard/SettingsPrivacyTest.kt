package io.secure.keyboard

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.test.InstrumentationTestCase
import androidx.compose.ui.text.input.TextFieldValue
import io.secure.keyboard.data.ApiConfiguration
import io.secure.keyboard.settings.ContentEdit
import io.secure.keyboard.settings.SettingsModel
import java.io.File

@Suppress("DEPRECATION")
class SettingsPrivacyTest : InstrumentationTestCase() {
    fun testDemandLoadingAndBackgroundInvalidatePendingReads() {
        val context = instrumentation.targetContext
        val folder = File(context.cacheDir, "settings-privacy-fixture").apply { mkdirs() }
        val app = object : Application() {
            init { attachBaseContext(context) }
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = folder
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("privacy-fixture-$name", mode)
        }
        lateinit var model: SettingsModel
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun settled() {
            val end = SystemClock.uptimeMillis() + 5000
            while (SystemClock.uptimeMillis() < end) {
                var busy = true
                main { busy = model.busy }
                if (!busy) return
                SystemClock.sleep(20)
            }
            fail("settings load did not finish")
        }
        try {
            main { model = SettingsModel(app) }
            model.content.addPhrase("private phrase")
            model.content.addClipboard("private clip")
            main { model.showPage("home") }
            settled()
            main {
                assertTrue(model.phrases.isEmpty()); assertTrue(model.clipboard.isEmpty())
                model.showPage("phrases")
            }
            settled()
            main {
                assertEquals("private phrase", model.phrases.single().text)
                assertTrue(model.clipboard.isEmpty())
                model.apiDraft = ApiConfiguration(googleKey = "synthetic-secret")
                model.edit = ContentEdit("phrases", first = TextFieldValue("unsaved"))
                model.showPage("clipboard")
                model.hide() // Pending result must not repopulate the cleared model.
                assertTrue(model.apiDraft.googleKey.isEmpty()); assertNull(model.edit)
            }
            SystemClock.sleep(200)
            main {
                assertTrue(model.clipboard.isEmpty()); assertTrue(model.phrases.isEmpty())
                model.showPage("clipboard")
            }
            settled()
            main { assertEquals("private clip", model.clipboard.single().text); model.hide() }
        } finally {
            main { androidx.lifecycle.ViewModelStore().apply { put("test", model); clear() } }
            folder.deleteRecursively()
            context.deleteSharedPreferences("privacy-fixture-settings")
        }
    }
}
