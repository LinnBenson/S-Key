package io.secure.keyboard

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.test.ServiceTestCase
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import io.secure.keyboard.data.ContentStore
import io.secure.keyboard.data.SettingsStore
import io.secure.keyboard.ui.CandidateStrip
import io.secure.keyboard.ui.CandidateGrid
import io.secure.keyboard.ui.ToolIcon

/**
 * Real attached views and production service query/selection paths with a controlled synthetic
 * editor. Worker gates make intermediate loading frames and late-result races deterministic.
 * The existing KeyboardUiTest separately covers real selected-IME EditText/WebView integration.
 */
@Suppress("DEPRECATION")
class CandidateRenderingTest : InstrumentationTestCase() {
    private lateinit var activity: Activity
    private lateinit var service: OfflineKeyboardService
    private lateinit var harness: ServiceHarness
    private lateinit var connection: RecordingConnection
    private var originalCompletion = true
    private var originalChinese = true
    private var originalFourCode = false
    private var originalKeySound = false
    private var originalVibration = true
    private var originalVibrationStrength = 15
    private val releases = mutableListOf<CountDownLatch>()
    private val context get() = instrumentation.targetContext
    private val worker get() = field<ExecutorService>("worker")
    private val strip get() = field<CandidateStrip>("candidateStrip")
    private val row get() = strip.getChildAt(0) as LinearLayout

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

