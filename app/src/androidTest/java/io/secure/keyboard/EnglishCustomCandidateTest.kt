package io.secure.keyboard

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.test.ServiceTestCase
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.secure.keyboard.data.ContentStore
import io.secure.keyboard.data.DictionaryKind
import io.secure.keyboard.data.SettingsStore
import io.secure.keyboard.ui.CandidateGrid
import io.secure.keyboard.ui.CandidateStrip
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Production lookup and selection with an attached EditText and isolated bilingual content. */
@Suppress("DEPRECATION")
class EnglishCustomCandidateTest : InstrumentationTestCase() {
    private lateinit var activity: Activity
    private lateinit var harness: ServiceHarness
    private lateinit var service: OfflineKeyboardService
    private lateinit var store: ContentStore
    private lateinit var sandbox: ContentContext
    private lateinit var editor: EditText
    private lateinit var connection: ObservedConnection
    private lateinit var haptics: HapticRoot
    private var originalCompletion = true
    private var originalFourCode = false
    private var originalVibration = true
    private var originalVibrationStrength = 10
    private var originalKeySound = false
    private val releases = mutableListOf<CountDownLatch>()
    private val context get() = instrumentation.targetContext
    private val worker get() = field<ExecutorService>("worker")
    private val strip get() = field<CandidateStrip>("candidateStrip")
    private val row get() = strip.getChildAt(0) as LinearLayout
    private val english = DictionaryKind.ENGLISH

    private class ContentContext(base: Context) : ContextWrapper(base) {
        val root = File(base.noBackupFilesDir, "english-candidate-tests-${SystemClock.elapsedRealtimeNanos()}")
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = root.apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getExternalCacheDir(): File = File(root, "external-cache").apply { mkdirs() }
        override fun getExternalCacheDirs(): Array<File> = arrayOf(externalCacheDir)
    }

    private class ServiceHarness : ServiceTestCase<OfflineKeyboardService>(OfflineKeyboardService::class.java) {
        fun create(context: Context): OfflineKeyboardService {
            setContext(context)
            setApplication(context.applicationContext as Application)
            setUp()
            startService(Intent(context, OfflineKeyboardService::class.java))
            return service
        }
        fun close() = tearDown()
    }

    private class HapticRoot(context: Context) : FrameLayout(context) {
        var feedbackCount = 0
        override fun performHapticFeedback(feedbackConstant: Int): Boolean {
            feedbackCount++
            return true
        }
    }

