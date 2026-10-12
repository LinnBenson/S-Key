package io.secure.keyboard

import io.secure.keyboard.diagnostics.checkedMain
import android.os.SystemClock
import android.widget.ListView
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.test.InstrumentationTestCase
import android.test.ServiceTestCase
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import io.secure.keyboard.data.ContentStore
import io.secure.keyboard.data.SettingsStore
import io.secure.keyboard.ui.ContentEntryRow

/** Production keyboard panels with a real editor, isolated app content, and no system clip writes. */
@Suppress("DEPRECATION")
class KeyboardContentPanelTest : InstrumentationTestCase() {
    private lateinit var activity: Activity
    private lateinit var service: OfflineKeyboardService
    private lateinit var harness: Harness
    private lateinit var sandbox: IsolatedContext
    private lateinit var store: ContentStore
    private lateinit var editor: EditText
    private var originalVibration = true
    private val context get() = instrumentation.targetContext

    private class Harness : ServiceTestCase<OfflineKeyboardService>(OfflineKeyboardService::class.java) {
        fun create(context: Context): OfflineKeyboardService {
            setContext(context)
            setApplication(context.applicationContext as Application)
            setUp()
            startService(Intent(context, OfflineKeyboardService::class.java))
            return service
        }
        fun close() = tearDown()
    }

