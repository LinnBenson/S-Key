package io.secure.keyboard

import android.app.Activity
import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.Looper
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.test.ServiceTestCase
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import io.secure.keyboard.data.ContentStore
import io.secure.keyboard.data.SettingsStore
import io.secure.keyboard.ui.ContentEntryRow
import io.secure.keyboard.ui.KeyContainer
import io.secure.keyboard.ui.SpaceHoldTouchTracker
import java.io.File
import java.util.UUID

/** Correctness/ownership regressions, not a memory measurement or GC attribution test. */
@Suppress("DEPRECATION")
class HiddenPanelLifecycleTest : InstrumentationTestCase() {
    private lateinit var activity: Activity
    private lateinit var service: OfflineKeyboardService
    private lateinit var harness: Harness
    private lateinit var sandbox: Sandbox
    private lateinit var store: ContentStore
    private lateinit var host: Host
    private var fixtureClosed = false
    private val context get() = instrumentation.targetContext
    private val body get() = field<ViewGroup>("body")
    private val root get() = field<View>("root")
    private val panels = listOf("clipboard", "phrases", "emoji", "quickedit", "more", "layout", "voice")

    private class App(context: Context) : Application() {
        init { attachBaseContext(context) }
        override fun getApplicationContext(): Context = this
    }

    private class Harness : ServiceTestCase<OfflineKeyboardService>(OfflineKeyboardService::class.java) {
        fun create(app: Application): OfflineKeyboardService {
            setContext(app)
            setApplication(app)
            setUp()
            startService(Intent(app, OfflineKeyboardService::class.java))
            return service
        }
        fun close() = tearDown()
    }