    private class ObservedConnection(target: InputConnection) : InputConnectionWrapper(target, false) {
        val commits = mutableListOf<String>()
        val cursorOffsets = mutableListOf<Int>()
        var deletes = 0
        @Volatile var beforeRequest = 0
        @Volatile var afterPrefixRead: (() -> Unit)? = null
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
            beforeRequest = n
            val result = super.getTextBeforeCursor(n, flags)
            val callback = afterPrefixRead
            afterPrefixRead = null
            callback?.invoke()
            return result
        }
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            deletes++
            return super.deleteSurroundingText(beforeLength, afterLength)
        }
        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            val accepted = super.commitText(text, newCursorPosition)
            if (accepted) {
                commits.add(text.toString())
                cursorOffsets.add(newCursorPosition)
            }
            return accepted
        }
        fun reset() { commits.clear(); cursorOffsets.clear(); deletes = 0; afterPrefixRead = null }
    }

    override fun setUp() {
        super.setUp()
        val settings = SettingsStore(context)
        originalCompletion = settings.englishCompletion
        originalFourCode = settings.fourCodeAutoCommit
        originalVibration = settings.vibration
        originalVibrationStrength = settings.vibrationStrength
        originalKeySound = settings.keySound
        settings.englishCompletion = true
        settings.fourCodeAutoCommit = false
        settings.vibration = true
        settings.vibrationStrength = 10
        settings.keySound = false
        sandbox = ContentContext(context)
        store = ContentStore(sandbox)
        activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.runOnMainSync {
            harness = ServiceHarness()
            service = harness.create(context)
            setField("content", store)
            val keyboard = service.onCreateInputView()
            editor = EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_TEXT
                showSoftInputOnFocus = false
            }
            haptics = HapticRoot(activity).apply { addView(keyboard, FrameLayout.LayoutParams(-1, -1)) }
            activity.setContentView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(editor, LinearLayout.LayoutParams(-1, 120))
                addView(haptics, LinearLayout.LayoutParams(-1, 0, 1f))
            })
            setField("root", haptics)
            editor.requestFocus()
            connection = ObservedConnection(requireNotNull(editor.onCreateInputConnection(EditorInfo())))
            startEditor()
            setField("active", true)
        }
        waitFor("loaded dictionary and attached real host", 60_000) {
            field<Boolean>("loaded") && editor.isAttachedToWindow && haptics.isShown
        }
    }

    override fun tearDown() {
        releases.forEach { it.countDown() }
        if (::connection.isInitialized) connection.afterPrefixRead = null
        if (::service.isInitialized) drainWorker()
        instrumentation.runOnMainSync {
            if (::harness.isInitialized) harness.close()
            if (::activity.isInitialized) activity.finish()
        }
        val settings = SettingsStore(context)
        settings.englishCompletion = originalCompletion
        settings.fourCodeAutoCommit = originalFourCode
        settings.vibration = originalVibration
        settings.vibrationStrength = originalVibrationStrength
        settings.keySound = originalKeySound
        if (::sandbox.isInitialized) sandbox.root.deleteRecursively()
        super.tearDown()
    }

    fun testActualRankingKeepsBuiltinFirstAndLiteralExactBlockBeforePrefixTail() {
        queryEnglish("he")
        val baseline = result()
        assertTrue("Real built-in fixture needs multiple suggestions", baseline.size > 2)
        val exact = "eBay \uD840\uDC00"
        store.addDictionary("Prefix Phrase", "hello", english)
        store.addDictionary(exact, "HE", english)
        store.addDictionary(baseline[2], "he", english)
        store.addDictionary("Hello", "he", english)
        store.addDictionary(exact, "help", english)
        queryEnglish("he")
        val expected = (listOf(baseline.first(), exact, baseline[2], "Hello") + baseline + "Prefix Phrase").distinct()
        assertEquals(expected, result())
        tapStrip(exact)
        drainWorker()
        instrumentation.runOnMainSync {
            assertEquals("$exact ", editor.text.toString())
            assertEquals(1, haptics.feedbackCount)
        }
    }

    fun testLiteralCustomPhraseSelectionPreservesSuffixAndExactlyOneBoundarySpace() {
        val output = "eBay Support \uD83D\uDE00"
        store.addDictionary(output, "qqqqq", english)
        data class Example(val text: String, val result: String, val committed: String, val cursor: Int)
        for (sample in listOf(
            Example("QQQQQ", "$output ", "$output ", 1),
            Example("QQQQQ world", "$output world", output, 2),
            Example("QQQQQ,world", "$output ,world", "$output ", 1),
        )) {
            queryEnglish(sample.text, 5)
            assertEquals(listOf(output), result())
            tapStrip(output)
            drainWorker()
            instrumentation.runOnMainSync {
                assertEquals(sample.result, editor.text.toString())
                assertEquals(output.length + 1, editor.selectionStart)
                assertEquals(editor.selectionStart, editor.selectionEnd)
                assertEquals(listOf(sample.committed), connection.commits)
                assertEquals(listOf(sample.cursor), connection.cursorOffsets)
                assertEquals(1, connection.deletes)
            }
        }
    }

    fun testFiveLetterPrefixCandidateCommitsFromExpandedGridWithoutFourCodeAutoCommit() {
        store.addDictionary("Prefix Only", "qqqqqa", english)
        SettingsStore(context).fourCodeAutoCommit = true
        queryEnglish("")
        instrumentation.runOnMainSync { "qqqqq".forEach { type(it.toString()) } }
        awaitResults()
        instrumentation.runOnMainSync {
            assertEquals("qqqqq", editor.text.toString())
            assertEquals("", field<String>("code"))
            assertEquals(listOf("Prefix Only"), field<List<String>>("candidateTexts"))
            field<View>("hideIcon").performClick()
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            val grid = field<CandidateGrid>("candidateGrid")
            assertEquals("Prefix Only", grid.adapter.getItem(0))
            grid.getChildAt(0).performClick()
        }
        drainWorker()
        instrumentation.runOnMainSync {
            assertEquals("Prefix Only ", editor.text.toString())
            assertFalse(field<Boolean>("candidatesExpanded"))
        }
    }

    fun testChineseAndEnglishSameCodeAreIsolatedAndUnrelatedMutationKeepsCandidateValid() {
        val code = "qqqqq"
        store.addDictionary("中文独立", code, DictionaryKind.CHINESE)
        store.addDictionary("English Only", code, english)
        queryEnglish(code)
        assertEquals(listOf("English Only"), result())
        store.clearDictionary(DictionaryKind.CHINESE)
        tapStrip("English Only")
        drainWorker()
        instrumentation.runOnMainSync { assertEquals("English Only ", editor.text.toString()) }
        store.addDictionary("中文独立", code, DictionaryKind.CHINESE)
        instrumentation.runOnMainSync {
            resetHost("")
            setField("chinese", true)
            setField("code", code)
            invoke("refreshCandidates")
        }
        awaitResults()
        assertEquals(listOf("中文独立"), result())
        store.clearDictionary(english)
        tapStrip("中文独立")
        instrumentation.runOnMainSync { assertEquals("中文独立", editor.text.toString()) }
    }

    fun testCompletionSwitchAndSecretFieldsSuppressAllCustomEnglishSuggestions() {
        store.addDictionary("Custom", "qqqqq", english)
        queryEnglish("qqqqq")
        SettingsStore(context).englishCompletion = false
        instrumentation.runOnMainSync { invoke("refreshCandidates") }
        drainWorker()
        assertTrue(result().isEmpty())
        SettingsStore(context).englishCompletion = true
        queryEnglish("qqqqq")
        instrumentation.runOnMainSync {
            service.onCreateInputMethodInterface().startInput(connection, EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            })
            setField("chinese", false)
            invoke("refreshCandidates")
        }
        drainWorker()
        assertTrue(result().isEmpty())
        instrumentation.runOnMainSync { assertEquals("qqqqq", editor.text.toString()) }
    }

    fun testFortyLetterCodeWorksButLongerTokensAndNonwordBoundariesAreRejected() {
        val code = "q".repeat(40)
        store.addDictionary("Forty", code, english)
        queryEnglish(code)
        assertEquals(listOf("Forty"), result())
        assertEquals(41, connection.beforeRequest)
        for (text in listOf("q" + code, "q".repeat(90), "1$code", "_$code", "'$code", "’$code")) {
            queryEnglish(text)
            assertTrue("Must reject a partial token: $text", result().isEmpty())
        }
        queryEnglish("($code")
        assertEquals(listOf("Forty"), result())
        queryEnglish("${code}x", 40)
        assertTrue("Cursor inside a token must not replace just its beginning", result().isEmpty())
    }

    fun testDeletionBeforeTapClearsPersonalResultWithoutChangingHost() {
        store.addDictionary("Deleted", "qqqqq", english)
        queryEnglish("qqqqq")
        store.deleteDictionary(store.dictionary(english).single().id, english)
        instrumentation.runOnMainSync {
            assertFalse(invoke("choose", "Deleted") as Boolean)
            assertTrue(field<List<String>>("candidateTexts").isEmpty())
            assertEquals(0, row.childCount)
            assertEquals("qqqqq", editor.text.toString())
            assertEquals(0, connection.deletes)
            assertEquals(0, haptics.feedbackCount)
        }
    }

    fun testDeleteClearAndEditAfterAsyncPrefixReadCannotCommitOrDeleteHostText() {
        for (mutation in listOf("delete", "clear", "edit")) {
            store.clearDictionary(english)
            store.addDictionary("Private $mutation", "qqqqq", english)
            val id = store.dictionary(english).single().id
            queryEnglish("qqqqq, suffix", 5)
            val changed = CountDownLatch(1)
            connection.afterPrefixRead = {
                when (mutation) {
                    "delete" -> store.deleteDictionary(id, english)
                    "clear" -> store.clearDictionary(english)
                    else -> store.updateDictionary(id, "Replacement", "other", english)
                }
                changed.countDown()
            }
            tapStrip("Private $mutation")
            assertTrue("Mutation must occur after the asynchronous prefix read", changed.await(15, TimeUnit.SECONDS))
            drainWorker()
            instrumentation.runOnMainSync {
                assertEquals("$mutation must not change the actual editor", "qqqqq, suffix", editor.text.toString())
                assertEquals(5, editor.selectionStart)
                assertEquals(0, connection.deletes)
                assertTrue(connection.commits.isEmpty())
                assertEquals(0, haptics.feedbackCount)
                assertTrue(field<List<String>>("candidateTexts").isEmpty())
            }
        }
    }

    fun testDeletionAfterLookupButBeforePublicationRejectsCapturedPersonalResult() {
        store.addDictionary("Never Publish", "qqqqq", english)
        queryEnglish("qqqqq")
        instrumentation.runOnMainSync {
            invoke("refreshCandidates")
            val deleted = CountDownLatch(1)
            worker.execute { store.clearDictionary(english); deleted.countDown() }
            assertTrue("Lookup and deletion finish while result delivery is held", deleted.await(15, TimeUnit.SECONDS))
        }
        drainWorker()
        assertTrue(result().isEmpty())
        instrumentation.runOnMainSync {
            assertEquals("", field<String>("candidateQuery"))
            assertEquals("qqqqq", editor.text.toString())
        }
    }

    fun testDelayedCustomSelectionRejectsChangedPrefixModeEditorAndCompletionSetting() {
        store.addDictionary("Stale", "qqqqq", english)
        for (change in listOf("prefix", "mode", "editor", "completion")) {
            SettingsStore(context).englishCompletion = true
            queryEnglish("qqqqq")
            val release = gateWorker()
            var replacementEditor: EditText? = null
            onMainChecked {
                label("Stale").performClick()
                when (change) {
                    "prefix" -> { editor.setText("changed"); editor.setSelection(7) }
                    "mode" -> setField("chinese", true)
                    "editor" -> {
                        val replacement = EditText(activity).apply {
                            setText("other"); setSelection(5); showSoftInputOnFocus = false
                        }
                        (editor.parent as ViewGroup).addView(replacement, 0)
                        val replacementConnection = requireNotNull(
                            replacement.onCreateInputConnection(EditorInfo()))
                        service.onCreateInputMethodInterface().startInput(replacementConnection,
                            EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT })
                        assertFalse(field<Boolean>("active"))
                        assertSame(replacementConnection, service.currentInputConnection)
                        setField("active", true)
                        replacementEditor = replacement
                    }
                    else -> SettingsStore(context).englishCompletion = false
                }
            }
            release.countDown()
            drainWorker()
            onMainChecked {
                assertEquals(if (change == "prefix") "changed" else "qqqqq", editor.text.toString())
                assertTrue("$change must reject the delayed custom commit", connection.commits.isEmpty())
                assertEquals(0, connection.deletes)
                assertEquals(0, haptics.feedbackCount)
                replacementEditor?.let {
                    assertEquals("The still-visible replacement editor must remain unchanged", "other", it.text.toString())
                    assertEquals(5, it.selectionStart)
                    assertEquals(5, it.selectionEnd)
                }
            }
        }
    }

    fun testEnglishCustomTailRemainsReachableThroughProgressiveStripAndRecycledGrid() {
        val words = (1..55).map { "Prefix $it" }
        store.importDictionary(words.joinToString("\n") { "$it\tqqqqqa" }, kind = english)
        queryEnglish("qqqqq")
        assertEquals(words, result())
        waitFor("English custom tail appended through horizontal scrolling") {
            if (row.childCount < words.size) strip.scrollTo(row.width, 0)
            row.childCount == words.size
        }
        instrumentation.runOnMainSync {
            assertEquals(words.last(), (row.getChildAt(row.childCount - 1) as TextView).text.toString())
            label(words.last()).performClick()
        }
        drainWorker()
        instrumentation.runOnMainSync { assertEquals("${words.last()} ", editor.text.toString()) }
        queryEnglish("qqqqq")
        instrumentation.runOnMainSync { field<View>("hideIcon").performClick() }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            val grid = field<CandidateGrid>("candidateGrid")
            assertEquals(words.size, grid.adapter.count)
            grid.setSelection(words.lastIndex)
        }
        waitFor("custom tail grid cell") {
            val grid = field<CandidateGrid>("candidateGrid")
            (0 until grid.childCount).any { (grid.getChildAt(it) as TextView).text.toString() == words.last() }
        }
        instrumentation.runOnMainSync {
            val grid = field<CandidateGrid>("candidateGrid")
            (0 until grid.childCount).map { grid.getChildAt(it) as TextView }
                .first { it.text.toString() == words.last() }.performClick()
        }
        drainWorker()
        instrumentation.runOnMainSync { assertEquals("${words.last()} ", editor.text.toString()) }
    }

    fun testTouchStartedWhileEnglishCustomResultLoadsCannotBecomeAValidTap() {
        store.addDictionary("Touch Safe", "qqqqq", english)
        queryEnglish("qqqqq")
        val release = gateWorker()
        val down = SystemClock.uptimeMillis()
        lateinit var view: TextView
        instrumentation.runOnMainSync {
            invoke("refreshCandidates")
            view = label("Touch Safe")
            val event = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, 8f, 8f, 0)
            view.dispatchTouchEvent(event)
            event.recycle()
        }
        release.countDown()
        awaitResults()
        instrumentation.runOnMainSync {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 8f, 8f, 0)
            view.dispatchTouchEvent(event)
            event.recycle()
        }
        drainWorker()
        instrumentation.runOnMainSync {
            assertEquals("qqqqq", editor.text.toString())
            assertTrue(connection.commits.isEmpty())
            assertEquals(0, haptics.feedbackCount)
            view.performClick()
        }
        drainWorker()
        instrumentation.runOnMainSync { assertEquals("Touch Safe ", editor.text.toString()) }
    }

    private fun startEditor() {
        service.onCreateInputMethodInterface().startInput(connection,
            EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT })
    }

    private fun resetHost(text: String, cursor: Int = text.length) {
        startEditor()
        // startInput finishes the old input. This attached Activity harness receives no automatic
        // onStartInputView callback, so explicitly restore its visible state for the new editor.
        setField("active", true)
        editor.setText(text)
        editor.setSelection(cursor)
        connection.reset()
        haptics.feedbackCount = 0
    }

    private fun queryEnglish(text: String, cursor: Int = text.length) {
        instrumentation.runOnMainSync {
            resetHost(text, cursor)
            setField("chinese", false)
            invoke("refreshCandidates")
        }
        awaitResults()
    }

    private fun result(): List<String> {
        var values = emptyList<String>()
        instrumentation.runOnMainSync { values = field<List<String>>("candidateTexts").toList() }
        return values
    }

    private fun label(value: String): TextView =
        (0 until row.childCount).map { row.getChildAt(it) as TextView }.first { it.text.toString() == value }

    private fun tapStrip(value: String) = instrumentation.runOnMainSync { label(value).performClick() }

    private fun awaitResults() = waitFor("latest candidate result, including empty") {
        field<Int>("candidateGeneration") == field<Int>("generation")
    }

    private fun gateWorker(): CountDownLatch {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        releases.add(release)
        worker.execute { started.countDown(); release.await(15, TimeUnit.SECONDS) }
        assertTrue(started.await(15, TimeUnit.SECONDS))
        return release
    }

    private fun drainWorker() {
        val done = CountDownLatch(1)
        worker.execute { done.countDown() }
        assertTrue(done.await(15, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }

    private fun waitFor(description: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            var success = false
            instrumentation.runOnMainSync { success = condition() }
            if (success) return
            SystemClock.sleep(25)
        }
        fail("Timed out: $description")
    }

    private fun type(value: String) {
        OfflineKeyboardService::class.java.getDeclaredMethod("type", String::class.java,
            Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(service, value, false)
    }

    private fun invoke(name: String, value: String? = null): Any? {
        val method = if (value == null) OfflineKeyboardService::class.java.getDeclaredMethod(name)
            else OfflineKeyboardService::class.java.getDeclaredMethod(name, String::class.java)
        method.isAccessible = true
        return if (value == null) method.invoke(service) else method.invoke(service, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T = OfflineKeyboardService::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(service) as T

    /** Report fixture assertions through JUnit rather than terminating Android's main thread. */
    private fun onMainChecked(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try { block() } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }

    private fun setField(name: String, value: Any) {
        OfflineKeyboardService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }
}
