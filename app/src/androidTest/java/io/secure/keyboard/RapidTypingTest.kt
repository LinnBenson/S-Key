package io.secure.keyboard

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PointF
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.test.ServiceTestCase
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import android.widget.LinearLayout
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import io.secure.keyboard.data.SettingsStore
import io.secure.keyboard.ui.KeyContainer

/**
 * Attached production keyboard, real EditText/InputConnection and root-dispatched touchscreen
 * streams. No performClick shortcut, per-key dispatch, inter-keystroke sleep or debounce.
 * Ordinary keys commit in release order, including reversed two-finger release order.
 * Synthetic 1 ms event spacing tests loss/correctness, not Samsung hardware latency.
 */
@Suppress("DEPRECATION")
class RapidTypingTest : InstrumentationTestCase() {
    private lateinit var activity: Activity
    private lateinit var service: OfflineKeyboardService
    private lateinit var harness: ServiceHarness
    private lateinit var editor: EditText
    private lateinit var connection: ObservedConnection
    private lateinit var sandbox: io.secure.keyboard.diagnostics.LifetimeSandbox
    private lateinit var app: IsolatedApplication
    private class IsolatedApplication(context: Context) : Application() {
        init { attachBaseContext(context) }
        override fun getApplicationContext(): Context = this
    }
    private val releases = mutableListOf<CountDownLatch>()
    private val context get() = app
    private val root get() = field<ViewGroup>("root")
    private val worker get() = field<ExecutorService>("worker")
    private var eventTime = 0L
    private var downTime = 0L
    private val pointers = linkedMapOf<Int, PointF>()
    private var measuredDispatchNanos = 0L
    private var maximumDispatchNanos = 0L
    private var measuredEventCount = 0

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

