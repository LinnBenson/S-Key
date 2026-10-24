package io.secure.keyboard.diagnostics

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Debug
import android.os.Looper
import android.test.InstrumentationTestCase
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnectionWrapper
import io.secure.keyboard.data.ContentStore
import io.secure.keyboard.data.SettingsStore
import java.io.File
import org.json.JSONObject

@Suppress("DEPRECATION")
class CleanInstallAuditTest : InstrumentationTestCase() {
    fun testPreviewSpacingHostReadsStayOffMainThread() {
        val fixture = LifetimeServiceFixture(instrumentation)
        try {
            fixture.open()
            var readsOnMain = 0
            val connection = object : InputConnectionWrapper(fixture.host, false) {
                override fun setComposingText(text: CharSequence?, cursor: Int) = true
                override fun finishComposingText() = true
                override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
                    if (Looper.myLooper() == Looper.getMainLooper()) readsOnMain++
                    return "ani"
                }
                override fun getTextAfterCursor(n: Int, flags: Int): CharSequence {
                    if (Looper.myLooper() == Looper.getMainLooper()) readsOnMain++
                    return "b"
                }
            }
            instrumentation.checkedMain {
                val settings = SettingsStore(fixture.sandbox)
                settings.editorPreview = true
                settings.autoChineseSpacing = true
                fixture.service.onCreateInputMethodInterface().startInput(connection, EditorInfo().apply {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT
                    initialSelStart = 0; initialSelEnd = 0
                })
                fixture.set("active", true)
                fixture.service.session.editorPreview.update(connection, "ni", 0, 0)
                readsOnMain = 0
                fixture.service.session.prefetchSpacingContext()
            }
            fixture.await("Spacing reads completed off main") { fixture.service.session.spacingContext.has(connection) }
            instrumentation.checkedMain { fixture.service.session.commitInputText("你") }
            fixture.await("Spaced preview commit reaches host") { fixture.host.commits == 1 }
            instrumentation.sendStatus(0, Bundle().apply { putInt("preview_spacing_main_thread_reads", readsOnMain) })
            assertEquals("Surrounding-text reads must not block the keyboard main thread", 0, readsOnMain)
        } finally { fixture.close() }
    }

    fun testPreviewSpacingDoesNotLeaveDelayedCommitAfterEditorEnds() {
        val fixture = LifetimeServiceFixture(instrumentation)
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            fixture.open()
            val worker = fixture.field<java.util.concurrent.ExecutorService>("worker")
            val entered = java.util.concurrent.CountDownLatch(1)
            worker.execute { entered.countDown(); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            instrumentation.checkedMain {
                val settings = SettingsStore(fixture.sandbox)
                settings.editorPreview = true; settings.autoChineseSpacing = true
                fixture.set("active", true)
                fixture.service.session.editorPreview.update(fixture.host, "ni", 0, 0)
                assertTrue(fixture.service.session.commitInputText("你"))
                assertEquals(1, fixture.host.commits)
                fixture.service.onFinishInputView(false)
                fixture.service.onFinishInput()
            }
            release.countDown()
            fixture.drain(worker)
            instrumentation.waitForIdleSync()
            assertEquals("Retired editor must never receive another delayed edit", 1, fixture.host.commits)
        } finally { release.countDown(); fixture.close() }
    }

    fun testClipboardAllocationWithNearLimitDictionaryAndEncryptedFiles() {
        val base = instrumentation.targetContext
        val root = File(base.cacheDir, "clean-audit-${System.nanoTime()}").apply { mkdirs() }
        val sandbox = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = File(root, "data").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getExternalCacheDirs(): Array<File> = emptyArray()
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("clean_audit_$name", mode)
        }
        val prefs = sandbox.getSharedPreferences("settings", 0)
        prefs.edit().clear().commit()
        try {
            val store = ContentStore(sandbox)
            fun allocated() = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: -1L
            fun batch(label: String): Long {
                val before = allocated()
                val start = android.os.SystemClock.elapsedRealtime()
                repeat(200) { store.addClipboard("private-clean-audit-$label-$it") }
                val total = allocated() - before
                val info = Debug.MemoryInfo().also(Debug::getMemoryInfo)
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("clipboard_allocation", JSONObject().put("stage", label).put("copies", 200)
                        .put("allocatedBytes", total).put("elapsedMillis", android.os.SystemClock.elapsedRealtime()-start)
                        .put("pssKiB", info.totalPss).put("fdCount", File("/proc/self/fd").list()?.size ?: -1)
                        .put("threadCount", File("/proc/self/task").list()?.size ?: -1).toString())
                })
                assertEquals(10, store.clipboard().size)
                return total
            }
            batch("empty")
            store.importDictionary((0 until 100).joinToString("\n") { "${"词".repeat(39000)}$it\tcode" })
            val dictionary = File(sandbox.noBackupFilesDir, "content/content.json")
            val stamp = dictionary.lastModified()
            val size = dictionary.length()
            val bytes = batch("large")
            assertEquals(stamp, dictionary.lastModified())
            assertEquals(size, dictionary.length())
            assertTrue("Copying must not allocate a dictionary-sized tree per event", bytes < 200L * 2 * 1024 * 1024)
            store.addPhrase("private-clean-audit-phrase")
            store.useEmoji("🙂")
            for (name in listOf("clipboard", "phrases", "emoji")) {
                val file = File(sandbox.noBackupFilesDir, "private-content/$name.enc")
                assertTrue(file.isFile)
                assertFalse(String(file.readBytes()).contains("private-clean-audit"))
            }
            assertFalse(dictionary.readText().contains("private-clean-audit"))
        } finally { root.deleteRecursively(); prefs.edit().clear().commit() }
    }
}
