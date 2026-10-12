package io.secure.keyboard.voice

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.test.ServiceTestCase
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import kotlin.math.abs
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import io.secure.keyboard.MainActivity
import io.secure.keyboard.OfflineKeyboardService

/** Production service commit path with synthetic editor data; no model/audio fixtures required. */
@Suppress("DEPRECATION")
class DirectVoiceInputTest : InstrumentationTestCase() {
    private lateinit var activity: Activity
    private lateinit var service: OfflineKeyboardService
    private lateinit var harness: Harness
    private lateinit var connection: Connection
    private val releases = mutableListOf<CountDownLatch>()
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

    private open class Connection(view: View) : BaseInputConnection(view, true) {
        val commits = mutableListOf<String>()
        var text = ""
        @Volatile var cursor = 0
        @Volatile var selectionEnd = 0
        @Volatile var readOnMain = false
        @Volatile var unknownSelection = false
        var acceptCommit = true
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
            if (Looper.myLooper() == Looper.getMainLooper()) readOnMain = true
            if (unknownSelection) return null
            return ExtractedText().apply {
                startOffset = 0
                selectionStart = cursor
                selectionEnd = this@Connection.selectionEnd
            }
        }
        override fun commitText(value: CharSequence, newCursorPosition: Int): Boolean {
            check(Looper.myLooper() == Looper.getMainLooper())
            if (!acceptCommit) return false
            commits.add(value.toString())
            text = text.substring(0, cursor) + value + text.substring(selectionEnd)
            cursor += value.length
            selectionEnd = cursor
            return true
        }
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = ""
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
        override fun getSelectedText(flags: Int): CharSequence? = null
    }

    override fun setUp() {
        super.setUp()
        activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        onMainChecked {
            harness = Harness()
            service = harness.create(context)
            connection = Connection(View(activity))
            startEditor(connection)
            activity.setContentView(service.onCreateInputView())
            setField("active", true)
            arm()
        }
    }

    override fun tearDown() {
        releases.forEach { it.countDown() }
        onMainChecked {
            if (::harness.isInitialized) harness.close()
            if (::activity.isInitialized) activity.finish()
        }
        super.tearDown()
    }

    fun testSlowValidationAndHundredCanceledSessionsNeverCoalesceCurrentFinalSegments() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1).also { releases.add(it) }
        val reads = java.util.concurrent.atomic.AtomicInteger()
        onMainChecked {
            invoke("cancelVoice")
            connection = object : Connection(View(activity)) {
                override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
                    check(Looper.myLooper() != Looper.getMainLooper())
                    reads.incrementAndGet()
                    entered.countDown()
                    check(release.await(30, TimeUnit.SECONDS)) { "Slow voice host gate timed out" }
                    return super.getExtractedText(request, flags)
                }
            }
            startEditor(connection)
            setField("active", true)
            arm()
        }
        queue(0, "obsolete running")
        assertTrue(entered.await(20, TimeUnit.SECONDS))
        val mailbox = field<io.secure.keyboard.concurrent.LatestTaskMailbox>("voiceSelectionChecks")
        onMainChecked {
            repeat(100) { index ->
                invoke("cancelVoice")
                assertEquals(0, mailbox.snapshot().pending)
                arm()
                OfflineKeyboardService::class.java.getDeclaredMethod(
                    "queueVoiceSegment", Long::class.javaPrimitiveType, String::class.java
                ).apply { isAccessible = true }.invoke(service, 0L, "obsolete $index")
                assertEquals(1, mailbox.snapshot().running)
                assertEquals(1, mailbox.snapshot().pending)
            }
            invoke("cancelVoice")
            arm()
            repeat(9) { index ->
                OfflineKeyboardService::class.java.getDeclaredMethod(
                    "queueVoiceSegment", Long::class.javaPrimitiveType, String::class.java
                ).apply { isAccessible = true }.invoke(service, index.toLong(), "当前$index")
            }
            assertTrue(field<Boolean>("voiceValidationPending"))
            assertEquals("One current validation plus the existing eight-final queue", 8,
                field<ArrayDeque<Pair<Long, String>>>("voiceSegments").size)
            assertEquals(1, mailbox.snapshot().pending)
        }
        release.countDown()
        waitFor { connection.commits.size == 9 && !mailbox.snapshot().scheduled }
        onMainChecked {
            assertEquals((0 until 9).map { "当前$it，" }, connection.commits)
            assertEquals("Only the running canceled query and nine authorized validations read the host", 10, reads.get())
            assertTrue(field<ArrayDeque<Pair<Long, String>>>("voiceSegments").isEmpty())
            assertFalse(field<Boolean>("voiceValidationPending"))
            assertFalse(connection.readOnMain)
        }
    }

    fun testHundredCanceledStartChecksOwnOnlyRunningAndNewestEditor() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1).also { releases.add(it) }
        val obsoleteReads = java.util.concurrent.atomic.AtomicInteger()
        val latestReads = java.util.concurrent.atomic.AtomicInteger()
        fun startCheck(target: Connection) {
            startEditor(target)
            setField("active", true)
            setField("panel", "voice")
            setField("voiceStarting", true)
            submitStartCheck(field("voiceRequest"), field("voiceEditorGeneration"), target)
        }
        onMainChecked {
            startCheck(object : Connection(View(activity)) {
                override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
                    check(Looper.myLooper() != Looper.getMainLooper())
                    entered.countDown()
                    check(release.await(30, TimeUnit.SECONDS)) { "Voice start gate timed out" }
                    return null // No microphone/model is started by this ownership diagnostic.
                }
            })
        }
        assertTrue(entered.await(20, TimeUnit.SECONDS))
        val mailbox = field<io.secure.keyboard.concurrent.LatestTaskMailbox>("voiceSelectionChecks")
        onMainChecked {
            repeat(100) {
                startCheck(object : Connection(View(activity)) {
                    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
                        obsoleteReads.incrementAndGet(); return null
                    }
                })
                assertEquals(1, mailbox.snapshot().running)
                assertEquals(1, mailbox.snapshot().pending)
            }
            startCheck(object : Connection(View(activity)) {
                override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
                    check(Looper.myLooper() != Looper.getMainLooper())
                    latestReads.incrementAndGet(); return null
                }
            })
        }
        release.countDown()
        waitFor { !field<Boolean>("voiceStarting") && !mailbox.snapshot().scheduled }
        onMainChecked {
            assertEquals(0, obsoleteReads.get())
            assertEquals(1, latestReads.get())
            assertNull(field<VoiceCommitGuard?>("voiceGuard"))
            assertTrue(connection.commits.isEmpty())
        }
    }

    fun testAlreadyObsoleteCapturedStartTokensSkipHostBeforeBlockingApi() {
        val release = gateValidationWorker()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val mailbox = field<io.secure.keyboard.concurrent.LatestTaskMailbox>("voiceSelectionChecks")
        onMainChecked {
            val oldRequest = field<Long>("voiceRequest")
            val oldEditor = field<Int>("voiceEditorGeneration")
            invoke("cancelVoice")
            val target = object : Connection(View(activity)) {
                override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
                    reads.incrementAndGet(); return null
                }
            }
            // Intentionally submit an already-revoked capture to exercise the worker's pre-read
            // volatile token check independently of normal cancel's pending-mailbox clear.
            submitStartCheck(oldRequest, oldEditor, target)
            assertEquals(1, mailbox.snapshot().pending)
        }
        release.countDown()
        waitFor { !mailbox.snapshot().scheduled }
        assertEquals(0, reads.get())
    }

    private fun submitStartCheck(request: Long, editor: Int, target: InputConnection) {
        OfflineKeyboardService::class.java.getDeclaredMethod(
            "checkVoiceStart", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            InputConnection::class.java,
        ).apply { isAccessible = true }.invoke(service, request, editor, target)
    }

    fun testPreviewNeverCommitsAndFinalizedSegmentIdsCommitExactlyOnce() {
        instrumentation.runOnMainSync {
            val voice = invoke("createVoiceSession") as OfflineVoiceSession
            setField("voice", voice)
            val listener = OfflineVoiceSession::class.java.getDeclaredField("listener")
                .apply { isAccessible = true }.get(voice) as OfflineVoiceSession.Listener
            listener.onPreview("未完成的预览", false)
            listener.onPreview("最终预览也不能重复输入", true)
            assertTrue(connection.commits.isEmpty())
            listener.onSegment(0, "你好")
            listener.onSegment(0, "你好")
            listener.onSegment(1, "你好")
        }
        waitFor { connection.commits.size == 2 }
        instrumentation.runOnMainSync {
            assertEquals("你好，你好，", connection.text)
            assertEquals(listOf("你好，", "你好，"), connection.commits)
            assertFalse("Host queries must never block IME/main", connection.readOnMain)
        }
    }

    fun testVoicePreviewCentersPlaceholderAndMultilineTextWithoutHidingStatusOrErrors() {
        instrumentation.runOnMainSync {
            val listener = syntheticListener()
            val preview = field<TextView>("voiceText")
            val status = field<TextView>("voiceStatus")
            val scroll = preview.parent as ScrollView
            val column = scroll.parent as ViewGroup
            val cancel = column.getChildAt(column.childCount - 1) as TextView
            val body = field<View>("body")
            val density = context.resources.displayMetrics.density
            fun layoutPreview() {
                body.measure(
                    View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((220 * density).toInt(), View.MeasureSpec.EXACTLY),
                )
                body.layout(0, 0, body.measuredWidth, body.measuredHeight)
                assertEquals(Gravity.CENTER, preview.gravity)
                assertEquals(View.TEXT_ALIGNMENT_CENTER, preview.textAlignment)
                assertTrue(scroll.isFillViewport)
                assertEquals(scroll.height, preview.height)
                val textLayout = requireNotNull(preview.layout)
                for (line in 0 until textLayout.lineCount) {
                    assertTrue("Every preview line stays horizontally centered",
                        abs((textLayout.getLineLeft(line) + textLayout.getLineRight(line)) / 2f -
                            textLayout.width / 2f) <= 1f)
                }
                assertEquals("取消", cancel.text.toString())
                assertEquals("Cancel stays at the bottom", column.height - column.paddingBottom, cancel.bottom)
                assertTrue("Preview never overlaps Cancel", scroll.bottom <= cancel.top)
            }
            // Native production View.draw evidence; this is not a compositor screenshot.
            fun savePreviewRender(name: String) {
                val image = Bitmap.createBitmap(body.width, body.height, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(image)
                    canvas.drawColor(field<Int>("bg"))
                    body.draw(canvas)
                    val directory = File(requireNotNull(context.getExternalFilesDir(null)), "validation")
                    check(directory.isDirectory || directory.mkdirs())
                    File(directory, name).outputStream().use {
                        check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally {
                    image.recycle()
                }
            }
            assertEquals("...", preview.text.toString())
            listener.onState(OfflineVoiceSession.State.LISTENING)
            listener.onPreview("", false)
            layoutPreview()
            assertEquals("...", preview.text.toString())
            assertEquals("正在聆听", status.text.toString())
            savePreviewRender("voice-centered-placeholder-render.png")
            val recognized = "第一行识别文字\n第二行\n最后一行"
            listener.onPreview(recognized, false)
            layoutPreview()
            assertEquals(recognized, preview.text.toString())
            assertEquals(3, preview.layout.lineCount)
            savePreviewRender("voice-centered-preview-render.png")
            listener.onPreview("", true)
            assertEquals("本次语音已结束", preview.text.toString())
            val error = "请在语音设置中允许麦克风权限"
            listener.onError(error)
            layoutPreview()
            assertEquals("Errors remain visible instead of becoming the placeholder", error, preview.text.toString())
            assertTrue(connection.commits.isEmpty())
        }
    }

    fun testCancelKeepsInsertedSegmentAndDropsQueuedFinalization() {
        queue(0, "已输入")
        waitFor { connection.commits.size == 1 }
        val release = gateValidationWorker()
        queue(1, "尚未输入")
        instrumentation.runOnMainSync { invoke("cancelTransient") }
        release.countDown()
        drainValidationWorker()
        instrumentation.runOnMainSync {
            assertEquals("已输入，", connection.text)
            assertNull(field<VoiceCommitGuard?>("voiceGuard"))
            assertTrue(field<ArrayDeque<Pair<Long, String>>>("voiceSegments").isEmpty())
        }
    }

    fun testEditorReplacementRevokesInFlightCommitForBothTargets() {
        val release = gateValidationWorker()
        queue(0, "过期结果")
        lateinit var replacement: Connection
        instrumentation.runOnMainSync {
            replacement = Connection(View(activity))
            startEditor(replacement)
        }
        release.countDown()
        drainValidationWorker()
        instrumentation.runOnMainSync {
            assertTrue(connection.commits.isEmpty())
            assertTrue(replacement.commits.isEmpty())
        }
    }

    fun testOldValidationCannotCommitIntoRestartedSessionOnSameEditor() {
        val release = gateValidationWorker()
        queue(0, "旧会话")
        instrumentation.runOnMainSync {
            invoke("cancelVoice")
            arm()
        }
        queue(0, "新会话")
        release.countDown()
        waitFor { connection.commits.size == 1 }
        instrumentation.runOnMainSync { assertEquals("新会话，", connection.text) }
    }

    fun testManualCursorAndSelectionChangesStopWithoutRewritingHost() {
        queue(0, "保留原文")
        waitFor { connection.commits.size == 1 }
        instrumentation.runOnMainSync {
            // Acknowledge our own commit, then simulate the user selecting existing text.
            service.onUpdateSelection(0, 0, 5, 5, -1, -1)
            connection.cursor = 0
            connection.selectionEnd = 4
            service.onUpdateSelection(5, 5, 0, 4, -1, -1)
        }
        queue(1, "不能覆盖")
        drainValidationWorker()
        instrumentation.runOnMainSync {
            assertEquals("保留原文，", connection.text)
            assertNull(field<VoiceCommitGuard?>("voiceGuard"))
        }
    }

    fun testUnreportedCursorMovementAndUnsupportedSelectionFailClosed() {
        instrumentation.runOnMainSync { connection.unknownSelection = true }
        queue(0, "不能输入")
        waitFor { field<VoiceCommitGuard?>("voiceGuard") == null }
        instrumentation.runOnMainSync {
            assertTrue(connection.commits.isEmpty())
            connection.unknownSelection = false
            connection.text = "原文"
            connection.cursor = 2
            connection.selectionEnd = 2
            arm()
            connection.cursor = 0
            connection.selectionEnd = 0
        }
        queue(0, "不能改写")
        waitFor { field<VoiceCommitGuard?>("voiceGuard") == null }
        instrumentation.runOnMainSync { assertEquals("原文", connection.text) }
    }

    fun testHideRevokesQueuedSegmentAndRejectedCommitStopsSession() {
        val release = gateValidationWorker()
        queue(0, "已隐藏")
        instrumentation.runOnMainSync { service.onFinishInputView(false) }
        release.countDown()
        drainValidationWorker()
        instrumentation.runOnMainSync {
            assertTrue(connection.commits.isEmpty())
            setField("active", true)
            arm()
            connection.acceptCommit = false
        }
        queue(0, "被拒绝")
        waitFor { field<VoiceCommitGuard?>("voiceGuard") == null }
        instrumentation.runOnMainSync { assertTrue(connection.commits.isEmpty()) }
    }

    fun testVoicePanelContainsOnlyCancelAction() {
        instrumentation.runOnMainSync {
            val texts = mutableListOf<TextView>()
            fun walk(view: View) {
                if (view is TextView) texts.add(view)
                if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
            walk(field("body"))
            assertEquals(listOf("取消"), texts.filter { it.isClickable }.map { it.text.toString() })
            texts.single { it.text == "取消" }.performClick()
            assertEquals("keys", field<String>("panel"))
            assertNull(field<VoiceCommitGuard?>("voiceGuard"))
        }
    }

    fun testRetappingVoiceClosesPanelAndRevokesPendingSegment() {
        queue(0, "已输入")
        waitFor { connection.commits.size == 1 }
        val release = gateValidationWorker()
        queue(1, "取消后的片段")
        instrumentation.runOnMainSync {
            invokeTool("voice")
            assertEquals("keys", field<String>("panel"))
            assertNull(field<VoiceCommitGuard?>("voiceGuard"))
            assertTrue(field<ArrayDeque<Pair<Long, String>>>("voiceSegments").isEmpty())
        }
        release.countDown()
        drainValidationWorker()
        instrumentation.runOnMainSync { assertEquals("已输入，", connection.text) }
    }

    fun testInlineToolsToggleAndDifferentToolSwitchesPanel() {
        instrumentation.runOnMainSync {
            invokeTool("voice") // close the armed fixture without requesting a microphone
            for (tool in listOf("clipboard", "phrases", "quickedit", "emoji", "layout")) {
                invokeTool(tool)
                assertEquals(tool, field<String>("panel"))
                invokeTool(tool)
                assertEquals("keys", field<String>("panel"))
            }
            invokeTool("clipboard")
            invokeTool("phrases")
            assertEquals("phrases", field<String>("panel"))
            invokeTool("quickedit")
            assertEquals("quickedit", field<String>("panel"))
            invokeTool("quickedit")
            assertEquals("keys", field<String>("panel"))
        }
    }

    fun testSwitchingFromVoiceToAnotherToolRevokesQueuedSpeech() {
        val release = gateValidationWorker()
        queue(0, "不能插入")
        instrumentation.runOnMainSync {
            invokeTool("clipboard")
            assertEquals("clipboard", field<String>("panel"))
            assertNull(field<VoiceCommitGuard?>("voiceGuard"))
        }
        release.countDown()
        drainValidationWorker()
        instrumentation.runOnMainSync { assertTrue(connection.commits.isEmpty()) }
    }

    fun testHeldReleaseFlushesFinalSegmentAndWaitsForGuardedCommitBeforeReturningKeys() {
        val release = gateValidationWorker()
        lateinit var listener: OfflineVoiceSession.Listener
        instrumentation.runOnMainSync {
            listener = syntheticListener()
            setField("heldVoice", true)
            setField("voiceState", OfflineVoiceSession.State.LISTENING)
            invoke("finishHeldVoice")
            assertEquals("voice", field<String>("panel"))
            assertNotNull(field<VoiceCommitGuard?>("voiceGuard"))
            listener.onSegment(0, "最后一句")
            listener.onPreview("最后一句", true)
            listener.onState(OfflineVoiceSession.State.IDLE)
            assertTrue(field<Boolean>("voiceValidationPending"))
            assertEquals("Native finish must not discard an in-flight editor check", "voice", field<String>("panel"))
            assertTrue(connection.commits.isEmpty())
        }
        release.countDown()
        waitFor { field<String>("panel") == "keys" }
        instrumentation.runOnMainSync {
            assertEquals(listOf("最后一句，"), connection.commits)
            assertFalse(field<Boolean>("heldVoice"))
            assertNull(field<VoiceCommitGuard?>("voiceGuard"))
        }
    }

    fun testHeldReleaseAfterNativeEndStillWaitsForEveryQueuedSegment() {
        val release = gateValidationWorker()
        instrumentation.runOnMainSync {
            val listener = syntheticListener()
            setField("heldVoice", true)
            listener.onSegment(0, "重复")
            listener.onSegment(1, "重复")
            listener.onState(OfflineVoiceSession.State.IDLE)
            invoke("finishHeldVoice")
            assertEquals("voice", field<String>("panel"))
        }
        release.countDown()
        waitFor { field<String>("panel") == "keys" }
        instrumentation.runOnMainSync { assertEquals(listOf("重复，", "重复，"), connection.commits) }
    }

    fun testNativeEditTextFinalSegmentsAndHeldReleaseUseFormattedCursorExactlyOnce() {
        lateinit var editor: EditText
        lateinit var listener: OfflineVoiceSession.Listener
        val commits = mutableListOf<String>()
        var queriedOnMain = false
        instrumentation.runOnMainSync {
            invoke("cancelVoice")
            editor = EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                showSoftInputOnFocus = false
                setText("前𥝚")
                setSelection(text.length)
            }
            val keyboard = field<View>("root")
            (keyboard.parent as? ViewGroup)?.removeView(keyboard)
            activity.setContentView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(editor, LinearLayout.LayoutParams(-1, 100))
                addView(keyboard, LinearLayout.LayoutParams(-1, 0, 1f))
            })
            editor.requestFocus()
            val info = EditorInfo()
            val native = requireNotNull(editor.onCreateInputConnection(info))
            val host = object : InputConnectionWrapper(native, false) {
                override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
                    queriedOnMain = queriedOnMain || Looper.myLooper() == Looper.getMainLooper()
                    // Model the cross-process dispatch: read the actual widget on its UI thread.
                    var result: ExtractedText? = null
                    instrumentation.runOnMainSync { result = native.getExtractedText(request, flags) }
                    return result
                }
                override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                    check(Looper.myLooper() == Looper.getMainLooper())
                    val accepted = super.commitText(text, newCursorPosition)
                    if (accepted) commits.add(text.toString())
                    return accepted
                }
            }
            service.onCreateInputMethodInterface().startInput(host, info)
            setField("active", true)
            setField("panel", "voice")
            setField("voiceConnection", host)
            setField("voiceGuard", VoiceCommitGuard(field("voiceEditorGeneration"), editor.selectionStart))
            setField("heldVoice", true)
            invoke("renderBody")
            listener = syntheticListener()
            listener.onState(OfflineVoiceSession.State.LISTENING)
            listener.onPreview("第一段", false)
            assertEquals("第一段", field<TextView>("voiceText").text.toString())
            assertEquals("前𥝚", editor.text.toString())
            listener.onSegment(0, "第一段")
            listener.onSegment(0, "第一段")
            listener.onSegment(1, " \t\n")
            listener.onSegment(2, "第二段。")
        }
        waitFor { commits.size == 2 }
        instrumentation.runOnMainSync {
            assertTrue(editor.isAttachedToWindow)
            assertEquals("前𥝚第一段，第二段。", editor.text.toString())
            assertEquals(editor.text.length, editor.selectionStart)
            assertEquals(editor.selectionStart, field<VoiceCommitGuard>("voiceGuard").expectedCursor)
        }
        val release = gateValidationWorker()
        instrumentation.runOnMainSync {
            invoke("finishHeldVoice")
            listener.onSegment(3, "末段😀")
            listener.onSegment(3, "末段😀")
            listener.onPreview("末段😀", true)
            listener.onState(OfflineVoiceSession.State.IDLE)
            assertEquals("voice", field<String>("panel"))
            assertEquals("末段😀", field<TextView>("voiceText").text.toString())
            assertEquals(2, commits.size)
        }
        release.countDown()
        waitFor { field<String>("panel") == "keys" }
        instrumentation.runOnMainSync {
            assertEquals(listOf("第一段，", "第二段。", "末段😀，"), commits)
            assertEquals("前𥝚第一段，第二段。末段😀，", editor.text.toString())
            assertEquals(editor.text.length, editor.selectionStart)
            assertEquals(editor.selectionStart, editor.selectionEnd)
            assertFalse(queriedOnMain)
            assertNull(field<VoiceCommitGuard?>("voiceGuard"))
            listener.onSegment(4, "结束后的回调")
            assertEquals(3, commits.size)
        }
    }

    fun testHeldCancelDiscardsFinalizationAndPreservesLocalUnconfirmedCode() {
        val release = gateValidationWorker()
        instrumentation.runOnMainSync {
            setField("heldVoice", true)
            setField("chinese", true)
            setField("code", "nihao")
        }
        queue(0, "未提交语音")
        instrumentation.runOnMainSync {
            invoke("cancelVoice")
            assertEquals("keys", field<String>("panel"))
            assertEquals("nihao", field<String>("code"))
        }
        release.countDown()
        drainValidationWorker()
        instrumentation.runOnMainSync { assertTrue(connection.commits.isEmpty()) }
    }

    fun testHeldSuccessfulFinalizationDoesNotCommitOrClearLocalRawCode() {
        instrumentation.runOnMainSync {
            val listener = syntheticListener()
            setField("heldVoice", true)
            setField("chinese", true)
            setField("code", "nihao")
            invoke("finishHeldVoice")
            listener.onSegment(0, "语音")
            listener.onState(OfflineVoiceSession.State.IDLE)
        }
        waitFor { field<String>("panel") == "keys" }
        instrumentation.runOnMainSync {
            assertEquals("nihao", field<String>("code"))
            assertEquals(listOf("语音，"), connection.commits)
        }
    }

    fun testHeldReleaseDuringAsyncPreparationRevokesRequestAndNeverAddsSpace() {
        instrumentation.runOnMainSync {
            setField("heldVoice", true)
            setField("voiceStarting", true)
            val oldRequest = field<Long>("voiceRequest")
            invoke("finishHeldVoice")
            assertTrue(field<Long>("voiceRequest") > oldRequest)
            assertFalse(field<Boolean>("voiceStarting"))
            assertEquals("keys", field<String>("panel"))
            assertTrue(connection.commits.isEmpty())
        }
    }

    fun testHeldEditorChangeRevokesQueuedFinalAndReturnsKeys() {
        val release = gateValidationWorker()
        instrumentation.runOnMainSync { setField("heldVoice", true) }
        queue(0, "旧输入框结果")
        instrumentation.runOnMainSync { service.onUpdateSelection(0, 0, 1, 1, -1, -1) }
        release.countDown()
        drainValidationWorker()
        instrumentation.runOnMainSync {
            assertEquals("keys", field<String>("panel"))
            assertTrue(connection.commits.isEmpty())
        }
    }

    private fun syntheticListener(): OfflineVoiceSession.Listener {
        val voice = invoke("createVoiceSession") as OfflineVoiceSession
        setField("voice", voice)
        return OfflineVoiceSession::class.java.getDeclaredField("listener")
            .apply { isAccessible = true }.get(voice) as OfflineVoiceSession.Listener
    }

    private fun invokeTool(tool: String) = OfflineKeyboardService::class.java
        .getDeclaredMethod("openTool", String::class.java).apply { isAccessible = true }
        .invoke(service, tool)

    private fun arm() {
        setField("panel", "voice")
        setField("voiceConnection", connection)
        setField("voiceGuard", VoiceCommitGuard(field("voiceEditorGeneration"), connection.cursor))
        invoke("renderBody")
    }

    private fun startEditor(target: Connection) {
        service.onCreateInputMethodInterface().startInput(target,
            EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; initialSelStart = 0; initialSelEnd = 0 })
    }

    private fun queue(id: Long, text: String) = onMainChecked {
        OfflineKeyboardService::class.java.getDeclaredMethod(
            "queueVoiceSegment", Long::class.javaPrimitiveType, String::class.java
        ).apply { isAccessible = true }.invoke(service, id, text)
    }

    private fun gateValidationWorker(): CountDownLatch {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        releases.add(release)
        field<ExecutorService>("voiceEditorWorker").execute {
            started.countDown(); release.await(15, TimeUnit.SECONDS)
        }
        assertTrue(started.await(15, TimeUnit.SECONDS))
        return release
    }

    private fun drainValidationWorker() {
        val done = CountDownLatch(1)
        field<ExecutorService>("voiceEditorWorker").execute { done.countDown() }
        assertTrue(done.await(15, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }

    private fun waitFor(condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < end) {
            var result = false
            onMainChecked { result = condition() }
            if (result) return
            SystemClock.sleep(25)
        }
        fail("Production voice state did not settle")
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T = OfflineKeyboardService::class.java
        .getDeclaredField(name).apply { isAccessible = true }.get(service) as T
    private fun setField(name: String, value: Any) = OfflineKeyboardService::class.java
        .getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    private fun invoke(name: String): Any? = OfflineKeyboardService::class.java
        .getDeclaredMethod(name).apply { isAccessible = true }.invoke(service)
    /** Keep diagnostic assertion failures on JUnit's thread instead of crashing Android main. */
    private fun onMainChecked(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try { block() } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }

}