    private class ObservedConnection(target: InputConnection) : InputConnectionWrapper(target, false) {
        val commits = mutableListOf<String>()
        val acceptedCommitNanos = mutableListOf<Long>()
        val keyEvents = mutableListOf<KeyEvent>()
        override fun sendKeyEvent(event: KeyEvent): Boolean {
            keyEvents.add(KeyEvent(event))
            return super.sendKeyEvent(event)
        }
        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            val accepted = super.commitText(text, newCursorPosition)
            if (accepted) {
                commits.add(text.toString())
                acceptedCommitNanos.add(SystemClock.elapsedRealtimeNanos())
            }
            return accepted
        }
    }

    override fun setUp() {
        super.setUp()
        sandbox = io.secure.keyboard.diagnostics.LifetimeSandbox(instrumentation.targetContext)
        check(!sandbox.root.exists()) { "Incomplete isolated fixture needs cleanup" }
        sandbox.root.mkdirs()
        app = IsolatedApplication(sandbox)
        SettingsStore(context).let {
            it.editorPreview = false
            it.autoChineseSpacing = false
            it.englishCompletion = true
            it.englishSpaceSelectsFirst = true
            it.fourCodeAutoCommit = false
            it.vibration = false
            it.longPressSymbols = true
        }
        activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
        )
        onMainChecked {
            harness = ServiceHarness()
            service = harness.create(context)
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
            connection = ObservedConnection(requireNotNull(editor.onCreateInputConnection(info)))
            service.onCreateInputMethodInterface().startInput(connection, info)
            setField("active", true)
            eventTime = SystemClock.uptimeMillis()
        }
        waitFor("loaded dictionary and measured attached keyboard", 60_000) {
            field<Boolean>("loaded") && root.isAttachedToWindow && key("q").width > 0
        }
    }

    override fun tearDown() {
        releases.forEach { it.countDown() }
        var workers = emptyList<ExecutorService>()
        onMainChecked {
            if (::harness.isInitialized) { workers = io.secure.keyboard.diagnostics.sessionWorkers(service); harness.close() }
            if (::activity.isInitialized) activity.finish()
        }
        io.secure.keyboard.diagnostics.awaitStoppedWorkers(workers)
        if (::sandbox.isInitialized) sandbox.close()
        super.tearDown()
    }

    fun testThousandSupersededRefreshesKeepConfirmationAndEveryQueuedKeyInOrder() {
        reset(true)
        val release = gateWorker()
        val mailbox = field<io.secure.keyboard.concurrent.LatestTaskMailbox>("candidateQueries")
        onMainChecked {
            typeLetters("nihao")
            tap(key("五笔"))
            typeLetters("wo")
            tap(key("回车"))
            tap(key("五笔"))
            repeat(1_000) { invoke("refreshCandidates") }
            assertEquals(1, mailbox.snapshot().pending)
            assertNotNull(field<Any?>("pendingConfirmation"))
            assertEquals(4, field<ArrayDeque<*>>("queuedKeys").size)
            assertEquals("nihao", field<String>("code"))
        }
        release.countDown()
        awaitOrderedInput("你好wo ", "")
        onMainChecked { assertEquals(listOf("你好", "wo", " "), connection.commits) }
    }

    fun testFastSinglePointerBurstAcrossRowsPreservesEveryChineseAndEnglishTap() {
        val acrossRows = "qazwsxedcrfvtgbyhnujmikolp"
        val timings = mutableListOf<String>()
        for (chinese in listOf(true, false)) {
            reset(chinese)
            val expected = if (chinese) acrossRows else acrossRows.repeat(20)
            val drawn = CountDownLatch(1)
            var burstEndNanos = 0L
            var nextDrawNanos = 0L
            lateinit var drawListener: ViewTreeObserver.OnDrawListener
            onMainChecked {
                measuredDispatchNanos = 0
                maximumDispatchNanos = 0
                measuredEventCount = 0
                drawListener = ViewTreeObserver.OnDrawListener {
                    if (burstEndNanos != 0L && drawn.count > 0L) {
                        nextDrawNanos = SystemClock.elapsedRealtimeNanos() - burstEndNanos
                        drawn.countDown()
                    }
                }
                root.viewTreeObserver.addOnDrawListener(drawListener)
                val originalKeys = allKeys()
                expected.forEach { tap(key(it.toString())) }
                burstEndNanos = SystemClock.elapsedRealtimeNanos()
                root.invalidate()
                assertText(chinese, expected)
                originalKeys.forEach { (label, view) ->
                    assertSame("Typing must retain the $label touch target", view, key(label))
                }
            }
            val observedDraw = drawn.await(5, TimeUnit.SECONDS)
            onMainChecked { root.viewTreeObserver.removeOnDrawListener(drawListener) }
            timings.add("mode=${if (chinese) "Chinese" else "English"},keys=${expected.length}," +
                "events=$measuredEventCount,root_dispatch_total_ms=${measuredDispatchNanos / 1_000_000.0}," +
                "max_event_ms=${maximumDispatchNanos / 1_000_000.0}," +
                "next_draw_callback_ms=${if (observedDraw) nextDrawNanos / 1_000_000.0 else "unobserved"}")
        }
        timingStatus("RAPID_SYNTHETIC_EVENT_TIMING " + timings.joinToString("; "))
    }

    fun testRapidCodeSpaceNextWordCommitsFirstWordAndPreservesLaterCode() {
        reset(true)
        val release = gateWorker()
        onMainChecked {
            typeLetters("nihao")
            tap(key("五笔"))
            typeLetters("wo")
            assertEquals("", editor.text.toString())
            assertEquals("nihao", field<String>("code"))
            assertNotNull(field<Any?>("pendingConfirmation"))
        }
        val releasedAt = SystemClock.elapsedRealtimeNanos()
        release.countDown()
        awaitOrderedInput("你好", "wo")
        onMainChecked {
            assertEquals(listOf("你好"), connection.commits)
            timingStatus("RAPID_ASYNC_CONFIRM_TIMING worker_release_to_accepted_commit_ms=" +
                (connection.acceptedCommitNanos.first() - releasedAt) / 1_000_000.0 +
                ",local_followup_keys=2; excludes intentional test gate wait")
        }
    }

    fun testRapidCodePunctuationNextWordPreservesPunctuationExactlyOnce() {
        reset(true)
        val release = gateWorker()
        onMainChecked {
            typeLetters("nihao")
            tap(key("，。"))
            typeLetters("wo")
            assertEquals("", editor.text.toString())
        }
        release.countDown()
        awaitOrderedInput("你好，", "wo")
        onMainChecked { assertEquals(listOf("你好", "，"), connection.commits) }
    }

    fun testSeveralRapidWordsResolveSuccessiveQueriesInOriginalOrder() {
        reset(true)
        val release = gateWorker()
        onMainChecked {
            repeat(2) {
                typeLetters("nihao")
                tap(key("五笔"))
            }
            typeLetters("wo")
        }
        release.countDown()
        awaitOrderedInput("你好你好", "wo")
        onMainChecked { assertEquals(listOf("你好", "你好"), connection.commits) }
    }

    fun testRapidConfirmationBackspaceAndRawEnterPreserveReleaseOrder() {
        reset(true)
        val release = gateWorker()
        onMainChecked {
            typeLetters("nihao")
            tap(key("五笔"))
            typeLetters("wo")
            tap(key("删除"))
            tap(key("回车"))
            assertEquals("", editor.text.toString())
        }
        release.countDown()
        awaitOrderedInput("你好w", "")
        onMainChecked { assertEquals(listOf("你好", "w"), connection.commits) }
    }

    fun testRepeatedQueuedSpaceAndCapturedOneShotShiftRemainDistinctActions() {
        reset(true)
        val release = gateWorker()
        onMainChecked {
            typeLetters("nihao")
            tap(key("五笔"))
            tap(key("五笔"))
            tap(key("大写"))
            tap(key("W"))
            tap(key("o"))
        }
        release.countDown()
        awaitOrderedInput("你好 W", "o")
        onMainChecked { assertEquals(listOf("你好", " ", "W"), connection.commits) }
    }

    fun testRawEnterNeedsNoDictionaryAndQueuedEnterFollowsPriorConfirmation() {
        reset(true)
        val release = gateWorker()
        onMainChecked {
            typeLetters("nihao")
            tap(key("回车"))
            assertEquals("Raw Enter is immediate even while dictionary work is gated", "nihao", editor.text.toString())
            typeLetters("nihao")
            tap(key("五笔"))
            typeLetters("wo")
            tap(key("回车"))
            assertEquals("nihao", editor.text.toString())
        }
        release.countDown()
        awaitOrderedInput("nihao你好wo", "")
    }

    fun testCancelAndEditorRestartRevokePendingConfirmationAndLaterKeystrokes() {
        for (restart in listOf(false, true)) {
            reset(true)
            val release = gateWorker()
            val original = connection
            onMainChecked {
                typeLetters("nihao")
                tap(key("五笔"))
                typeLetters("wo")
                if (restart) {
                    val info = EditorInfo()
                    connection = ObservedConnection(requireNotNull(editor.onCreateInputConnection(info)))
                    service.onCreateInputMethodInterface().startInput(connection, info)
                } else {
                    invoke("clearPending")
                    invoke("renderHeader")
                }
                assertNull(field<Any?>("pendingConfirmation"))
                assertTrue(field<ArrayDeque<*>>("queuedKeys").isEmpty())
            }
            release.countDown()
            drainWorker()
            onMainChecked {
                assertEquals("", editor.text.toString())
                assertTrue(original.commits.isEmpty())
                assertTrue(connection.commits.isEmpty())
                assertEquals("", field<String>("code"))
            }
        }
    }

    fun testEmptyCandidateResultReleasesQueueAndKeepsRawCodeForEnter() {
        reset(true)
        val release = gateWorker()
        onMainChecked {
            typeLetters("qqqqqq")
            tap(key("五笔"))
            typeLetters("wo")
            tap(key("回车"))
        }
        release.countDown()
        awaitOrderedInput("qqqqqqwo", "")
    }

    fun testQueryFailureReleasesQueueInsteadOfBlockingLaterTyping() {
        reset(true)
        val release = gateWorker()
        val originalEngine = field<Any>("engine")
        try {
            onMainChecked {
                typeLetters("nihao")
                tap(key("五笔"))
                typeLetters("wo")
                // Deterministic failure at the actual worker query, before any host commit.
                setField("engine", null)
            }
            release.countDown()
            awaitOrderedInput("", "nihaowo")
        } finally {
            onMainChecked { setField("engine", originalEngine) }
        }
    }

    fun testExceptionalQueueSaturationIsExplicitAndClearableWithoutRawInsertion() {
        reset(true)
        val release = gateWorker()
        onMainChecked {
            typeLetters("nihao")
            tap(key("五笔"))
            repeat(1025) { tap(key("a")) }
            assertEquals(1024, field<ArrayDeque<*>>("queuedKeys").size)
            assertTrue(field<Boolean>("queueSaturationNotified"))
            assertEquals("", editor.text.toString())
            invoke("clearPending")
            invoke("renderHeader")
            assertFalse(field<Boolean>("queueSaturationNotified"))
            assertTrue(field<ArrayDeque<*>>("queuedKeys").isEmpty())
        }
        release.countDown()
        drainWorker()
        onMainChecked { assertEquals("", editor.text.toString()) }
    }

    fun testFastSameKeySequentialTapsHaveNoDebounceOrLoss() {
        for (chinese in listOf(true, false)) {
            reset(chinese)
            val expected = "a".repeat(if (chinese) 32 else 200)
            onMainChecked {
                val target = key("a")
                expected.forEach { tap(target) }
                assertText(chinese, expected)
                assertSame(target, key("a"))
            }
        }
    }

    fun testOverlappingDifferentKeysAndRowsCommitEveryTapInReleaseOrder() {
        for (chinese in listOf(true, false)) for (reverse in listOf(false, true)) {
            reset(chinese)
            onMainChecked {
                val expected = StringBuilder()
                repeat(3) {
                    for ((first, second) in listOf("q" to "p", "a" to "l", "z" to "m", "q" to "m")) {
                        down(2, center(key(first)))
                        down(11, center(key(second)))
                        if (reverse) { up(11); up(2); expected.append(second).append(first) }
                        else { up(2); up(11); expected.append(first).append(second) }
                    }
                }
                assertText(chinese, expected.toString())
            }
        }
    }

    fun testOverlappingSameKeyFingersDoNotCollapseIntoOneTap() {
        for (chinese in listOf(true, false)) for (reverse in listOf(false, true)) {
            reset(chinese)
            onMainChecked {
                val target = key("a")
                repeat(8) {
                    down(3, center(target))
                    down(9, center(target))
                    if (reverse) { up(9); up(3) } else { up(3); up(9) }
                }
                assertText(chinese, "a".repeat(16))
                assertSame(target, key("a"))
                assertEquals("Last release must restore key feedback", 1f, target.alpha)
            }
        }
    }

    fun testVisualHorizontalAndVerticalGapsRemainTouchableKeyCells() {
        reset(false)
        onMainChecked {
            val expected = StringBuilder()
            for ((left, right) in listOf("q" to "w", "a" to "s", "z" to "x")) {
                val a = bounds(key(left))
                val b = bounds(key(right))
                // Just left of the cell boundary: inside the visual gap on the old layout.
                tap(PointF((a.right + b.left) / 2f - .5f, a.exactCenterY()))
                expected.append(left)
            }
            for ((upper, lower) in listOf("t" to "g", "g" to "b")) {
                val a = bounds(key(upper))
                val b = bounds(key(lower))
                tap(PointF(a.exactCenterX(), (a.bottom + b.top) / 2f - .5f))
                expected.append(upper)
            }
            assertText(false, expected.toString())
        }
    }

    fun testCandidateResultsArrivingDuringHeldKeysKeepTheirTouchTargets() {
        reset(true)
        onMainChecked { tap(key("n")) }
        drainWorker()
        lateinit var i: KeyContainer
        lateinit var h: KeyContainer
        onMainChecked {
            i = key("i")
            h = key("h")
            down(2, center(i))
            down(11, center(h))
            // The actual query worker posts a candidate change between DOWN and UP.
            invoke("refreshCandidates")
        }
        drainWorker()
        onMainChecked {
            assertSame(i, key("i"))
            assertSame(h, key("h"))
            assertTrue(i.isAttachedToWindow)
            assertTrue(h.isAttachedToWindow)
            up(2)
            up(11)
            assertText(true, "nih")
        }
    }

    fun testShiftReleaseBetweenOverlappingLetterReleasesPreservesNextTapAndCase() {
        reset(false)
        onMainChecked {
            val a = key("a")
            val b = key("b")
            tap(key("大写"))
            assertSame(a, key("A"))
            down(2, center(a))
            down(11, center(b))
            up(2)
            assertSame("One-shot Shift must not detach the second held key", b, key("b"))
            up(11)
            assertText(false, "Ab")
            down(2, center(key("大写")))
            down(11, center(key("c")))
            up(2)
            up(11)
            assertText(false, "AbC")
        }
    }

    fun testSameKeyMultitouchMoveCancelsOnlyThePointerThatMovedAway() {
        reset(false)
        onMainChecked {
            val a = key("a")
            val start = center(a)
            down(2, start)
            down(11, start)
            move(2, PointF(start.x + 90 * context.resources.displayMetrics.density, start.y))
            up(2)
            up(11)
            assertText(false, "a")
            tap(a)
            assertText(false, "aa")
        }
    }

    fun testBackspaceAndSpaceSwipeKeepTouchModeForTheNextFastTap() {
        reset(false)
        waitFor("fixture editor has a focused foreground window before real touch", 20_000) {
            activity.hasWindowFocus() && editor.isShown && editor.isAttachedToWindow
        }
        var touchX = 0f
        var touchY = 0f
        onMainChecked {
            val location = IntArray(2)
            editor.getLocationOnScreen(location)
            touchX = location[0] + editor.width / 2f
            touchY = location[1] + editor.height / 2f
        }
        // Instrumentation.setInTouchMode changes WindowManager's global flag; an already
        // attached ViewRoot may still retain its old local mode. Route this precondition tap
        // through Android's input pipeline, unlike the intentional direct-root key fixtures.
        val touchDown = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(touchDown, SystemClock.uptimeMillis(), action, touchX, touchY, 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        waitFor("real touch establishes the fixture editor's local touch mode and focus", 20_000) {
            activity.hasWindowFocus() && editor.isFocused && editor.isInTouchMode
        }
        onMainChecked {
            touchModeStatus("before-delete")
            assertTrue("Precondition: real touch must establish this window's local touch mode", editor.isInTouchMode)
            assertTrue("Precondition: the host editor must own focus", editor.isFocused)
            editor.setText("abcd")
            editor.setSelection(4)
            tap(key("删除"))
        }
        instrumentation.waitForIdleSync()
        onMainChecked {
            assertEquals("abc", editor.text.toString())
            touchModeStatus("after-delete-before-swipe")
            val delta = 20 * context.resources.displayMetrics.density
            val start = center(key("English"))
            down(2, start)
            move(2, PointF(start.x - delta, start.y))
            up(2)
        }
        instrumentation.waitForIdleSync()
        onMainChecked {
            assertEquals(2, editor.selectionStart)
            touchModeStatus("after-delete-and-swipe")
            assertEquals(listOf(KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_DEL,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_LEFT),
                connection.keyEvents.map { it.keyCode })
            connection.keyEvents.forEach { event ->
                assertTrue(event.flags and KeyEvent.FLAG_SOFT_KEYBOARD != 0)
                assertTrue(event.flags and KeyEvent.FLAG_KEEP_TOUCH_MODE != 0)
                assertEquals("Ordinary cursor movement must not leak Shift", 0, event.metaState)
            }
            assertTrue("Soft-key navigation must keep touch mode", editor.isInTouchMode)
            assertTrue("Navigation must retain the host editor focus", editor.isFocused)
            tap(key("q"))
            assertEquals("abqc", editor.text.toString())
        }
    }

    /** Fixture-state evidence is emitted before assertions, so a failure retains its preconditions. */
    private fun touchModeStatus(stage: String) {
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "\nRAPID_TOUCH_MODE stage=$stage,windowFocus=${activity.hasWindowFocus()}," +
                "editorFocus=${editor.isFocused},localTouchMode=${editor.isInTouchMode}," +
                "keyEvents=${connection.keyEvents.map { "${it.keyCode}:${it.action}:${it.flags}" }}\n")
        })
    }

    fun testCancelAndMultitouchDoNotLeaveStuckRepeatOrProducePhantomTaps() {
        reset(false)
        onMainChecked {
            val a = key("a")
            down(2, center(a))
            down(11, center(a))
            cancel()
            assertText(false, "")
            tap(a)
            assertText(false, "a")
            assertEquals(1f, a.alpha)
            val delete = key("删除")
            down(2, center(delete))
            cancel()
            tap(key("b"))
            assertText(false, "ab")
        }
        // Wait for the first revoked hold deadline, using a scheduled marker rather than
        // adding delays to typing. A leaked delete hold would have removed the new text.
        val holdDeadlinePassed = CountDownLatch(1)
        Handler(Looper.getMainLooper()).postAtTime(
            { holdDeadlinePassed.countDown() },
            SystemClock.uptimeMillis() + SettingsStore(context).longPressDelay + 70L,
        )
        assertTrue(holdDeadlinePassed.await(5, TimeUnit.SECONDS))
        onMainChecked { assertText(false, "ab") }
    }

    fun testHeldSpacePreservesQueuedTapsAndReleaseSurvivesVoicePanelRebuild() {
        reset(true)
        val release = gateWorker()
        onMainChecked {
            typeLetters("nihao")
            tap(key("五笔"))
            typeLetters("wo")
            down(7, center(key("五笔")))
        }
        waitFor("held space transfers its pointer and waits behind earlier taps", 5_000) {
            field<Boolean>("heldVoice") && field<Boolean>("heldVoiceWaitingKeys")
        }
        onMainChecked {
            assertEquals("voice", field<String>("panel"))
            assertEquals("nihao", field<String>("code"))
            assertEquals(2, field<ArrayDeque<*>>("queuedKeys").size)
            assertNotNull(field<Any?>("pendingConfirmation"))
            assertFalse(field<Boolean>("voiceStarting"))
            assertNull(field<Any?>("voiceGuard"))
            invoke("renderBody") // Original space target is gone; the root still owns pointer 7.
            up(7)
            assertEquals("keys", field<String>("panel"))
            assertFalse(field<Boolean>("heldVoice"))
            assertTrue(connection.commits.isEmpty())
        }
        release.countDown()
        awaitOrderedInput("你好", "wo")
        onMainChecked {
            assertEquals("Only the earlier ordinary space may confirm text", listOf("你好"), connection.commits)
            assertFalse("Finishing the old queue after UP must not start voice", field<Boolean>("heldVoice"))
            assertEquals("keys", field<String>("panel"))
        }
    }

    fun testHeldSpaceRootCancelRevokesDeferredVoiceWithoutDroppingQueuedKeys() {
        reset(true)
        val release = gateWorker()
        onMainChecked {
            typeLetters("nihao")
            tap(key("五笔"))
            typeLetters("wo")
            down(7, center(key("五笔")))
        }
        waitFor("held space claimed before cancel", 5_000) { field<Boolean>("heldVoice") }
        onMainChecked {
            cancel()
            assertEquals("keys", field<String>("panel"))
            assertFalse(field<Boolean>("heldVoice"))
            assertEquals(2, field<ArrayDeque<*>>("queuedKeys").size)
        }
        release.countDown()
        awaitOrderedInput("你好", "wo")
    }

    fun testEarlyHorizontalSpaceMoveCancelsHoldBeforeFirstCursorStep() {
        reset(false)
        onMainChecked {
            val start = center(key("English"))
            val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()
            val cursorStep = 18 * context.resources.displayMetrics.density
            assertTrue("Fixture supports a move between hold slop and cursor step", slop + 1 < cursorStep)
            down(7, start)
            move(7, PointF(start.x + slop + 1, start.y))
        }
        waitPastHoldDeadline()
        onMainChecked {
            assertEquals("keys", field<String>("panel"))
            assertFalse(field<Boolean>("heldVoice"))
            up(7)
        }
        // Auto-spacing can commit asynchronously; do not assert before its worker completes.
        waitFor("Below the cursor threshold, release remains an ordinary space", 5000) {
            editor.text.toString() == " "
        }
    }

    fun testSpaceMultitouchKeepsBothTapsAndDisablesTheHold() {
        reset(false)
        onMainChecked {
            val center = center(key("English"))
            down(7, center)
            down(22, center)
        }
        waitPastHoldDeadline()
        onMainChecked {
            assertFalse(field<Boolean>("heldVoice"))
            up(22)
            up(7)
            assertEquals("  ", editor.text.toString())
        }
    }

    fun testDifferentKeyOverlapCannotRearmSpaceVoiceAfterOtherFingerLifts() {
        // Isolate overlapping-touch voice cancellation from English candidate selection.
        SettingsStore(context).englishCompletion = false
        reset(false)
        onMainChecked {
            down(7, center(key("English")))
            down(22, center(key("q")))
            up(22)
        }
        waitPastHoldDeadline()
        onMainChecked {
            assertFalse(field<Boolean>("heldVoice"))
            up(7)
        }
        awaitOrderedInput("q ", "")
    }

    fun testSpaceAsSecondFingerCannotStartVoiceAfterFirstKeyReleases() {
        // Isolate overlapping-touch voice cancellation from English candidate selection.
        SettingsStore(context).englishCompletion = false
        reset(false)
        onMainChecked {
            down(22, center(key("q")))
            down(7, center(key("English")))
            up(22)
        }
        waitPastHoldDeadline()
        onMainChecked {
            assertFalse(field<Boolean>("heldVoice"))
            up(7)
        }
        awaitOrderedInput("q ", "")
    }

    private fun waitPastHoldDeadline() {
        val finished = CountDownLatch(1)
        Handler(Looper.getMainLooper()).postAtTime(
            { finished.countDown() },
            SystemClock.uptimeMillis() + SettingsStore(context).longPressDelay + 70L,
        )
        assertTrue(finished.await(5, TimeUnit.SECONDS))
    }

    private fun reset(chinese: Boolean) {
        onMainChecked {
            if (pointers.isNotEmpty()) cancel()
            invoke("clearPending")
            editor.setText("")
            connection.commits.clear()
            connection.acceptedCommitNanos.clear()
            connection.keyEvents.clear()
            setField("chinese", chinese)
            setField("shifted", false)
            setField("capsLocked", false)
            setField("symbols", false)
            setField("panel", "keys")
            invoke("renderHeader")
            invoke("renderBody")
        }
        instrumentation.waitForIdleSync()
    }

    private fun typeLetters(text: String) = text.forEach { tap(key(it.toString())) }

    private fun gateWorker(): CountDownLatch {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        releases.add(release)
        worker.execute {
            started.countDown()
            check(release.await(30, TimeUnit.SECONDS)) { "Test candidate gate timed out" }
        }
        assertTrue("Candidate gate must be running", started.await(20, TimeUnit.SECONDS))
        return release
    }

    private fun awaitOrderedInput(expectedHost: String, expectedCode: String) {
        waitFor("ordered host=$expectedHost and local code=$expectedCode", 20_000) {
            editor.text.toString() == expectedHost && field<String>("code") == expectedCode &&
                field<Any?>("pendingConfirmation") == null && field<ArrayDeque<*>>("queuedKeys").isEmpty()
        }
        onMainChecked {
            assertEquals(expectedHost, editor.text.toString())
            assertEquals(expectedCode, field<String>("code"))
        }
    }

    private fun assertText(chinese: Boolean, expected: String) {
        val actual = if (chinese) field<String>("code") else editor.text.toString()
        if (actual != expected) saveFailureView()
        if (chinese) {
            assertEquals("Every release must reach keyboard-local Chinese code", expected, field<String>("code"))
            assertEquals("Unselected Chinese code must not leak into the host", "", editor.text.toString())
            assertTrue(connection.commits.isEmpty())
        } else {
            assertEquals("Every release must reach the real host in order", expected, editor.text.toString())
            assertEquals(expected, connection.commits.joinToString(""))
        }
    }

    /** Main-thread rendering captures the attached host/keyboard before assertion teardown. */
    private fun saveFailureView() {
        val view = activity.window.decorView
        if (view.width <= 0 || view.height <= 0) return
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            val directory = File(requireNotNull(context.getExternalFilesDir(null)), "validation")
            check(directory.isDirectory || directory.mkdirs())
            File(directory, "rapid-failure-${name}.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun allKeys(): Map<String, KeyContainer> {
        val found = linkedMapOf<String, KeyContainer>()
        fun visit(view: View) {
            if (view is KeyContainer) found[view.contentDescription.toString()] = view
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(root)
        return found
    }

    private fun key(description: String): KeyContainer =
        requireNotNull(allKeys()[description]) { "Key missing: $description" }

    private fun bounds(view: View): Rect = Rect(0, 0, view.width, view.height).also {
        root.offsetDescendantRectToMyCoords(view, it)
    }

    private fun center(view: View): PointF = bounds(view).let { PointF(it.exactCenterX(), it.exactCenterY()) }
    private fun tap(view: View) = tap(center(view))
    private fun tap(point: PointF) { down(0, point); up(0) }

    private fun down(id: Int, point: PointF) {
        check(id !in pointers)
        if (pointers.isEmpty()) downTime = eventTime + 1
        pointers[id] = point
        val index = pointers.keys.indexOf(id)
        dispatch(if (pointers.size == 1) MotionEvent.ACTION_DOWN else
            MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
    }

    private fun up(id: Int) {
        val index = pointers.keys.indexOf(id)
        check(index >= 0)
        dispatch(if (pointers.size == 1) MotionEvent.ACTION_UP else
            MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
        pointers.remove(id)
    }

    private fun move(id: Int, point: PointF) {
        check(id in pointers)
        pointers[id] = point
        dispatch(MotionEvent.ACTION_MOVE)
    }

    private fun cancel() {
        dispatch(MotionEvent.ACTION_CANCEL)
        pointers.clear()
    }

    private fun dispatch(action: Int) {
        val properties = pointers.keys.map { id -> MotionEvent.PointerProperties().apply {
            this.id = id
            toolType = MotionEvent.TOOL_TYPE_FINGER
        } }.toTypedArray()
        val coordinates = pointers.values.map { point -> MotionEvent.PointerCoords().apply {
            x = point.x; y = point.y; pressure = 1f; size = .1f
        } }.toTypedArray()
        val event = MotionEvent.obtain(downTime, ++eventTime, action, pointers.size,
            properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        val started = SystemClock.elapsedRealtimeNanos()
        try { root.dispatchTouchEvent(event) } finally {
            val elapsed = SystemClock.elapsedRealtimeNanos() - started
            measuredDispatchNanos += elapsed
            maximumDispatchNanos = maxOf(maximumDispatchNanos, elapsed)
            measuredEventCount++
            event.recycle()
        }
    }

    /** Synthetic fixture metrics only; no production text logging or phone-latency assertions. */
    private fun timingStatus(summary: String) {
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "\n$summary; software-emulator measurements, no latency threshold\n")
        })
    }

    private fun drainWorker() {
        val done = CountDownLatch(1)
        worker.execute { done.countDown() }
        assertTrue("Candidate worker must settle", done.await(20, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }

    private fun waitFor(description: String, timeoutMs: Long, condition: () -> Boolean) {
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
    private fun <T> field(name: String): T = io.secure.keyboard.SessionTestAccess.field(name).apply { isAccessible = true }.get(service) as T

    private fun setField(name: String, value: Any?) {
        io.secure.keyboard.SessionTestAccess.field(name).apply { isAccessible = true }.set(service, value)
    }

    private fun invoke(name: String) = io.secure.keyboard.SessionTestAccess.method(name).apply { isAccessible = true }.invoke(service)
    /** Keep diagnostic assertion failures on JUnit's thread instead of crashing Android main. */
    private fun onMainChecked(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try { block() } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }

}