    private class RecordingConnection(view: View) : BaseInputConnection(view, true) {
        @Volatile var before = ""
        val committed = mutableListOf<String>()
        var deleted = 0
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = before.takeLast(n)
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
        override fun getSelectedText(flags: Int): CharSequence? = null
        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            committed.add(text.toString())
            before += text
            return true
        }
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            deleted += beforeLength
            before = before.dropLast(beforeLength)
            return true
        }
        override fun beginBatchEdit() = true
        override fun endBatchEdit() = true
    }

    override fun setUp() {
        super.setUp()
        val settings = SettingsStore(context)
        originalChinese = settings.lastChineseLanguage
        settings.lastChineseLanguage = true
        originalCompletion = settings.englishCompletion
        originalFourCode = settings.fourCodeAutoCommit
        originalKeySound = settings.keySound
        settings.keySound = false
        originalVibration = settings.vibration
        originalVibrationStrength = settings.vibrationStrength
        settings.englishCompletion = true
        settings.fourCodeAutoCommit = false
        activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
        )
        onMainChecked {
            harness = ServiceHarness()
            service = harness.create(context)
            connection = RecordingConnection(View(activity))
            startEditor(connection)
            activity.setContentView(service.onCreateInputView())
            setField("active", true)
        }
        waitFor("dictionary loaded and production view attached", 60_000) {
            field<Boolean>("loaded") && field<View>("root").isAttachedToWindow
        }
    }

    override fun tearDown() {
        releases.forEach { it.countDown() }
        onMainChecked {
            if (::harness.isInitialized) harness.close()
            if (::activity.isInitialized) activity.finish()
        }
        SettingsStore(context).englishCompletion = originalCompletion
        SettingsStore(context).lastChineseLanguage = originalChinese
        SettingsStore(context).fourCodeAutoCommit = originalFourCode
        SettingsStore(context).keySound = originalKeySound
        SettingsStore(context).vibration = originalVibration
        SettingsStore(context).vibrationStrength = originalVibrationStrength
        super.tearDown()
    }

    fun testSlowHostThousandRefreshesRetainOneCurrentPendingQueryAndSkipObsoleteEditors() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1).also { releases.add(it) }
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val obsoleteReads = java.util.concurrent.atomic.AtomicInteger()
        val onMain = java.util.concurrent.atomic.AtomicBoolean()
        lateinit var slow: InputConnection
        onMainChecked {
            slow = object : BaseInputConnection(View(activity), true) {
                override fun getSelectedText(flags: Int): CharSequence? = null
                override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
                override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
                    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) onMain.set(true)
                    reads.incrementAndGet()
                    entered.countDown()
                    check(release.await(30, TimeUnit.SECONDS)) { "Slow candidate host gate timed out" }
                    return "obsolete"
                }
            }
            service.onCreateInputMethodInterface().startInput(slow, EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT
            })
            setField("active", true)
            setField("chinese", false)
            invoke("refreshCandidates")
        }
        assertTrue("Slow host must own the real worker", entered.await(20, TimeUnit.SECONDS))
        val mailbox = field<io.secure.keyboard.concurrent.LatestTaskMailbox>("candidateQueries")
        val commandDone = CountDownLatch(2)
        val commandOrder = java.util.Collections.synchronizedList(mutableListOf<Int>())
        worker.execute { commandOrder.add(1); commandDone.countDown() }
        worker.execute { commandOrder.add(2); commandDone.countDown() }
        onMainChecked {
            repeat(10) {
                val obsolete = object : BaseInputConnection(View(activity), true) {
                    override fun getSelectedText(flags: Int): CharSequence? = null
                    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
                    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
                        obsoleteReads.incrementAndGet(); return "never"
                    }
                }
                service.onCreateInputMethodInterface().startInput(obsolete, EditorInfo())
                setField("active", true)
                setField("chinese", false)
                repeat(100) {
                    invoke("refreshCandidates")
                    assertEquals(1, mailbox.snapshot().running)
                    assertEquals(1, mailbox.snapshot().pending)
                }
            }
            startEditor(connection)
            setField("active", true)
            setField("chinese", false)
            connection.before = "he"
            invoke("refreshCandidates")
        }
        release.countDown()
        awaitResults()
        waitFor("candidate mailbox drained") { !mailbox.snapshot().scheduled }
        assertTrue(commandDone.await(20, TimeUnit.SECONDS))
        onMainChecked {
            assertEquals(1, reads.get())
            assertEquals(0, obsoleteReads.get())
            assertFalse(onMain.get())
            assertEquals(listOf(1, 2), commandOrder)
            assertEquals("he", field<String>("candidateQuery"))
            assertSame(connection, field<InputConnection>("candidateConnection"))
            assertTrue(connection.committed.isEmpty())
        }
    }

    fun testEmptyPrivacyAndFinishImmediatelyDropPendingDerivedPayloads() {
        val mailbox = field<io.secure.keyboard.concurrent.LatestTaskMailbox>("candidateQueries")
        val release = gateWorker()
        onMainChecked {
            fun enqueue() {
                setField("active", true)
                setField("secret", false)
                setField("chinese", true)
                setField("code", "nihao")
                invoke("refreshCandidates")
                assertEquals(1, mailbox.snapshot().pending)
            }
            enqueue()
            setField("code", "")
            invoke("refreshCandidates")
            assertEquals(0, mailbox.snapshot().pending)
            enqueue()
            setField("secret", true)
            invoke("refreshCandidates")
            assertEquals(0, mailbox.snapshot().pending)
            enqueue()
            service.onFinishInputView(false)
            assertEquals(0, mailbox.snapshot().pending)
            service.onFinishInput()
            assertEquals(0, mailbox.snapshot().pending)
        }
        release.countDown()
        waitFor("canceled dispatcher drains") { !mailbox.snapshot().scheduled }
        onMainChecked { assertTrue(field<List<String>>("candidateTexts").isEmpty()) }
    }

    fun testFinishedPresentationReleasesStripGridPreeditAndPreviewIdempotently() {
        query(true, "khkh")
        lateinit var oldStrip: CandidateStrip
        lateinit var oldGrid: CandidateGrid
        lateinit var oldPreedit: TextView
        lateinit var oldPreview: TextView
        lateinit var oldStatus: TextView
        val labels = mutableListOf<TextView>()
        onMainChecked {
            // All logical candidates remain reachable, including many visited strip batches.
            val all = field<List<String>>("candidateTexts")
            assertEquals(472, all.size)
            CandidateStrip::class.java.getDeclaredMethod("bindThrough",
                Int::class.javaPrimitiveType, List::class.java, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(strip, all.size, all, row.childCount)
            oldStrip = strip
            assertEquals(all.size, row.childCount)
            repeat(row.childCount) { labels.add(row.getChildAt(it) as TextView) }
            field<View>("hideIcon").performClick()
            oldGrid = field("candidateGrid")
            oldPreedit = field("preedit")
        }
        instrumentation.waitForIdleSync()
        onMainChecked {
            // Collapse leaves the grid detached but retained for its normal visible-session reuse.
            field<View>("hideIcon").performClick()
            setField("panel", "voice")
            invoke("renderBody")
            oldPreview = field("voiceText")
            oldStatus = field("voiceStatus")
            oldPreview.text = "synthetic preview"
            setField("voicePreview", "synthetic preview")
            val rootBefore = field<View>("root")
            service.onFinishInputView(false)
            service.onFinishInput()
            service.onFinishInputView(false)
            assertSame("Lifecycle release keeps the cheap root", rootBefore, field<View>("root"))
            val values = CandidateStrip::class.java.getDeclaredField("displayed").apply { isAccessible = true }
            assertTrue((values.get(oldStrip) as List<*>).isEmpty())
            assertEquals(0, (oldStrip.getChildAt(0) as ViewGroup).childCount)
            assertFalse(CandidateStrip::class.java.getDeclaredField("appendScheduled")
                .apply { isAccessible = true }.getBoolean(oldStrip))
            assertNull(oldGrid.adapter)
            assertEquals(0, oldGrid.childCount)
            assertNull(field<CandidateGrid?>("candidateGrid"))
            assertEquals("", oldPreedit.text.toString())
            assertEquals("", oldPreview.text.toString())
            assertEquals("", oldStatus.text.toString())
            assertEquals("", field<String>("voicePreview"))
            assertNull(field<TextView?>("voiceText"))
            assertNull(field<TextView?>("voiceStatus"))
            labels.forEach { assertEquals("", it.text.toString()); it.performClick() }
            assertTrue(connection.committed.isEmpty())
        }
    }

    fun testActiveExpandedRestartRestoresKeysAndPreservesManualLanguageAndSymbolPage() {
        for (chinese in listOf(true, false)) {
            query(chinese, if (chinese) "nihao" else "he")
            onMainChecked {
                setField("symbols", true)
                setField("symbolPage", true)
                invoke("renderBody")
                field<View>("hideIcon").performClick()
                val oldGrid = field<CandidateGrid>("candidateGrid")
                assertSame(oldGrid, field<ViewGroup>("body").getChildAt(0))
                val info = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
                service.onCreateInputMethodInterface().restartInput(connection, info)
                fun assertRestoredKeys() {
                    assertTrue(field<Boolean>("active"))
                    assertEquals(chinese, field<Boolean>("chinese"))
                    assertTrue(field<Boolean>("symbols"))
                    assertTrue(field<Boolean>("symbolPage"))
                    assertFalse(field<Boolean>("candidatesExpanded"))
                    val body = field<ViewGroup>("body")
                    assertTrue("Restart must never leave a released grid's body empty", body.childCount > 0)
                    assertFalse(body.getChildAt(0) is CandidateGrid)
                    fun containsSecondPageKey(view: View): Boolean = view.contentDescription == "~" ||
                        (view is ViewGroup && (0 until view.childCount).any { containsSecondPageKey(view.getChildAt(it)) })
                    assertTrue("The restored body must contain the retained second symbol page", containsSecondPageKey(body))
                }
                assertRestoredKeys() // Before a separate view-start callback.
                assertNull(oldGrid.adapter)
                service.onStartInputView(info, true)
                val newRoot = field<View>("root")
                (newRoot.parent as? ViewGroup)?.removeView(newRoot)
                activity.setContentView(newRoot)
                assertRestoredKeys() // The normal subsequent callback must preserve the same mode.
            }
        }
    }

    fun testFinishInputWithoutViewFinishReleasesPresentationAndRevokesDelivery() {
        query(true, "nihao")
        onMainChecked {
            service.onFinishInput()
            assertFalse(field<Boolean>("active"))
            assertEquals(0, row.childCount)
            assertEquals("", field<TextView>("preedit").text.toString())
            assertTrue(field<List<String>>("candidateTexts").isEmpty())
            assertEquals(0, field<io.secure.keyboard.concurrent.LatestTaskMailbox>("candidateQueries").snapshot().pending)
        }
    }

    fun testEditorInvalidationReleasesOldPresentationBeforeNewQuery() {
        query(true, "nihao")
        lateinit var oldStrip: CandidateStrip
        lateinit var oldPreedit: TextView
        onMainChecked {
            oldStrip = strip
            oldPreedit = field("preedit")
            startEditor(RecordingConnection(View(activity)))
            assertEquals(0, (oldStrip.getChildAt(0) as ViewGroup).childCount)
            assertEquals("", oldPreedit.text.toString())
            assertTrue(field<List<String>>("candidateTexts").isEmpty())
        }
    }

    fun testRepeatedChineseResultsRetainAttachedViewsScrollAndEveryLoadingFrame() {
        query(true, "ni")
        assertStableRefresh()
    }

    fun testRepeatedEnglishResultsRetainAttachedViewsScrollAndEveryLoadingFrame() {
        query(false, "he")
        assertStableRefresh()
    }

    fun testExpandedGridReplacesOnlyBodyThenCommitsFullLongCandidateWithOneHaptic() {
        val host = attachActualHost()
        val text = "完整自定义长句：网格显示两行省略，实际输入全部文字。".repeat(5)
        val store = ContentStore(context)
        store.addDictionary(text, "nihao")
        val id = store.dictionary().single { it.word == text }.id
        try {
            typePending("nihao")
            var bodyHeight = 0
            var boxHeight = 0
            lateinit var originalStrip: CandidateStrip
            instrumentation.runOnMainSync {
                bodyHeight = field<View>("body").height
                boxHeight = field<View>("box").height
                originalStrip = strip
                assertEquals("展开候选", field<View>("hideIcon").contentDescription)
                field<View>("hideIcon").performClick()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val grid = field<CandidateGrid>("candidateGrid")
                assertSame(grid, field<ViewGroup>("body").getChildAt(0))
                assertSame(originalStrip, strip)
                assertEquals(bodyHeight, field<View>("body").height)
                assertEquals(boxHeight, field<View>("box").height)
                assertEquals(View.VISIBLE, field<View>("preedit").visibility)
                assertEquals("收起候选", field<View>("hideIcon").contentDescription)
                assertEquals(180f, field<View>("hideIcon").rotation)
                assertEquals("", host.editor.text.toString())
                // Collapse and re-expand without changing the query or committing anything.
                field<View>("hideIcon").performClick()
                assertFalse(field<Boolean>("candidatesExpanded"))
                assertFalse(field<ViewGroup>("body").getChildAt(0) is CandidateGrid)
                field<View>("hideIcon").performClick()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val grid = field<CandidateGrid>("candidateGrid")
                val position = field<List<String>>("candidateTexts").indexOf(text)
                assertEquals(1, position)
                val cell = grid.getChildAt(position - grid.firstVisiblePosition) as TextView
                assertEquals(text, cell.text.toString())
                host.haptics.reset()
                cell.performClick()
                assertEquals(text, host.editor.text.toString())
                assertEquals(listOf(text), host.connection.commits)
                assertEquals(listOf(HapticFeedbackConstants.KEYBOARD_TAP), host.haptics.effects)
                assertEquals(listOf(""), host.haptics.hostTextAtFeedback)
                assertFalse(field<Boolean>("candidatesExpanded"))
                assertFalse(field<ViewGroup>("body").getChildAt(0) is CandidateGrid)
                assertEquals("收起键盘", field<View>("hideIcon").contentDescription)
                cell.performClick()
                assertEquals("Detached old cell must not commit again", 1, host.connection.commits.size)
                assertEquals(1, host.haptics.effects.size)
            }
        } finally {
            store.deleteDictionary(id)
        }
    }

    fun testAllMappedWubiCandidatesRemainReachableAtTheExpandedGridTail() {
        query(true, "khkh")
        lateinit var expectedTail: String
        instrumentation.runOnMainSync {
            val values = field<List<String>>("candidateTexts")
            assertEquals("The full packaged four-code bucket reaches the service", 472, values.size)
            assertEquals(values.size, values.distinct().size)
            expectedTail = values.last()
            assertEquals("The compact strip initially binds only one page", 40, row.childCount)
            field<View>("hideIcon").performClick()
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            val grid = field<CandidateGrid>("candidateGrid")
            assertEquals(472, grid.adapter.count)
            grid.setSelection(471)
        }
        waitFor("last real mapped Wubi entry becomes visible") {
            val grid = field<CandidateGrid>("candidateGrid")
            grid.firstVisiblePosition <= 471 && grid.lastVisiblePosition >= 471
        }
        instrumentation.runOnMainSync {
            val grid = field<CandidateGrid>("candidateGrid")
            val cell = grid.getChildAt(471 - grid.firstVisiblePosition) as TextView
            assertEquals(expectedTail, cell.text.toString())
            cell.performClick()
            assertEquals(listOf(expectedTail), connection.committed)
            assertEquals("", field<String>("code"))
            assertFalse(field<Boolean>("candidatesExpanded"))
        }
    }

    fun testExpandedSameResultsRetainScrollViewsAndRejectLoadingTouch() {
        query(true, "q")
        instrumentation.runOnMainSync { field<View>("hideIcon").performClick() }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { field<CandidateGrid>("candidateGrid").setSelection(20) }
        instrumentation.waitForIdleSync()
        lateinit var originalGrid: CandidateGrid
        lateinit var cells: List<TextView>
        lateinit var texts: List<CharSequence>
        var first = 0
        var top = 0
        instrumentation.runOnMainSync {
            originalGrid = field("candidateGrid")
            cells = (0 until originalGrid.childCount).map { originalGrid.getChildAt(it) as TextView }
            texts = cells.map { it.text }
            first = originalGrid.firstVisiblePosition
            top = cells.first().top
        }
        val release = gateWorker()
        instrumentation.runOnMainSync {
            invoke("refreshCandidates")
            cells.first().performClick()
            assertTrue("Retained grid labels must not select during loading", connection.committed.isEmpty())
            assertSame(originalGrid, field("candidateGrid"))
        }
        release.countDown()
        awaitResults()
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertSame(originalGrid, field("candidateGrid"))
            assertSame(originalGrid, field<ViewGroup>("body").getChildAt(0))
            assertEquals(first, originalGrid.firstVisiblePosition)
            assertEquals(top, originalGrid.getChildAt(0).top)
            cells.forEachIndexed { index, view ->
                assertSame(view, originalGrid.getChildAt(index))
                assertSame(texts[index], view.text)
            }
            assertTrue(connection.committed.isEmpty())
        }
    }

    fun testExpandedEnglishSelectionAndCancelEditorResetSafelyRestoreKeys() {
        query(false, "he")
        instrumentation.runOnMainSync {
            assertEquals("展开候选", field<View>("hideIcon").contentDescription)
            field<View>("hideIcon").performClick()
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { field<CandidateGrid>("candidateGrid").getChildAt(0).performClick() }
        drainWorker()
        instrumentation.runOnMainSync {
            assertEquals(1, connection.committed.size)
            assertFalse(field<Boolean>("candidatesExpanded"))
            assertFalse(field<ViewGroup>("body").getChildAt(0) is CandidateGrid)
        }
        query(true, "ni")
        instrumentation.runOnMainSync { field<View>("hideIcon").performClick() }
        instrumentation.waitForIdleSync()
        lateinit var oldCell: View
        instrumentation.runOnMainSync {
            oldCell = field<CandidateGrid>("candidateGrid").getChildAt(0)
            val count = connection.committed.size
            (field<LinearLayout>("candidateGroup").getChildAt(1)).performClick()
            assertEquals("", field<String>("code"))
            assertFalse(field<Boolean>("candidatesExpanded"))
            assertFalse(field<ViewGroup>("body").getChildAt(0) is CandidateGrid)
            oldCell.performClick()
            assertEquals(count, connection.committed.size)
        }
        query(true, "ni")
        instrumentation.runOnMainSync { field<View>("hideIcon").performClick() }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            oldCell = field<CandidateGrid>("candidateGrid").getChildAt(0)
            val replacement = RecordingConnection(View(activity))
            startEditor(replacement)
            invoke("renderHeader")
            assertFalse(field<Boolean>("candidatesExpanded"))
            assertFalse(field<ViewGroup>("body").getChildAt(0) is CandidateGrid)
            oldCell.performClick()
            assertTrue(replacement.committed.isEmpty())
            assertEquals("收起键盘", field<View>("hideIcon").contentDescription)
        }
    }

    private fun assertStableRefresh() {
        lateinit var originalHeader: ViewGroup
        lateinit var originalBody: ViewGroup
        lateinit var originalKeys: View
        lateinit var originalStrip: CandidateStrip
        lateinit var labels: List<View>
        lateinit var texts: List<CharSequence>
        var scrollX = 0
        var rebindings = 0
        var emptyFrames = 0
        val frames = CountDownLatch(3)
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { rebindings++ }
            override fun afterTextChanged(s: Editable?) {}
        }
        lateinit var draw: ViewTreeObserver.OnDrawListener
        instrumentation.runOnMainSync {
            originalHeader = field("header")
            originalBody = field("body")
            originalKeys = originalBody.getChildAt(0)
            originalStrip = strip
            labels = (0 until row.childCount).map { row.getChildAt(it) }
            texts = labels.map { (it as TextView).text }
            labels.forEach { (it as TextView).addTextChangedListener(watcher) }
            strip.scrollTo(120, 0)
            scrollX = strip.scrollX
            assertTrue("Fixture must contain horizontally scrollable candidates", scrollX > 0)
            draw = ViewTreeObserver.OnDrawListener {
                if (!strip.isShown || row.childCount == 0) emptyFrames++
                frames.countDown()
                if (frames.count > 0) originalHeader.postInvalidateOnAnimation()
            }
            originalHeader.viewTreeObserver.addOnDrawListener(draw)
        }
        val release = gateWorker()
        instrumentation.runOnMainSync {
            repeat(3) { invoke("refreshCandidates") }
            originalHeader.postInvalidateOnAnimation()
            assertEquals("Pending query must retain all visible candidates", labels.size, row.childCount)
            assertSame(originalStrip, strip)
            assertEquals(scrollX, strip.scrollX)
        }
        assertTrue("Loading frames must actually be drawn", frames.await(5, TimeUnit.SECONDS))
        release.countDown()
        awaitResults()
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            originalHeader.viewTreeObserver.removeOnDrawListener(draw)
            assertEquals("There must be no empty/toolbar intermediate candidate frame", 0, emptyFrames)
            assertSame(originalHeader, field("header"))
            assertSame(originalBody, field("body"))
            assertSame(originalKeys, originalBody.getChildAt(0))
            assertSame(originalStrip, strip)
            assertEquals(scrollX, strip.scrollX)
            assertEquals("Equal ordered results must never call setText or restyle fonts", 0, rebindings)
            labels.forEachIndexed { index, view ->
                assertSame(view, row.getChildAt(index))
                assertSame(texts[index], (view as TextView).text)
                view.removeTextChangedListener(watcher)
            }
        }
    }

    fun testChangedItemsOnlyRebindAffectedPositionsAndReadCurrentClickText() {
        val original = List(12) { "candidate$it" }
        val clicked = mutableListOf<String>()
        lateinit var testStrip: CandidateStrip
        instrumentation.runOnMainSync {
            testStrip = CandidateStrip(activity, Color.DKGRAY, Color.BLUE) { clicked.add(it) }
            activity.setContentView(testStrip, ViewGroup.LayoutParams(-1, 120))
            testStrip.updateCandidates(original)
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertTrue(testStrip.isAttachedToWindow)
            val testRow = testStrip.getChildAt(0) as LinearLayout
            val labels = (0 until testRow.childCount).map { testRow.getChildAt(it) as TextView }
            val texts = labels.map { it.text }
            testStrip.scrollTo(100, 0)
            val x = testStrip.scrollX
            val updated = original.toMutableList().apply { this[2] = "changed" }
            testStrip.updateCandidates(updated)
            labels.forEachIndexed { index, view ->
                assertSame(view, testRow.getChildAt(index))
                if (index != 2) assertSame(texts[index], view.text)
            }
            assertEquals(x, testStrip.scrollX)
            labels[2].performClick()
            assertEquals(listOf("changed"), clicked)
            testStrip.updateCandidates(updated.take(10))
            assertEquals(10, testRow.childCount)
            assertSame(labels[0], testRow.getChildAt(0))
            testStrip.updateCandidates(updated + "last")
            assertEquals(13, testRow.childCount)
            assertSame(labels[0], testRow.getChildAt(0))
        }
    }

    fun testOldChineseVisualsCannotCommitThroughTapSpaceOrPunctuation() {
        query(true, "ni")
        val release = gateWorker()
        instrumentation.runOnMainSync {
            val oldFirst = row.getChildAt(0)
            setField("code", "nihao")
            invoke("refreshCandidates")
            assertTrue("Old visuals should stay attached while computing", oldFirst.isAttachedToWindow)
            oldFirst.performClick()
            assertTrue("A stale candidate tap must never commit immediately", connection.committed.isEmpty())
            invoke("space")
            invoke("type", ".")
            assertTrue("Queued confirmation and punctuation must wait for the exact current query", connection.committed.isEmpty())
            assertEquals("nihao", field<String>("code"))
        }
        release.countDown()
        waitFor("current-query confirmation and following punctuation commit in order") {
            connection.committed.size == 2
        }
        instrumentation.runOnMainSync {
            assertEquals("Only the fresh nihao result and one queued punctuation may commit",
                listOf("你好", "."), connection.committed)
            assertEquals("", field<String>("code"))
            assertEquals("Successful confirmation must clear the old candidate visuals", 0, row.childCount)
        }
    }

    fun testEnglishStalePrefixAndNewEditorRejectDelayedSelection() {
        query(false, "he")
        var release = gateWorker()
        instrumentation.runOnMainSync {
            row.getChildAt(0).performClick()
            connection.before = "different"
        }
        release.countDown()
        drainWorker()
        instrumentation.runOnMainSync {
            assertTrue(connection.committed.isEmpty())
            assertEquals(0, connection.deleted)
        }
        query(false, "he")
        release = gateWorker()
        lateinit var replacement: RecordingConnection
        instrumentation.runOnMainSync {
            row.getChildAt(0).performClick()
            replacement = RecordingConnection(View(activity)).apply { before = "he" }
            startEditor(replacement)
        }
        release.countDown()
        drainWorker()
        instrumentation.runOnMainSync {
            assertTrue(connection.committed.isEmpty())
            assertTrue(replacement.committed.isEmpty())
            assertEquals(0, replacement.deleted)
        }
    }

    fun testPendingEnglishRefreshCannotSelectRetainedVisuals() {
        query(false, "he")
        val release = gateWorker()
        instrumentation.runOnMainSync {
            connection.before = "hello"
            invoke("refreshCandidates")
            assertTrue(row.childCount > 0)
            row.getChildAt(0).performClick()
            assertTrue(connection.committed.isEmpty())
        }
        release.countDown()
        waitFor("latest completed English query, including a legitimate empty result") {
            field<Int>("candidateGeneration") == field<Int>("generation") &&
                field<String>("candidateQuery") == "hello"
        }
        instrumentation.runOnMainSync {
            assertTrue(connection.committed.isEmpty())
            assertEquals(0, connection.deleted)
            assertEquals("hello", connection.before)
            assertTrue("A complete hello has no longer static completions", field<List<String>>("candidateTexts").isEmpty())
            assertEquals("The stale row disappears only once the empty result is ready", 0, row.childCount)
        }
    }

    fun testTouchStartedDuringLoadingCannotCommitAfterSameResultsArrive() {
        query(true, "ni")
        val release = gateWorker()
        lateinit var label: View
        val down = SystemClock.uptimeMillis()
        instrumentation.runOnMainSync {
            invoke("refreshCandidates")
            label = row.getChildAt(0)
            val event = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, 8f, 8f, 0)
            label.dispatchTouchEvent(event)
            event.recycle()
        }
        release.countDown()
        awaitResults()
        instrumentation.runOnMainSync {
            assertSame(label, row.getChildAt(0))
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 8f, 8f, 0)
            label.dispatchTouchEvent(event)
            event.recycle()
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertTrue("A tap begun on a stale result must not become valid later", connection.committed.isEmpty())
            label.performClick()
            assertEquals("A new accessibility/programmatic click must still work", 1, connection.committed.size)
        }
    }

    fun testDeletedPersonalCandidateIsImmediatelyClearedAndCannotBeSelected() {
        val store = ContentStore(context)
        val word = "候选隐私测试${SystemClock.uptimeMillis()}"
        store.addDictionary(word, "ni")
        val id = store.dictionary().first { it.word == word }.id
        try {
            query(true, "ni")
            instrumentation.runOnMainSync { assertTrue(field<List<String>>("candidateTexts").contains(word)) }
            val release = gateWorker()
            store.deleteDictionary(id)
            instrumentation.runOnMainSync {
                assertFalse(invoke("choose", word) as Boolean)
                assertEquals(0, row.childCount)
                assertTrue(connection.committed.isEmpty())
                invoke("refreshCandidates")
            }
            release.countDown()
            awaitResults()
            instrumentation.runOnMainSync { assertFalse(field<List<String>>("candidateTexts").contains(word)) }
        } finally {
            if (store.dictionary().any { it.id == id }) store.deleteDictionary(id)
        }
    }

    fun testExactCustomMatchesFormSecondPlaceBlockAndPromoteDuplicateText() {
        query(true, "ni")
        lateinit var baseline: List<String>
        instrumentation.runOnMainSync { baseline = field<List<String>>("candidateTexts").toList() }
        assertTrue("Fixture needs several normal candidates", baseline.size > 3)
        val suffix = SystemClock.uptimeMillis()
        val first = "自定排序甲$suffix"
        val second = "自定排序乙$suffix"
        val prefix = "自定前缀$suffix"
        val duplicate = baseline[2]
        val store = ContentStore(context)
        val oldIds = store.dictionary().map { it.id }.toSet()
        try {
            store.addDictionary(first, "N ' I")
            store.addDictionary(prefix, "Ni Hao")
            store.addDictionary(second, "ni")
            store.addDictionary(duplicate, "ni")
            store.addDictionary(first, "n i")
            query(true, "ni")
            instrumentation.runOnMainSync {
                val actual = field<List<String>>("candidateTexts")
                assertEquals(listOf(baseline.first(), first, second, duplicate), actual.take(4))
                assertEquals(1, actual.count { it == duplicate })
                assertEquals(1, actual.count { it == first })
                assertEquals(baseline.filterNot { it == baseline.first() || it == duplicate },
                    actual.drop(4).dropLast(1))
                assertEquals(prefix, actual.last())
            }
            assertStableRefresh()
        } finally {
            store.dictionary().filter { it.id !in oldIds }.forEach { store.deleteDictionary(it.id) }
        }
    }

    fun testCustomPrefixTailBeyondFortyBuiltinsIsReachableByHorizontalScrolling() {
        query(true, "q")
        lateinit var baseline: List<String>
        instrumentation.runOnMainSync {
            baseline = field<List<String>>("candidateTexts").toList()
            assertTrue("Fixture needs a nonempty actual built-in baseline", baseline.isNotEmpty())
        }
        val suffix = SystemClock.uptimeMillis()
        val words = (1..45).map { "自定尾部${suffix}号$it" }
        val (extensionB, ordinary) = baseline.partition { value ->
            value.codePoints().anyMatch { it in 0x20000..0x2A6DF }
        }
        // Ordinary custom prefixes follow the ordinary baseline, while the final stable
        // Extension-B partition moves every Extension-B baseline string behind those prefixes.
        val expected = ordinary + words + extensionB
        assertTrue("All 45 custom matches must extend beyond the first 40 attached labels", expected.size > 40)
        val store = ContentStore(context)
        val oldIds = store.dictionary().map { it.id }.toSet()
        try {
            store.importDictionary(words.joinToString("\n") { "$it\tqqqqqqqqqqqq" })
            query(true, "q")
            instrumentation.runOnMainSync {
                assertEquals(baseline.size + words.size, field<List<String>>("candidateTexts").size)
                assertEquals(expected, field<List<String>>("candidateTexts"))
                assertEquals("Initial binding must stay bounded despite all retained matches", 40, row.childCount)
            }
            waitFor("custom tail appended through ordinary horizontal scrolling") {
                if (row.childCount < expected.size) strip.scrollTo(row.width, 0)
                row.childCount == expected.size
            }
            instrumentation.runOnMainSync {
                val actual = field<List<String>>("candidateTexts")
                words.forEach { word ->
                    val index = actual.indexOf(word)
                    assertTrue("Every custom match must remain reachable", index >= 0)
                    assertEquals(word, (row.getChildAt(index) as TextView).text.toString())
                }
                val lastCustomIndex = actual.indexOf(words.last())
                assertTrue("The selected custom result must be beyond the initial batch", lastCustomIndex >= 40)
                val tail = row.getChildAt(lastCustomIndex) as TextView
                assertEquals(words.last(), tail.text.toString())
                strip.scrollTo(tail.left, 0)
                tail.performClick()
                assertEquals(listOf(words.last()), connection.committed)
            }
        } finally {
            store.dictionary().filter { it.id !in oldIds }.forEach { store.deleteDictionary(it.id) }
        }
    }

    fun testCustomOnlyExactAndPrefixResultsWorkWithoutBaseline() {
        val exact = "独立编码精确${SystemClock.uptimeMillis()}"
        val prefix = "独立编码前缀${SystemClock.uptimeMillis()}"
        val code = "qqqqqqqqqqq"
        val store = ContentStore(context)
        val oldIds = store.dictionary().map { it.id }.toSet()
        try {
            store.addDictionary(prefix, code + "a")
            store.addDictionary(exact, code)
            query(true, code)
            instrumentation.runOnMainSync {
                assertEquals(listOf(exact, prefix), field<List<String>>("candidateTexts"))
                assertTrue(invoke("choose", exact) as Boolean)
                assertEquals(listOf(exact), connection.committed)
            }
            val id = store.dictionary().single { it.word == exact }.id
            store.deleteDictionary(id)
            query(true, code)
            instrumentation.runOnMainSync {
                assertEquals(listOf(prefix), field<List<String>>("candidateTexts"))
                assertTrue(invoke("choose", prefix) as Boolean)
                assertEquals(listOf(exact, prefix), connection.committed)
            }
        } finally {
            store.dictionary().filter { it.id !in oldIds }.forEach { store.deleteDictionary(it.id) }
        }
    }

    fun testCachedCustomCodeEditAndPrefixDeletionImmediatelyInvalidateOldSelections() {
        val word = "编辑缓存候选${SystemClock.uptimeMillis()}"
        val store = ContentStore(context)
        store.addDictionary(word, "ni")
        val id = store.dictionary().single { it.word == word }.id
        try {
            query(true, "ni")
            instrumentation.runOnMainSync { assertEquals(word, field<List<String>>("candidateTexts")[1]) }
            store.updateDictionary(id, word, "nihao")
            instrumentation.runOnMainSync {
                assertFalse("Old exact snapshot cannot commit after code edit", invoke("choose", word) as Boolean)
                assertTrue(connection.committed.isEmpty())
            }
            query(true, "ni")
            instrumentation.runOnMainSync { assertEquals(word, field<List<String>>("candidateTexts").last()) }
            store.deleteDictionary(id)
            instrumentation.runOnMainSync {
                assertFalse("Old prefix snapshot cannot commit after deletion", invoke("choose", word) as Boolean)
                assertTrue(connection.committed.isEmpty())
            }
            query(true, "ni")
            instrumentation.runOnMainSync { assertFalse(word in field<List<String>>("candidateTexts")) }
        } finally {
            if (store.dictionary().any { it.id == id }) store.deleteDictionary(id)
        }
    }

    fun testFourCodeAutoCommitKeepsFirstWubiWithExactCustomMatches() {
        val word = "四码自定候选${SystemClock.uptimeMillis()}"
        val store = ContentStore(context)
        store.addDictionary(word, "wgkq")
        val id = store.dictionary().single { it.word == word }.id
        val settings = SettingsStore(context)
        try {
            query(true, "wgkq")
            instrumentation.runOnMainSync {
                assertEquals(listOf("使", word), field<List<String>>("candidateTexts").take(2))
                invoke("clearPending")
            }
            settings.fourCodeAutoCommit = true
            instrumentation.runOnMainSync {
                repeat(3) {
                    "wgkq".forEach { invoke("type", it.toString()) }
                    assertEquals("", field<String>("code"))
                }
                assertEquals(listOf("使", "使", "使"), connection.committed)
            }
            drainWorker()
            settings.fourCodeAutoCommit = false
            query(true, "wgkq")
            instrumentation.runOnMainSync {
                assertEquals(listOf("使", word), field<List<String>>("candidateTexts").take(2))
            }
        } finally {
            settings.fourCodeAutoCommit = false
            if (store.dictionary().any { it.id == id }) store.deleteDictionary(id)
        }
    }

    fun testCompletedOldQueryCannotReplaceNewCompositionOrResurrectCanceledCandidates() {
        query(true, "ni")
        instrumentation.runOnMainSync {
            invoke("refreshCandidates")
            // Chinese lookup does not need the editor/main thread. Wait only in this fixture so
            // its result is already queued for main-thread delivery when generation changes.
            val computed = CountDownLatch(1)
            worker.execute { computed.countDown() }
            assertTrue(computed.await(5, TimeUnit.SECONDS))
            setField("code", "nihao")
            invoke("refreshCandidates")
        }
        awaitResults()
        instrumentation.runOnMainSync {
            assertEquals("nihao", field<String>("candidateQuery"))
            assertTrue(field<List<String>>("candidateTexts").contains("你好"))
            invoke("refreshCandidates")
            val computed = CountDownLatch(1)
            worker.execute { computed.countDown() }
            assertTrue(computed.await(5, TimeUnit.SECONDS))
            invoke("clearPending")
            invoke("renderHeader")
        }
        drainWorker()
        instrumentation.runOnMainSync {
            assertEquals(0, row.childCount)
            assertEquals("", field<String>("code"))
            assertEquals("", field<String>("candidateQuery"))
        }
    }

    fun testDictionaryRevisionRejectsAnAlreadyComputedPersonalResult() {
        val store = ContentStore(context)
        val word = "过期候选测试${SystemClock.uptimeMillis()}"
        store.addDictionary(word, "ni")
        val id = store.dictionary().first { it.word == word }.id
        try {
            query(true, "ni")
            instrumentation.runOnMainSync {
                invoke("refreshCandidates")
                val deletedAfterComputation = CountDownLatch(1)
                worker.execute {
                    store.deleteDictionary(id)
                    deletedAfterComputation.countDown()
                }
                assertTrue(deletedAfterComputation.await(5, TimeUnit.SECONDS))
            }
            drainWorker()
            instrumentation.runOnMainSync {
                assertEquals("A removed personal result must not return on delayed delivery", 0, row.childCount)
                assertEquals("", field<String>("candidateQuery"))
            }
        } finally {
            if (store.dictionary().any { it.id == id }) store.deleteDictionary(id)
        }
    }

    fun testToolbarRetainsFocusSemanticsWithoutGrayBlockAfterDpadInBothPalettes() {
        val settings = SettingsStore(context)
        val originalTheme = settings.theme
        try {
            for (theme in listOf("light", "dark")) {
                instrumentation.runOnMainSync {
                    settings.theme = theme
                    activity.setContentView(service.onCreateInputView())
                }
                instrumentation.waitForIdleSync()
                instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
                instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
                instrumentation.runOnMainSync {
                    val toolbar = field<LinearLayout>("toolbarGroup")
                    val more = toolbar.getChildAt(0) as ToolIcon
                    assertTrue(more.isAttachedToWindow)
                    assertTrue(more.isFocusable)
                    assertTrue(more.isClickable)
                    assertFalse(more.defaultFocusHighlightEnabled)
                    assertTrue("DPAD-focusable toolbar control must remain focusable", more.requestFocus())
                    assertTrue(more.isFocused)
                    val bitmap = Bitmap.createBitmap(more.width, more.height, Bitmap.Config.ARGB_8888)
                    more.draw(Canvas(bitmap))
                    assertEquals("Focused toolbar corner must remain transparent in $theme", 0, Color.alpha(bitmap.getPixel(2, 2)))
                    bitmap.recycle()
                }
                saveFocusScreenshot(theme)
                instrumentation.runOnMainSync {
                    val more = field<LinearLayout>("toolbarGroup").getChildAt(0)
                    more.performClick()
                    assertEquals("more", field<String>("panel"))
                    assertEquals("返回键盘", (field<LinearLayout>("toolbarGroup").getChildAt(0)).contentDescription)
                    field<LinearLayout>("toolbarGroup").getChildAt(0).performClick()
                    assertEquals("keys", field<String>("panel"))
                }
            }
        } finally {
            settings.theme = originalTheme
        }
    }

    fun testActualEditorChineseAndWubiEnterCommitRawCodeWhileSpaceCommitsCandidate() {
        val host = attachActualHost()
        for ((raw, word) in listOf("nihao" to "你好", "wqiy" to "你")) {
            resetActualHost(host)
            typePending(raw)
            instrumentation.runOnMainSync {
                assertEquals("Composition must remain keyboard-local", "", host.editor.text.toString())
                host.haptics.reset()
                tapKey("回车")
                assertEquals(raw, host.editor.text.toString())
                assertEquals(listOf(raw), host.connection.commits)
                assertTrue("Raw-code Enter must not also invoke the editor action", host.connection.actions.isEmpty())
                assertTrue(host.observedActions.isEmpty())
                assertFalse(host.editor.text.contains('\n'))
                assertEquals("", field<String>("code"))
            }
            resetActualHost(host)
            typePending(raw)
            instrumentation.runOnMainSync {
                assertEquals(word, (row.getChildAt(0) as TextView).text.toString())
                host.haptics.reset()
                tapKey("五笔")
                assertEquals(word, host.editor.text.toString())
                assertEquals(listOf(word), host.connection.commits)
                assertTrue(host.connection.actions.isEmpty())
                assertEquals("Physical Space already supplies exactly one key haptic", 1, host.haptics.effects.size)
                assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, host.haptics.effects.single())
            }
        }
    }

    fun testActualEditorIdleEnterPerformsItsNormalActionWithoutInsertingText() {
        val host = attachActualHost()
        instrumentation.runOnMainSync {
            assertEquals("", field<String>("code"))
            tapKey("回车")
            assertEquals(listOf(EditorInfo.IME_ACTION_SEARCH), host.connection.actions)
            assertEquals(listOf(EditorInfo.IME_ACTION_SEARCH), host.observedActions)
            assertEquals("", host.editor.text.toString())
            assertTrue(host.connection.commits.isEmpty())
        }
    }

    fun testKeysToolsEditingAndEmojiRequestOneFeedbackBeforeTheirActions() {
        val host = attachActualHost()
        fun descendants(view: View): List<View> = listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
            else emptyList()
        fun assertOneBefore(check: () -> Unit, action: () -> Unit) {
            host.haptics.reset()
            host.haptics.onFeedback = check
            action()
            host.haptics.onFeedback = null
            assertEquals("One accepted control press has exactly one feedback request", 1, host.haptics.effects.size)
        }
        instrumentation.runOnMainSync {
            setField("chinese", false)
            invoke("renderBody")
            assertOneBefore({ assertEquals("", host.editor.text.toString()) }) { tapKey("a") }
            assertEquals("a", host.editor.text.toString())
            assertOneBefore({
                assertEquals("a", host.editor.text.toString())
                assertTrue("Feedback precedes both host key-event requests", host.connection.keyEvents.isEmpty())
            }) { tapKey("删除") }
            assertEquals(
                listOf(KeyEvent.ACTION_DOWN to KeyEvent.KEYCODE_DEL, KeyEvent.ACTION_UP to KeyEvent.KEYCODE_DEL),
                host.connection.keyEvents,
            )
        }
        // BaseInputConnection routes sendKeyEvent through the ViewRoot's message queue.
        // Release the main thread and require the real editor result, without replacing the
        // host deletion path or assuming a fixed delay is sufficient.
        waitFor("real EditText handles queued Backspace down/up") { host.editor.text.isEmpty() }
        drainWorker()
        instrumentation.runOnMainSync {
            assertEquals("", host.editor.text.toString())
            assertEquals("Asynchronous host delivery must not add another feedback", 1, host.haptics.effects.size)
            assertEquals(listOf("a"), host.haptics.hostTextAtFeedback)
            val toolbarEdit = descendants(field("header")).first { it.contentDescription == "快捷编辑" }
            assertOneBefore({ assertEquals("keys", field<String>("panel")) }) { toolbarEdit.performClick() }
            assertEquals("quickedit", field<String>("panel"))
            host.editor.setText("abc")
            host.editor.setSelection(3)
            val selectAll = descendants(field("body")).filterIsInstance<TextView>().first { it.text.toString() == "全选" }
            assertOneBefore({ assertEquals(3, host.editor.selectionStart) }) { selectAll.performClick() }
            assertEquals(0, host.editor.selectionStart)
            assertEquals(3, host.editor.selectionEnd)
            invoke("openTool", "more")
            val emojiCell = descendants(field("body")).filterIsInstance<TextView>()
                .first { it.text.toString() == "表情" }.parent as View
            assertOneBefore({ assertEquals("more", field<String>("panel")) }) { emojiCell.performClick() }
            assertEquals("emoji", field<String>("panel"))
            host.editor.setText("")
            host.editor.setSelection(0)
            val emojiGrid = descendants(field("body")).filterIsInstance<io.secure.keyboard.ui.EmojiGrid>().single()
            val emoji = emojiGrid.adapter.getItem(0).toString()
            assertOneBefore({ assertEquals("", host.editor.text.toString()) }) {
                emojiGrid.performItemClick(null, 0, 0L)
            }
            assertEquals(emoji, host.editor.text.toString())
            // Central sound is opt-in. Native clicks must never add a second/default-on sound.
            for (control in descendants(field("root")).filter { it.isClickable || it.isLongClickable }) {
                assertFalse("Implicit click sound: ${control.contentDescription}", control.isSoundEffectsEnabled)
                assertFalse("Implicit long-press haptic: ${control.contentDescription}", control.isHapticFeedbackEnabled)
            }
        }
    }

    fun testActualCandidateTapUsesOneGlobalTenPercentHapticBeforeHostCommit() {
        val host = attachActualHost()
        typePending("nihao")
        instrumentation.runOnMainSync {
            host.haptics.reset()
            row.getChildAt(0).performClick()
            assertEquals("你好", host.editor.text.toString())
            assertEquals(listOf("你好"), host.connection.commits)
            assertEquals(listOf(HapticFeedbackConstants.KEYBOARD_TAP), host.haptics.effects)
            assertEquals("Haptic must precede the validated host commit attempt", listOf(""), host.haptics.hostTextAtFeedback)
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { assertEquals(1, host.haptics.effects.size) }
    }

    fun testActualCandidateTapRespectsDisabledFeedbackAndRejectsStaleBeforeValidAttempts() {
        val host = attachActualHost()
        SettingsStore(context).vibration = false
        typePending("nihao")
        instrumentation.runOnMainSync {
            row.getChildAt(0).performClick()
            assertEquals("你好", host.editor.text.toString())
            assertTrue("Global feedback switch must cover candidate taps", host.haptics.effects.isEmpty())
        }
        SettingsStore(context).vibration = true
        resetActualHost(host)
        typePending("ni")
        val release = gateWorker()
        instrumentation.runOnMainSync {
            setField("code", "nihao")
            invoke("refreshCandidates")
            row.getChildAt(0).performClick()
            assertEquals("", host.editor.text.toString())
            assertTrue("Rejected stale taps must be silent", host.haptics.effects.isEmpty())
        }
        release.countDown()
        awaitResults()
        instrumentation.runOnMainSync {
            host.connection.rejectCommits = true
            row.getChildAt(0).performClick()
            assertEquals("", host.editor.text.toString())
            assertEquals("A valid attempted tap gets feedback before the host can reject it", 1, host.haptics.effects.size)
            assertEquals(listOf(""), host.haptics.hostTextAtFeedback)
            host.connection.rejectCommits = false
            row.getChildAt(0).performClick()
            assertEquals("你好", host.editor.text.toString())
            assertEquals(2, host.haptics.effects.size)
        }
    }

    fun testActualEnglishCandidateHapticFollowsValidationAndPrecedesEditorChanges() {
        val host = attachActualHost()
        instrumentation.runOnMainSync {
            host.editor.setText("he")
            host.editor.setSelection(2)
            setField("chinese", false)
            invoke("refreshCandidates")
        }
        awaitResults()
        var expected = ""
        instrumentation.runOnMainSync {
            expected = (row.getChildAt(0) as TextView).text.toString()
            host.haptics.reset()
            row.getChildAt(0).performClick()
            assertTrue("No candidate haptic before asynchronous prefix validation", host.haptics.effects.isEmpty())
        }
        drainWorker()
        instrumentation.runOnMainSync {
            assertEquals("$expected ", host.editor.text.toString())
            assertEquals(listOf("$expected "), host.connection.commits)
            assertEquals(listOf(HapticFeedbackConstants.KEYBOARD_TAP), host.haptics.effects)
            assertEquals(listOf("he"), host.haptics.hostTextAtFeedback)
        }
    }

    fun testActualEnglishCompletionAddsOneSpaceOrAdvancesOverExistingSpace() {
        val host = attachActualHost()
        data class Example(val before: String, val expected: String, val committed: String, val cursorOffset: Int)
        for (example in listOf(
            Example("hel", "hello ", "hello ", 1),
            Example("hel world", "hello world", "hello", 2),
            Example("hel,world", "hello ,world", "hello ", 1),
        )) {
            resetActualHost(host)
            instrumentation.runOnMainSync {
                host.editor.setText(example.before)
                host.editor.setSelection(3)
                setField("chinese", false)
                invoke("refreshCandidates")
            }
            awaitResults()
            instrumentation.runOnMainSync {
                val hello = (0 until row.childCount).map { row.getChildAt(it) as TextView }
                    .first { it.text.toString() == "hello" }
                hello.performClick()
            }
            drainWorker()
            instrumentation.runOnMainSync {
                assertEquals(example.expected, host.editor.text.toString())
                assertEquals("Cursor must advance past exactly the completion's one trailing space", 6, host.editor.selectionStart)
                assertEquals(6, host.editor.selectionEnd)
                assertEquals(listOf(example.committed), host.connection.commits)
                assertEquals(listOf(example.cursorOffset), host.connection.cursorOffsets)
                assertEquals(1, host.haptics.effects.size)
            }
        }
    }

    fun testActualEnglishDelayedSelectionCannotMutateNewHostTextOrProduceFeedback() {
        val host = attachActualHost()
        instrumentation.runOnMainSync {
            host.editor.setText("hel ")
            host.editor.setSelection(3)
            setField("chinese", false)
            invoke("refreshCandidates")
        }
        awaitResults()
        val release = gateWorker()
        instrumentation.runOnMainSync {
            row.getChildAt(0).performClick()
            host.editor.setText("different, text")
            host.editor.setSelection(9)
        }
        release.countDown()
        drainWorker()
        instrumentation.runOnMainSync {
            assertEquals("different, text", host.editor.text.toString())
            assertEquals(9, host.editor.selectionStart)
            assertTrue(host.connection.commits.isEmpty())
            assertTrue(host.haptics.effects.isEmpty())
        }
    }

    /** Records only calls to the production global feedback route, without relying on hardware. */
    private class RecordingHapticRoot(context: Context, private val editor: EditText) : FrameLayout(context) {
        val effects = mutableListOf<Int>()
        val hostTextAtFeedback = mutableListOf<String>()
        var onFeedback: (() -> Unit)? = null
        override fun performHapticFeedback(feedbackConstant: Int): Boolean {
            effects.add(feedbackConstant)
            hostTextAtFeedback.add(editor.text.toString())
            onFeedback?.invoke()
            return true
        }
        fun reset() { effects.clear(); hostTextAtFeedback.clear(); onFeedback = null }
    }

    /** Delegates to the real attached EditText connection, recording externally visible actions. */
    private class ObservedEditorConnection(target: InputConnection) : InputConnectionWrapper(target, false) {
        val commits = mutableListOf<String>()
        val cursorOffsets = mutableListOf<Int>()
        val actions = mutableListOf<Int>()
        val keyEvents = mutableListOf<Pair<Int, Int>>()
        var rejectCommits = false
        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            if (rejectCommits) return false
            val accepted = super.commitText(text, newCursorPosition)
            if (accepted) {
                commits.add(text.toString())
                cursorOffsets.add(newCursorPosition)
            }
            return accepted
        }
        override fun performEditorAction(actionCode: Int): Boolean {
            actions.add(actionCode)
            return super.performEditorAction(actionCode)
        }
        override fun sendKeyEvent(event: KeyEvent): Boolean {
            keyEvents.add(event.action to event.keyCode)
            return super.sendKeyEvent(event)
        }
    }

    private data class ActualHost(
        val editor: EditText,
        val connection: ObservedEditorConnection,
        val haptics: RecordingHapticRoot,
        val observedActions: MutableList<Int>,
    )

    private fun attachActualHost(): ActualHost {
        lateinit var host: ActualHost
        onMainChecked {
            val settings = SettingsStore(context)
            settings.vibration = true
            settings.vibrationStrength = 10
            val editor = EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_TEXT
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                showSoftInputOnFocus = false
            }
            val actions = mutableListOf<Int>()
            editor.setOnEditorActionListener { _, action, _ -> actions.add(action); true }
            val keyboard = field<View>("root")
            (keyboard.parent as ViewGroup).removeView(keyboard)
            val haptics = RecordingHapticRoot(activity, editor).apply {
                addView(keyboard, FrameLayout.LayoutParams(-1, -1))
            }
            val column = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(editor, LinearLayout.LayoutParams(-1, 120))
                addView(haptics, LinearLayout.LayoutParams(-1, 0, 1f))
            }
            activity.setContentView(column)
            setField("root", haptics)
            editor.requestFocus()
            val info = EditorInfo()
            val target = requireNotNull(editor.onCreateInputConnection(info))
            info.imeOptions = EditorInfo.IME_ACTION_SEARCH
            val connection = ObservedEditorConnection(target)
            service.onCreateInputMethodInterface().startInput(connection, info)
            assertFalse("Switching the synthetic host finishes the previous input", field<Boolean>("active"))
            assertSame(connection, service.currentInputConnection)
            // This Activity-attached harness does not open an IME window or receive its view-start
            // callback. Restore the explicitly visible fixture without replacing its haptic root.
            setField("active", true)
            invoke("renderHeader")
            host = ActualHost(editor, connection, haptics, actions)
        }
        instrumentation.waitForIdleSync()
        onMainChecked {
            assertTrue(host.editor.isAttachedToWindow)
            assertTrue(host.haptics.isShown)
        }
        return host
    }

    private fun resetActualHost(host: ActualHost) {
        instrumentation.runOnMainSync {
            invoke("clearPending")
            invoke("renderHeader")
            host.editor.setText("")
            host.connection.commits.clear()
            host.connection.cursorOffsets.clear()
            host.connection.actions.clear()
            host.connection.keyEvents.clear()
            host.observedActions.clear()
            host.haptics.reset()
        }
    }

    private fun typePending(raw: String) {
        instrumentation.runOnMainSync { raw.forEach { invoke("type", it.toString()) } }
        awaitResults()
        instrumentation.waitForIdleSync()
    }

    private fun tapKey(description: String) {
        fun find(view: View): View? {
            if (view.contentDescription?.toString() == description) return view
            if (view is ViewGroup) for (index in 0 until view.childCount)
                find(view.getChildAt(index))?.let { return it }
            return null
        }
        val key = requireNotNull(find(field("body"))) { "Key missing: $description" }
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, down + action, action, key.width / 2f, key.height / 2f, 0)
            key.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    private fun query(chinese: Boolean, query: String) {
        onMainChecked {
            setField("chinese", chinese)
            setField("code", if (chinese) query else "")
            connection.before = if (chinese) "" else query
            invoke("refreshCandidates")
        }
        awaitResults()
        instrumentation.waitForIdleSync()
    }

    private fun awaitResults() = waitFor("latest nonempty candidate result") {
        field<Int>("candidateGeneration") == field<Int>("generation") && row.childCount > 0
    }

    private fun gateWorker(): CountDownLatch {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        releases.add(release)
        worker.execute { started.countDown(); release.await(15, TimeUnit.SECONDS) }
        assertTrue("Worker gate must be running", started.await(15, TimeUnit.SECONDS))
        return release
    }

    private fun drainWorker() {
        val done = CountDownLatch(1)
        worker.execute { done.countDown() }
        assertTrue(done.await(15, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }

    private fun startEditor(editor: RecordingConnection) {
        service.onCreateInputMethodInterface().startInput(
            editor, EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
        )
    }

    private fun saveFocusScreenshot(theme: String) {
        instrumentation.waitForIdleSync()
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "validation")
        check(directory.isDirectory || directory.mkdirs())
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "candidate-toolbar-focused-$theme.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun waitFor(description: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            var success = false
            onMainChecked { success = condition() }
            if (success) return
            SystemClock.sleep(25)
        }
        fail("Timed out: $description")
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T =
        OfflineKeyboardService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service) as T

    private fun setField(name: String, value: Any) {
        OfflineKeyboardService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }

    private fun invoke(name: String, value: String? = null): Any? {
        // These fixtures type alphabet/ordinary punctuation. Symbol-page behavior is exercised
        // by actual keys in KeyboardEnglishSymbolPairTest, not by this reflection shortcut.
        if (name == "type") {
            return OfflineKeyboardService::class.java.getDeclaredMethod(
                name, String::class.java, Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }.invoke(service, requireNotNull(value), false)
        }
        val method = if (value == null) OfflineKeyboardService::class.java.getDeclaredMethod(name)
        else OfflineKeyboardService::class.java.getDeclaredMethod(name, String::class.java)
        method.isAccessible = true
        return if (value == null) method.invoke(service) else method.invoke(service, value)
    }
    /** Keep diagnostic assertion failures on JUnit's thread instead of crashing Android main. */
    private fun onMainChecked(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try { block() } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }

}