    private class Host(view: View) : BaseInputConnection(view, true) {
        val committed = mutableListOf<String>()
        var contextActions = 0
        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            committed.add(text.toString())
            return true
        }
        override fun performContextMenuAction(id: Int): Boolean { contextActions++; return true }
        override fun getSelectedText(flags: Int): CharSequence? = null
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = ""
    }

    /** Records cancellation ordering without asserting or throwing from a framework callback. */
    private class PendingProbe(context: Context) : View(context) {
        var canceledWhileAttached = false
        var fired = false
        private val pending = Runnable { fired = true }
        fun enqueue() { check(post(pending)) }
        override fun onCancelPendingInputEvents() {
            canceledWhileAttached = canceledWhileAttached || (isAttachedToWindow && parent != null)
            removeCallbacks(pending)
            super.onCancelPendingInputEvents()
        }
    }

    override fun setUp() {
        super.setUp()
        try {
            sandbox = Sandbox(context)
            SettingsStore(sandbox).apply {
                englishCompletion = false
                fourCodeAutoCommit = false
                vibration = false
                keySound = false
            }
            store = ContentStore(sandbox)
            repeat(10) {
                store.addClipboard("fixture-copy-$it " + "完整内容🙂".repeat(32))
                store.addPhrase("fixture-phrase-$it " + "完整常用语🙂".repeat(32))
            }
            activity = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
                )
            )
            onMainChecked {
                harness = Harness()
                service = harness.create(App(sandbox))
                // This suite never captures, writes, or clears the real system clipboard.
                field<ClipboardManager>("clips").removePrimaryClipChangedListener(field("clipListener"))
                host = Host(View(activity))
                startEditor()
                activity.setContentView(service.onCreateInputView())
                set("active", true)
            }
            await("attached root and loaded dictionary", 60_000) {
                root.isAttachedToWindow && field<Boolean>("loaded") && body.height > 0
            }
        } catch (error: Throwable) {
            try { closeFixture() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    override fun tearDown() {
        try { closeFixture() } finally { super.tearDown() }
    }

    private fun closeFixture() {
        if (fixtureClosed) return
        fixtureClosed = true
        try {
            onMainChecked {
                try {
                    if (::harness.isInitialized) harness.close()
                } finally {
                    if (::activity.isInitialized) activity.finish()
                }
            }
            instrumentation.waitForIdleSync()
        } finally {
            if (::sandbox.isInitialized) sandbox.close()
        }
    }

    fun testClipboardAndPhraseRowsDetachOnHideAndBothFinishCallbacks() {
        for (panel in listOf("clipboard", "phrases")) for (endpoint in listOf("hide", "view", "input")) {
            onMainChecked {
                startEditor()
                set("active", true)
                showPanel(panel)
                val oldRoot = root
                val oldChild = body.getChildAt(0)
                val oldRows = rows()
                assertEquals(10, oldRows.size)
                assertTrue(oldRows.all { it.isAttachedToWindow })
                finish(endpoint)
                assertSame(oldRoot, root)
                assertEquals("keys", field<String>("panel"))
                assertEquals(0, body.childCount)
                assertNull(oldChild.parent)
                assertTrue(oldRows.none { it.isAttachedToWindow })
                assertTrue(rows().isEmpty())
                // Repeated endpoints remain cheap and cannot repopulate the released body.
                service.onFinishInputView(false)
                service.onFinishInput()
                assertEquals(0, body.childCount)
                assertEquals(10, store.clipboard().size)
                assertEquals(10, store.phrases().size)
                assertTrue(host.committed.isEmpty())
            }
        }
    }

    fun testCategoryClearAfterHideDoesNotReintroduceOldRowsOrDeleteOtherCategory() {
        for (panel in listOf("clipboard", "phrases")) {
            onMainChecked {
                // Refill the prior cleared category so both directions verify preservation.
                if (panel == "phrases") store.addClipboard("fixture category-clear survivor")
                val copiesBefore = store.clipboard()
                val phrasesBefore = store.phrases()
                assertTrue(copiesBefore.isNotEmpty() && phrasesBefore.isNotEmpty())
                startEditor()
                set("active", true)
                showPanel(panel)
                val oldRows = rows()
                assertEquals(10, oldRows.size)
                finish("hide")
                if (panel == "clipboard") store.clearClipboard() else store.clearPhrases()
                assertEquals(0, body.childCount)
                assertTrue(oldRows.none { it.isAttachedToWindow })
                if (panel == "clipboard") assertEquals(phrasesBefore, store.phrases())
                else assertEquals(copiesBefore, store.clipboard())
                showPanel(panel)
                assertTrue(rows().isEmpty())
                assertTrue(descendants(root).none { view -> oldRows.any { it === view } })
                assertTrue(host.committed.isEmpty())
                service.onFinishInput()
                assertEquals(0, body.childCount)
            }
        }
    }

    fun testAllFinishedNonKeyPanelsReleaseTheirBodyWithoutChangingStorage() {
        for (panel in panels) onMainChecked {
            startEditor()
            set("active", true)
            showPanel(panel) // Rendering voice alone starts no microphone/model session.
            val oldChild = body.getChildAt(0)
            assertNotNull(oldChild)
            service.onFinishInputView(false)
            assertNull(oldChild.parent)
            assertEquals(0, body.childCount)
            assertEquals("keys", field<String>("panel"))
            assertTrue(field<List<*>>("letterKeyViews").isEmpty())
            assertNull(field<KeyContainer?>("shiftKeyView"))
            assertNull(field<TextView?>("voiceText"))
            assertNull(field<TextView?>("voiceStatus"))
            assertEquals(10, store.clipboard().size)
            assertEquals(10, store.phrases().size)
            assertTrue(host.committed.isEmpty())
        }
    }

    fun testPendingViewInputIsCanceledBeforeDetachmentAtEveryEndpoint() {
        for (endpoint in listOf("hide", "view", "input", "editor")) {
            lateinit var probe: PendingProbe
            onMainChecked {
                startEditor()
                set("active", true)
                showPanel("phrases")
                probe = PendingProbe(activity)
                (body.getChildAt(0) as ViewGroup).addView(probe)
                assertTrue(probe.isAttachedToWindow)
                probe.enqueue()
                if (endpoint == "editor")
                    service.onCreateInputMethodInterface().restartInput(host, editorInfo())
                else finish(endpoint)
                assertTrue("Cancel must reach attached descendants before removal", probe.canceledWhileAttached)
                assertFalse(probe.isAttachedToWindow)
            }
            instrumentation.waitForIdleSync()
            onMainChecked {
                assertFalse("Detached panel must not run its pending input action", probe.fired)
                assertTrue(host.committed.isEmpty())
            }
        }
    }

    fun testNativePostedContentInsertPinAndDeleteAreRevokedByHide() {
        for (panel in listOf("clipboard", "phrases")) {
            onMainChecked { startEditor(); set("active", true); showPanel(panel) }
            await("laid out content row") { rows().firstOrNull()?.textView?.width?.let { it > 0 } == true }
            lateinit var expectedControl: String
            onMainChecked {
                expectedControl = rows().first().textView.text.toString()
                queueNativeClick(rows().first().textView)
                assertTrue("The control click must initially be pending", host.committed.isEmpty())
            }
            await("no-hide control click delivered") { host.committed == listOf(expectedControl) }
            onMainChecked {
                host.committed.clear()
                val row = rows().first()
                val beforeCopies = store.clipboard()
                val beforePhrases = store.phrases()
                // Native ACTION_UP posts PerformClick. Hide in the same UI turn before delivery.
                queueNativeClick(row.textView)
                queueNativeClick(row.pinAction)
                queueNativeClick(row.deleteAction)
                assertTrue("The clicks must still be pending", host.committed.isEmpty())
                assertEquals(beforeCopies, store.clipboard())
                assertEquals(beforePhrases, store.phrases())
                finish("hide")
            }
            instrumentation.waitForIdleSync()
            onMainChecked {
                assertTrue(host.committed.isEmpty())
                assertEquals(10, store.clipboard().size)
                assertEquals(10, store.phrases().size)
                assertTrue(store.clipboard().none { it.pinned })
                assertTrue(store.phrases().none { it.pinned })
                assertEquals(0, body.childCount)
            }
        }
    }

    fun testActiveRestartRestoresEveryPanelBeforeViewStartAndPreservesManualModeAndGeometry() {
        for (chinese in listOf(true, false)) for (symbolPage in listOf(false, true)) for (panel in panels) {
            onMainChecked {
                startEditor()
                set("active", true)
                set("chinese", chinese)
                set("symbols", symbolPage)
                set("symbolPage", symbolPage)
                showPanel("keys")
            }
            await("laid out keys before panel") { keysLaidOut() }
            lateinit var expected: List<List<Any>>
            lateinit var oldRoot: View
            lateinit var oldBody: ViewGroup
            onMainChecked {
                expected = keyGeometry()
                assertTrue(expected.isNotEmpty())
                oldRoot = root
                oldBody = body
                showPanel(panel)
                val oldPanel = body.getChildAt(0)
                service.onCreateInputMethodInterface().restartInput(host, editorInfo())
                assertSame(oldRoot, root)
                assertSame(oldBody, body)
                assertNull(oldPanel.parent)
                assertEquals("keys", field<String>("panel"))
                assertTrue("Keys must exist before optional view-start", body.childCount > 0)
                assertEquals(chinese, field<Boolean>("chinese"))
                assertEquals(symbolPage, field<Boolean>("symbols"))
                assertEquals(symbolPage, field<Boolean>("symbolPage"))
                assertTrue(rows().isEmpty())
                assertTrue(host.committed.isEmpty())
            }
            await("laid out keys after restart") { keysLaidOut() }
            onMainChecked { assertEquals("Key labels and exact geometry must remain unchanged", expected, keyGeometry()) }
        }
    }

    fun testRestartDuringHideRequestRestoresAlreadyReleasedBodyBeforeViewStart() {
        onMainChecked {
            set("chinese", false)
            set("symbols", true)
            set("symbolPage", true)
            showPanel("phrases")
            finish("hide")
            assertEquals(0, body.childCount)
            // The service may receive a restart before the platform's finish-view callback.
            assertTrue(field<Boolean>("active"))
            service.onCreateInputMethodInterface().restartInput(host, editorInfo())
            assertTrue(body.childCount > 0)
            assertEquals("keys", field<String>("panel"))
            assertFalse(field<Boolean>("chinese"))
            assertTrue(field<Boolean>("symbols"))
            assertTrue(field<Boolean>("symbolPage"))
            assertTrue(rows().isEmpty())
            assertTrue(host.committed.isEmpty())
        }
    }

    fun testPasswordRestartRestoresKeysOnlyAfterPolicyAndCancelsHeldVoice() {
        onMainChecked {
            set("chinese", true)
            set("symbols", true)
            set("symbolPage", true)
            showPanel("voice")
            val oldPreview = field<TextView>("voiceText")
            val oldStatus = field<TextView>("voiceStatus")
            set("voicePreview", "synthetic unfinished preview")
            oldPreview.text = "synthetic unfinished preview"
            set("heldVoice", true)
            set("heldVoiceWaitingKeys", true)
            val tracker = field<SpaceHoldTouchTracker>("spaceHoldTouch")
            val down = MotionEvent.obtain(1L, 1L, MotionEvent.ACTION_DOWN, 1f, 1f, 0)
            try { tracker.observe(down) } finally { down.recycle() }
            assertTrue(tracker.claim(0, tracker.version))
            val attachedPolicies = mutableListOf<List<Boolean>>()
            body.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View, child: View) {
                    attachedPolicies.add(listOf(field("secret"), field("chinese"), field("symbols")))
                }
                override fun onChildViewRemoved(parent: View, child: View) {}
            })
            try {
                service.onCreateInputMethodInterface().restartInput(host, editorInfo(
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                ))
            } finally { body.setOnHierarchyChangeListener(null) }
            assertEquals("Exactly one body rebuild, after password policy", listOf(listOf(true, true, true)), attachedPolicies)
            assertEquals("keys", field<String>("panel"))
            assertTrue(body.childCount > 0)
            assertFalse(field<Boolean>("heldVoice"))
            assertFalse(field<Boolean>("heldVoiceWaitingKeys"))
            assertFalse(field<Boolean>("voiceStarting"))
            assertFalse(field<Boolean>("voiceValidationPending"))
            assertTrue(field<ArrayDeque<*>>("voiceSegments").isEmpty())
            assertEquals("", oldPreview.text.toString())
            assertEquals("", oldStatus.text.toString())
            assertNull(field<TextView?>("voiceText"))
            assertNull(field<TextView?>("voiceStatus"))
            val up = MotionEvent.obtain(1L, 2L, MotionEvent.ACTION_UP, 1f, 1f, 0)
            try { assertTrue("Revoked held finger's terminal event stays consumed", root.dispatchTouchEvent(up)) }
            finally { up.recycle() }
            assertTrue(host.committed.isEmpty())
            assertEquals(0, host.contextActions)
        }
    }

    fun testOrdinaryLetterKeyHierarchySurvivesFinishAndActiveRestart() {
        onMainChecked {
            showPanel("keys")
            val oldRoot = root
            val oldBody = body
            val oldKeys = descendants(body).filterIsInstance<KeyContainer>()
            val oldChild = body.getChildAt(0)
            assertTrue(oldKeys.isNotEmpty())
            service.onCreateInputMethodInterface().restartInput(host, editorInfo())
            assertSame(oldChild, body.getChildAt(0))
            assertEquals(oldKeys, descendants(body).filterIsInstance<KeyContainer>())
            service.onFinishInputView(false)
            service.onFinishInput()
            assertSame(oldRoot, root)
            assertSame(oldBody, body)
            assertSame(oldChild, body.getChildAt(0))
            assertEquals(oldKeys, descendants(body).filterIsInstance<KeyContainer>())
            assertTrue(host.committed.isEmpty())
        }
    }

    fun testRestoredVisibleContentStillInsertsFullTextAndPreservesIndependentActions() {
        for (panel in listOf("clipboard", "phrases")) onMainChecked {
            startEditor()
            set("active", true)
            showPanel(panel)
            service.onFinishInputView(false)
            startEditor()
            set("active", true)
            showPanel(panel)
            val fullText = rows().first().textView.text.toString()
            val commitsBefore = host.committed.size
            rows().first().textView.performClick()
            assertEquals(commitsBefore + 1, host.committed.size)
            assertEquals(fullText, host.committed.last())
            rows().first().pinAction.performClick()
            assertEquals(commitsBefore + 1, host.committed.size)
            rows().first().deleteAction.performClick()
            assertEquals(commitsBefore + 1, host.committed.size)
            assertEquals(9, if (panel == "clipboard") store.clipboard().size else store.phrases().size)
        }
    }

    private fun editorInfo(inputType: Int = InputType.TYPE_CLASS_TEXT) = EditorInfo().apply {
        this.inputType = inputType
        initialSelStart = 0
        initialSelEnd = 0
    }

    private fun startEditor(inputType: Int = InputType.TYPE_CLASS_TEXT) {
        service.onCreateInputMethodInterface().startInput(host, editorInfo(inputType))
    }

    private fun showPanel(panel: String) {
        set("panel", panel)
        invoke("renderHeader")
        invoke("renderBody")
    }

    private fun finish(endpoint: String) = when (endpoint) {
        "hide" -> { field<View>("hideIcon").performClick(); Unit }
        "view" -> service.onFinishInputView(false)
        "input" -> service.onFinishInput()
        else -> error("Unknown endpoint")
    }

    private fun rows() = descendants(body).filterIsInstance<ContentEntryRow>()
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun keysLaidOut(): Boolean = descendants(body).filterIsInstance<KeyContainer>().let { keys ->
        keys.isNotEmpty() && keys.all { it.width > 0 && it.height > 0 }
    }

    private fun keyGeometry(): List<List<Any>> = descendants(body).filterIsInstance<KeyContainer>().map { key ->
        val xy = IntArray(2)
        key.getLocationInWindow(xy)
        assertTrue("Geometry needs laid-out keys", key.width > 0 && key.height > 0)
        listOf(key.contentDescription?.toString().orEmpty(), xy[0], xy[1], key.width, key.height)
    }

    private fun queueNativeClick(view: View) {
        assertTrue(view.isAttachedToWindow)
        assertTrue(view.width > 0 && view.height > 0)
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, time + if (action == MotionEvent.ACTION_UP) 1 else 0,
                action, view.width / 2f, view.height / 2f, 0)
            try { assertTrue(view.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
    }

    private fun await(description: String, timeout: Long = 20_000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) {
            var ready = false
            onMainChecked { ready = condition() }
            if (ready) return
            SystemClock.sleep(25)
        }
        fail("Timed out: $description")
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T = OfflineKeyboardService::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(service) as T
    private fun set(name: String, value: Any?) = OfflineKeyboardService::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.set(service, value)
    private fun invoke(name: String) = OfflineKeyboardService::class.java.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(service)

    /** Catch all test/setup/teardown failures inside UI work, then rethrow on the JUnit thread. */
    private fun onMainChecked(block: () -> Unit) {
        check(Looper.myLooper() != Looper.getMainLooper())
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try { block() } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }

    private class Sandbox(base: Context) : ContextWrapper(base), AutoCloseable {
        private val id = UUID.randomUUID().toString()
        private val directory = File(base.noBackupFilesDir, "hidden-panel-regression-$id")
        private val preferenceNames = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir() = File(directory, "no_backup").apply { mkdirs() }
        override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
        override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        override fun getExternalCacheDirs(): Array<File> = emptyArray()
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val ownName = "hidden-panel-regression-$id-$name"
            preferenceNames.add(ownName)
            return baseContext.getSharedPreferences(ownName, mode)
        }
        override fun close() {
            check(!directory.exists() || directory.deleteRecursively())
            preferenceNames.forEach { baseContext.deleteSharedPreferences(it) }
        }
    }
}
