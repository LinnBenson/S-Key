package io.secure.keyboard.settings

import android.app.Application
import android.test.InstrumentationTestCase
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.secure.keyboard.diagnostics.checkedMain
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DictionaryImportLifecycleTest : InstrumentationTestCase() {
    fun testPickerResultWaitsForBusyModelAndKeepsImportKind() {
        val base = instrumentation.targetContext
        val directory = File(base.cacheDir, "import-lifecycle-${System.nanoTime()}").apply { mkdirs() }
        val app = object : Application() {
            init { attachBaseContext(base) }
            override fun getNoBackupFilesDir() = directory
            override fun getApplicationContext() = this
        }
        val state = SavedStateHandle(mapOf("importKind" to "english"))
        lateinit var model: BuiltinDictionaryModel
        val finished = CountDownLatch(1)
        val owner = ViewModelStore()
        try {
            instrumentation.checkedMain {
                model = BuiltinDictionaryModel(app, state)
                owner.put("fixture", model)
                assertTrue(model.busy)
                assertEquals("english", model.pendingImportKind)
                model.run("已导入", waitUntilReady = true) {
                    model.repository.addFile("skey_english_high.tsv", model.pendingImportKind,
                        "hello\t7\n".byteInputStream(), replaceExisting = true)
                    finished.countDown()
                }
            }
            // This standalone ViewModel has no Compose Activity/frame loop. Deliver the
            // snapshot notifications that a production composition normally schedules.
            val importDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (finished.count > 0 && System.nanoTime() < importDeadline) {
                instrumentation.checkedMain { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
                finished.await(20, TimeUnit.MILLISECONDS)
            }
            assertTrue("Picker result must not be dropped during initialization; status=${model.status}", finished.count == 0L)
            val lib = model.repository.libraries().first { it.filename == "skey_english_high.tsv" }
            assertEquals("hello\t7\n", model.repository.page(lib.id, 0).text)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var busy = true
            while (busy && System.nanoTime() < deadline) {
                instrumentation.checkedMain { busy = model.busy }
                if (busy) Thread.sleep(20)
            }
            assertFalse(busy)
        } finally {
            instrumentation.checkedMain { owner.clear() }
            directory.deleteRecursively()
        }
    }
}