    override fun setUp() {
        super.setUp()
        sandbox = IsolatedContext(context)
        sandbox.root.deleteRecursively()
        sandbox.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        store = ContentStore(sandbox)
        originalVibration = SettingsStore(context).vibration
        SettingsStore(context).vibration = false
        activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            )
        )
        instrumentation.checkedMain {
            harness = Harness()
            service = harness.create(context)
            setField("content", store)
            editor = EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                showSoftInputOnFocus = false
            }
            val keyboard = service.onCreateInputView()
            activity.setContentView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(editor, LinearLayout.LayoutParams(-1, 100))
                addView(keyboard, LinearLayout.LayoutParams(-1, 0, 1f))
            })
            editor.requestFocus()
            val info = EditorInfo()
            val connection = requireNotNull(editor.onCreateInputConnection(info))
            service.onCreateInputMethodInterface().startInput(connection, info)
            setField("active", true)
        }
        instrumentation.waitForIdleSync()
    }

    override fun tearDown() {
        instrumentation.checkedMain {
            if (::harness.isInitialized) harness.close()
            if (::activity.isInitialized) activity.finish()
        }
        SettingsStore(context).vibration = originalVibration
        sandbox.root.deleteRecursively()
        sandbox.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        super.tearDown()
    }

    fun testClipboardFullTextInsertionAndPinDeleteAreIndependentInKeyboard() {
        val longText = "完整剪贴板\n第二行\r\n👨‍👩‍👧‍👦𠀀".repeat(80)
        store.addClipboard(longText)
        store.addClipboard("newer copy")
        val firstId = store.clipboard().first().id
        instrumentation.checkedMain {
            showPanel("clipboard")
        }
        awaitContent()
        instrumentation.checkedMain {
            assertEquals(listOf("newer copy", longText), rows().map { it.textView.text.toString() })
            rows().last().textView.performClick()
            assertEquals(longText, editor.text.toString())
            rows().last().pinAction.performClick()
        }
        awaitContent()
        instrumentation.checkedMain {
            assertEquals(longText, editor.text.toString())
            assertEquals(longText, rows().first().textView.text.toString())
            assertEquals("取消置顶剪贴板记录", rows().first().pinAction.contentDescription)
            assertTrue(ContentStore(sandbox).clipboard().first().pinned)
            showPanel("keys")
            showPanel("clipboard")
        }
        awaitContent()
        instrumentation.checkedMain {
            assertEquals(longText, rows().first().textView.text.toString())
            rows().first().deleteAction.performClick()
        }
        awaitContent()
        instrumentation.checkedMain {
            assertEquals("Deleting a row must not insert its text", longText, editor.text.toString())
            assertEquals(listOf("newer copy"), rows().map { it.textView.text.toString() })
            assertFalse(store.clipboard().any { it.id == firstId })
        }
    }

    fun testPhrasePinningReturnsToManualOrderAndDeleteNeverInserts() {
        val longText = "完整常用语\n所有文字都会插入🙂".repeat(80)
        store.addPhrase("first phrase")
        store.addPhrase(longText)
        store.addPhrase("last phrase")
        val pinnedId = store.phrases()[1].id
        instrumentation.checkedMain {
            showPanel("phrases")
        }
        awaitContent()
        instrumentation.checkedMain {
            rows()[1].pinAction.performClick()
        }
        awaitContent()
        instrumentation.checkedMain {
            assertEquals("", editor.text.toString())
            assertEquals(longText, rows().first().textView.text.toString())
            assertEquals("取消置顶常用语", rows().first().pinAction.contentDescription)
            rows().first().textView.performClick()
            assertEquals(longText, editor.text.toString())
            rows().first().pinAction.performClick()
        }
        awaitContent()
        instrumentation.checkedMain {
            assertEquals(listOf("first phrase", longText, "last phrase"), rows().map { it.textView.text.toString() })
            assertFalse(ContentStore(sandbox).phrases()[1].pinned)
            store.movePhrase(pinnedId, 0)
            showPanel("phrases")
        }
        awaitContent()
        instrumentation.checkedMain {
            assertEquals(longText, rows().first().textView.text.toString())
            rows().first().deleteAction.performClick()
        }
        awaitContent()
        instrumentation.checkedMain {
            assertEquals(longText, editor.text.toString())
            assertEquals(listOf("first phrase", "last phrase"), rows().map { it.textView.text.toString() })
        }
    }

    fun testReopeningAfterCategoryClearRemovesPinnedRows() {
        store.addClipboard("copy")
        store.addPhrase("phrase")
        store.setClipboardPinned(store.clipboard().single().id, true)
        store.setPhrasePinned(store.phrases().single().id, true)
        instrumentation.checkedMain {
            showPanel("clipboard")
        }
        awaitContent()
        instrumentation.checkedMain {
            assertEquals(1, rows().size)
            store.clearClipboard()
            showPanel("keys")
            showPanel("clipboard")
        }
        awaitContent()
        instrumentation.checkedMain {
            assertTrue(rows().isEmpty())
            showPanel("phrases")
        }
        awaitContent()
        instrumentation.checkedMain {
            assertEquals(1, rows().size)
            store.clearPhrases()
            showPanel("keys")
            showPanel("phrases")
        }
        awaitContent()
        instrumentation.checkedMain {
            assertTrue(rows().isEmpty())
            assertEquals("", editor.text.toString())
        }
    }


    fun testThousandRowsStayVirtualizedAcrossRepeatedOpenAndClose() {
        SettingsStore(sandbox).clipboardLimit = 1000
        val values = org.json.JSONArray()
        repeat(1000) { values.put(org.json.JSONObject().put("id", "fixture-$it").put("text", "copy-$it")) }
        File(sandbox.noBackupFilesDir, "content/content.json").writeText(
            org.json.JSONObject().put("clipboard", values).toString())
        val samples = mutableListOf<String>()
        repeat(20) { cycle ->
            instrumentation.checkedMain { showPanel("clipboard") }
            awaitContent()
            instrumentation.checkedMain {
                assertTrue("Only viewport rows may be attached", rows().size in 1..30)
                val list = rows().first().parent as ListView
                assertEquals(1000, list.count)
                list.setSelection(999)
            }
            instrumentation.waitForIdleSync()
            instrumentation.checkedMain {
                assertTrue(rows().size in 1..30)
                service.onFinishInputView(false)
                assertTrue(rows().isEmpty())
                setField("active", true)
            }
            if (cycle % 5 == 4) {
                val memory = android.os.Debug.MemoryInfo()
                android.os.Debug.getMemoryInfo(memory)
                samples.add("cycle=${cycle + 1},pssKiB=${memory.totalPss},nativeBytes=${android.os.Debug.getNativeHeapAllocatedSize()}")
            }
        }
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("skey_content_memory", samples.joinToString("; "))
        })
    }

    fun testLateBackgroundLoadCannotRestoreHiddenPanel() {
        store.addClipboard("fixture")
        val worker = OfflineKeyboardService::class.java.getDeclaredField("worker").apply {
            isAccessible = true
        }.get(service) as java.util.concurrent.ExecutorService
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        worker.execute { entered.countDown(); release.await(30, java.util.concurrent.TimeUnit.SECONDS) }
        assertTrue(entered.await(60, java.util.concurrent.TimeUnit.SECONDS))
        try {
            instrumentation.checkedMain {
                showPanel("clipboard")
                service.onFinishInputView(false)
                assertTrue(rows().isEmpty())
            }
        } finally { release.countDown() }
        awaitContent()
        instrumentation.checkedMain { assertTrue(rows().isEmpty()) }
    }

    private fun awaitContent() {
        val worker = OfflineKeyboardService::class.java.getDeclaredField("worker").apply {
            isAccessible = true
        }.get(service) as java.util.concurrent.ExecutorService
        worker.submit {}.get(60, java.util.concurrent.TimeUnit.SECONDS)
        instrumentation.waitForIdleSync()
        worker.submit {}.get(60, java.util.concurrent.TimeUnit.SECONDS)
        val deadline = SystemClock.elapsedRealtime() + 60_000
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            var ready = false
            instrumentation.checkedMain {
                val body = OfflineKeyboardService::class.java.getDeclaredField("body").apply {
                    isAccessible = true
                }.get(service) as View
                fun loading(view: View): Boolean =
                    (view is TextView && view.text.toString() == "正在读取…") ||
                    (view is ViewGroup && (0 until view.childCount).any { loading(view.getChildAt(it)) })
                ready = !loading(body)
                if (ready && rows().isEmpty()) {
                    fun listPending(view: View): Boolean =
                        (view is ListView && view.count > 0 && view.childCount == 0) ||
                        (view is ViewGroup && (0 until view.childCount).any { listPending(view.getChildAt(it)) })
                    ready = !listPending(body)
                }
            }
            if (ready) return
            SystemClock.sleep(30)
        }
        fail("Content panel failed to load")
    }

    private fun showPanel(panel: String) {
        setField("panel", panel)
        OfflineKeyboardService::class.java.getDeclaredMethod("renderBody").apply {
            isAccessible = true
        }.invoke(service)
        val body = OfflineKeyboardService::class.java.getDeclaredField("body").apply {
            isAccessible = true
        }.get(service) as View
        fun hasManagement(view: View): Boolean =
            (view is TextView && view.text.toString() == "管理") ||
                (view is ViewGroup && (0 until view.childCount).any { hasManagement(view.getChildAt(it)) })
        if (panel == "clipboard" || panel == "phrases")
            assertFalse("Inline content panels must not include a management button", hasManagement(body))
    }

    private fun rows(): List<ContentEntryRow> {
        val body = OfflineKeyboardService::class.java.getDeclaredField("body").apply {
            isAccessible = true
        }.get(service) as View
        fun collect(view: View): List<ContentEntryRow> = when (view) {
            is ContentEntryRow -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { collect(view.getChildAt(it)) }
            else -> emptyList()
        }
        return collect(body)
    }

    private fun setField(name: String, value: Any) {
        OfflineKeyboardService::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }.set(service, value)
    }

    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        val root = File(base.noBackupFilesDir, "content-panel-instrumentation-tests")
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getExternalCacheDirs(): Array<File> = emptyArray()
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("content-panel-test-$name", mode)
    }
}
