package io.secure.keyboard

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.test.InstrumentationTestCase
import android.test.ServiceTestCase
import android.text.InputType
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewTreeObserver
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
import io.secure.keyboard.data.SettingsStore
import io.secure.keyboard.ui.KeyContainer
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Actual lifecycle callbacks and host InputConnection; every service store is fixture-local. */
@Suppress("DEPRECATION")
class KeyboardLanguageLifecycleTest : InstrumentationTestCase() {
    private lateinit var activity: Activity
    private lateinit var application: IsolatedApplication
    private lateinit var sandbox: IsolatedContext
    private lateinit var service: OfflineKeyboardService
    private var harness: Harness? = null
    private lateinit var editor: EditText
    private lateinit var keyboardSlot: FrameLayout
    private lateinit var connection: InputConnection
    private lateinit var editorInfo: EditorInfo
    private val context get() = instrumentation.targetContext
    private val config get() = SettingsStore(application)
    private val prefs get() = application.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val root get() = field<ViewGroup>("root")

    private class Harness : ServiceTestCase<OfflineKeyboardService>(OfflineKeyboardService::class.java) {
        fun create(application: Application): OfflineKeyboardService {
            setContext(application)
            setApplication(application)
            setUp()
            startService(Intent(application, OfflineKeyboardService::class.java))
            return service
        }

        fun close() = tearDown()
    }

    override fun setUp() {
        super.setUp()
        sandbox = IsolatedContext(context)
        application = IsolatedApplication(sandbox)
        config.vibration = false
        config.englishCompletion = false
        config.fourCodeAutoCommit = false
        activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            ),
        )
        instrumentation.runOnMainSync {
            editor = EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                showSoftInputOnFocus = false
            }
            keyboardSlot = FrameLayout(activity)
            activity.setContentView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(editor, LinearLayout.LayoutParams(-1, 100))
                addView(keyboardSlot, LinearLayout.LayoutParams(-1, 0, 1f))
            })
            createService()
            startEditor()
        }
        instrumentation.waitForIdleSync()
    }

    override fun tearDown() {
        try {
            instrumentation.runOnMainSync {
                harness?.close()
                harness = null
                if (::activity.isInitialized) activity.finish()
            }
        } finally {
            if (::sandbox.isInitialized) {
                sandbox.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
                sandbox.root.deleteRecursively()
            }
            super.tearDown()
        }
    }

    fun testAbsentPreferenceDefaultsChineseAndBothUserChoicesSurviveWarmReopen() {
        instrumentation.runOnMainSync {
            assertFalse("Opening a default keyboard must not invent a saved selection",
                prefs.contains("lastChineseLanguage"))
            assertTrue(SettingsStore.DEFAULT_CHINESE_LANGUAGE)
            assertLetterMode(true)
            assertHostTyping(true)
            for (chinese in listOf(false, true)) {
                selectLanguage(chinese)
                assertEquals(chinese, SettingsStore(application).lastChineseLanguage)
                assertTrue(prefs.contains("lastChineseLanguage"))
                hideKeyboard()
                reopenKeyboard()
                assertLetterMode(chinese)
                assertHostTyping(chinese)
            }
        }
    }

    fun testActiveRestartKeepsSymbolPageButHideReopenAndNewEditorRestoreLetters() {
        instrumentation.runOnMainSync {
            for (chinese in listOf(true, false)) {
                selectLanguage(chinese)
                // Clipboard capture can update its deduplication timestamp asynchronously.
                val beforeSymbols = prefs.all.filterKeys { it != "lastClipboardToken" }
                enterSecondSymbolPage()
                assertEquals("Symbol mode/page is transient, not a configuration choice",
                    beforeSymbols, prefs.all.filterKeys { it != "lastClipboardToken" })

                service.onCreateInputMethodInterface().restartInput(connection, editorInfo)
                service.onStartInputView(editorInfo, true)
                attachKeyboard()
                assertSymbolPage(chinese, secondary = true)
                editor.setText("")
                key("~").performClick()
                assertEquals("Active editor restart must retain literal symbol typing", "~", editor.text.toString())

                key("ABC").performClick()
                key("?123").performClick()
                assertSymbolPage(chinese, secondary = false)
                key("=\\<").performClick()
                assertSymbolPage(chinese, secondary = true)
                hideKeyboard()
                reopenKeyboard()
                assertLetterMode(chinese)
                assertHostTyping(chinese)
                key("?123").performClick()
                assertSymbolPage(chinese, secondary = false)
                key("=\\<").performClick()
                startEditor()
                assertLetterMode(chinese)
                assertHostTyping(chinese)
                assertEquals(chinese, SettingsStore(application).lastChineseLanguage)
            }
        }
    }

    fun testColdServiceRecreationAndContentClearRetainEachSavedLanguage() {
        instrumentation.runOnMainSync {
            for (chinese in listOf(false, true)) {
                selectLanguage(chinese)
                enterSecondSymbolPage()
                recreateService()
                assertEquals("onCreate must read the saved selection before an editor starts",
                    chinese, field<Boolean>("chinese"))
                startEditor()
                assertLetterMode(chinese)
                assertHostTyping(chinese)

                // This Application redirects both content files and preferences. Never clear
                // ContentStore(context), which would erase the real application's content.
                val content = ContentStore(application)
                content.addPhrase("language lifecycle fixture")
                content.addClipboard("fixture-only clipboard history")
                content.clearAll()
                assertTrue(content.phrases().isEmpty())
                assertTrue(content.clipboard().isEmpty())
                assertEquals(chinese, SettingsStore(application).lastChineseLanguage)
                recreateService()
                startEditor()
                assertLetterMode(chinese)
                assertHostTyping(chinese)
            }
        }
    }

    fun testPasswordAndForceAsciiAreTemporaryAndDoNotOverwriteSavedChinese() {
        instrumentation.runOnMainSync {
            // Make the Chinese choice explicit so this checks persistence as well as defaults.
            selectLanguage(false)
            selectLanguage(true)
            for (password in listOf(true, false)) {
                startEditor(
                    inputType = InputType.TYPE_CLASS_TEXT or (if (password)
                        InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_MULTI_LINE),
                    imeOptions = if (password) 0 else EditorInfo.IME_FLAG_FORCE_ASCII,
                )
                assertLetterMode(false)
                assertHostTyping(false)
                assertTrue(SettingsStore(application).lastChineseLanguage)
                hideKeyboard()
                reopenKeyboard()
                assertLetterMode(false)
                assertTrue(SettingsStore(application).lastChineseLanguage)
                startEditor()
                assertLetterMode(true)
                assertHostTyping(true)
                assertTrue(SettingsStore(application).lastChineseLanguage)
            }
        }
    }

    fun testFieldDefaultsAreCorrectBeforeAttachAndOnFirstPreDraw() {
        val text = InputType.TYPE_CLASS_TEXT
        val number = InputType.TYPE_CLASS_NUMBER
        val types = listOf(
            text to EditorEntryPolicy.Entry(true, false, false),
            (text or InputType.TYPE_TEXT_VARIATION_PASSWORD) to EditorEntryPolicy.Entry(false, false, true),
            (text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) to EditorEntryPolicy.Entry(false, false, true),
            (text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) to EditorEntryPolicy.Entry(false, false, true),
            (text or InputType.TYPE_TEXT_VARIATION_URI) to EditorEntryPolicy.Entry(false, false, false),
            (text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS) to EditorEntryPolicy.Entry(false, false, false),
            (text or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) to EditorEntryPolicy.Entry(false, false, false),
            number to EditorEntryPolicy.Entry(true, true, false),
            (number or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL) to
                EditorEntryPolicy.Entry(true, true, false),
            (number or InputType.TYPE_NUMBER_VARIATION_PASSWORD) to EditorEntryPolicy.Entry(false, true, true),
        )
        for ((type, expected) in types) {
            val drawn = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            instrumentation.runOnMainSync {
                config.lastChineseLanguage = true
                startEditor(type, beforeAttach = {
                    assertEntryState(expected)
                    assertEquals("The first built tree already contains the correct keys",
                        expected.symbols, keys().containsKey("ABC"))
                    assertEquals(!expected.symbols, keys().containsKey("q"))
                })
                val openingRoot = root
                openingRoot.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        openingRoot.viewTreeObserver.removeOnPreDrawListener(this)
                        try {
                            assertEntryState(expected)
                            if (expected.symbols) assertSymbolPage(expected.chinese, false)
                            else assertLetterMode(expected.chinese)
                            assertTrue("Automatic entry never changes remembered Chinese",
                                SettingsStore(application).lastChineseLanguage)
                        } catch (problem: Throwable) { failure.set(problem) }
                        drawn.countDown()
                        return true
                    }
                })
                openingRoot.requestLayout()
                openingRoot.invalidate()
            }
            assertTrue("Expected an actual first pre-draw for input type $type", drawn.await(5, TimeUnit.SECONDS))
            failure.get()?.let { throw it }
        }
    }

    fun testNumericPasswordFirstPageAndManualModesSurviveRestartCursorAndReopen() {
        instrumentation.runOnMainSync {
            config.lastChineseLanguage = true
            startEditor(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
            assertSymbolPage(false, false)
            key("1").performClick()
            assertEquals("1", editor.text.toString())
            key("=\\<").performClick()
            assertSymbolPage(false, true)
            restartEditor()
            assertSymbolPage(false, true)
            service.onUpdateSelection(1, 1, 0, 0, -1, -1)
            assertSymbolPage(false, true)
            key("ABC").performClick()
            assertLetterMode(false)
            restartEditor()
            assertLetterMode(false)
            // A numeric password starts on page 1 again after a genuinely new opening.
            hideKeyboard()
            reopenKeyboard()
            assertSymbolPage(false, false)
            assertTrue(SettingsStore(application).lastChineseLanguage)
            startEditor()
            assertLetterMode(true)
        }
    }

    fun testSpecialFieldExplicitLanguageChoicePersistsAndIsNeverLocked() {
        instrumentation.runOnMainSync {
            for (type in listOf(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_CLASS_NUMBER,
            )) {
                config.lastChineseLanguage = false
                startEditor(type)
                // Numeric fields open on the symbol page, whose bottom row contains punctuation.
                if (field<Boolean>("symbols")) key("ABC").performClick()
                key("切换中文和英文").performClick()
                assertLetterMode(true)
                assertTrue("An explicit choice in a special field has normal preference semantics",
                    SettingsStore(application).lastChineseLanguage)
                restartEditor()
                assertLetterMode(true)
                service.onUpdateSelection(0, 0, 1, 1, -1, -1)
                assertLetterMode(true)
                if (field<Boolean>("secret")) {
                    key("q").performClick()
                    assertEquals("Secret Chinese selection must still type literally", "q", editor.text.toString())
                    assertEquals("", field<String>("code"))
                }
                hideKeyboard()
                reopenKeyboard()
                if (type and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_NUMBER)
                    assertSymbolPage(true, false)
                else assertLetterMode(false)
                assertTrue(SettingsStore(application).lastChineseLanguage)
                startEditor()
                assertLetterMode(true)
                selectLanguage(false)
                startEditor(type)
                hideKeyboard()
                reopenKeyboard()
                assertFalse(SettingsStore(application).lastChineseLanguage)
                startEditor()
                assertLetterMode(false)
            }
        }
    }

    fun testForceAsciiRetainsInitialDefaultAndAllowsManualChoiceUntilNextOpening() {
        instrumentation.runOnMainSync {
            config.lastChineseLanguage = true
            startEditor(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_FORCE_ASCII)
            assertLetterMode(false)
            assertFalse(field<Boolean>("secret"))
            key("切换中文和英文").performClick()
            assertLetterMode(true)
            restartEditor()
            assertLetterMode(true)
            service.onUpdateSelection(0, 0, 1, 1, -1, -1)
            assertLetterMode(true)
            hideKeyboard()
            reopenKeyboard()
            assertLetterMode(false)
            assertTrue(SettingsStore(application).lastChineseLanguage)
            startEditor(InputType.TYPE_CLASS_NUMBER, EditorInfo.IME_FLAG_FORCE_ASCII)
            assertSymbolPage(false, false)
            assertFalse(field<Boolean>("secret"))
            startEditor()
            assertLetterMode(true)
        }
    }

    fun testNullPhoneDateAndUnrelatedTextVariationsUseOrdinaryFallback() {
        instrumentation.runOnMainSync {
            for (remembered in listOf(true, false)) {
                config.lastChineseLanguage = remembered
                for (type in listOf(InputType.TYPE_NULL, InputType.TYPE_CLASS_PHONE,
                    InputType.TYPE_CLASS_DATETIME, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT,
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT)) {
                    startEditor(type)
                    assertLetterMode(remembered)
                    assertFalse(field<Boolean>("secret"))
                    assertEquals(remembered, SettingsStore(application).lastChineseLanguage)
                }
            }
        }
    }

    fun testPasswordSuppressesCandidatesClipboardContentsAndStillAllowsExplicitPaste() {
        val fixture = "paste-only-fixture"
        val content = ContentStore(application)
        onMainChecked {
            content.addClipboard("PRIVATE_SAVED_CLIPBOARD_HINT")
            config.englishCompletion = true
            startEditor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                guardPrivateReads = true)
        }
        // ClipboardService on Android 10 rejects an unfocused non-default-IME UID. This local
        // harness must obtain actual host-window focus before testing a real system paste.
        val focusDeadline = SystemClock.uptimeMillis() + 20_000
        var focused = false
        while (!focused && SystemClock.uptimeMillis() < focusDeadline) {
            onMainChecked {
                focused = editor.isAttachedToWindow && editor.isFocused && editor.hasWindowFocus() &&
                    activity.window.decorView.hasWindowFocus()
            }
            if (!focused) SystemClock.sleep(25)
        }
        assertTrue("Real host window must be focused before accessing the system clipboard", focused)
        onMainChecked {
            // Even loaded dictionaries and old candidates must not reach this editor or header.
            setField("loaded", true)
            setField("candidateTexts", listOf("PRIVATE_OLD_CANDIDATE"))
            invoke("renderHeader")
            assertFalse(field<View>("candidateGroup").isShown)
            key("q").performClick()
            assertEquals("q", editor.text.toString())
            assertTrue(field<List<String>>("candidateTexts").isEmpty())
            assertEquals("", field<String>("code"))
            assertFalse(field<View>("preedit").isShown)
            key("切换中文和英文").performClick()
            key("w").performClick()
            assertEquals("qw", editor.text.toString())
            assertEquals("", field<String>("code"))
            assertTrue(field<List<String>>("candidateTexts").isEmpty())
            tool("剪贴板").performClick()
            assertFalse("Saved clipboard text must not be in the view tree",
                descendants(root).filterIsInstance<TextView>().any {
                    it.text.contains("PRIVATE_SAVED_CLIPBOARD_HINT") || it.text.contains("PRIVATE_OLD_CANDIDATE")
                })
            assertTrue("No history rows in a password panel",
                descendants(root).none { it is io.secure.keyboard.ui.ContentEntryRow })
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val originalClip = clipboard.primaryClip
            val savedClipboard = content.clipboard()
            try {
                clipboard.setPrimaryClip(ClipData.newPlainText("keyboard-password-test", fixture))
                assertEquals("System clipboard must be readable by the focused host before paste",
                    fixture, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
                io.secure.keyboard.SessionTestAccess.method("captureClipboard", Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(service, true)
                textButton("粘贴").performClick()
                assertEquals("Explicit host paste remains usable", "qw$fixture", editor.text.toString())
                assertEquals("Password capture must not store the system paste text", savedClipboard, content.clipboard())
                tool("快捷编辑").performClick()
                textButton("粘贴").performClick()
                assertEquals("Quick-edit host paste remains usable", "qw$fixture$fixture", editor.text.toString())
            } finally {
                if (originalClip != null) clipboard.setPrimaryClip(originalClip)
                else clipboard.clearPrimaryClip()
            }
        }
    }

    fun testPasswordLongPressHidesPreviewButStillInsertsAlternate() {
        var heldKey: KeyContainer? = null
        var down = 0L
        instrumentation.runOnMainSync {
            config.longPressSymbols = true
            config.longPressDelay = 200
            startEditor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            heldKey = key("q")
            down = SystemClock.uptimeMillis()
            touch(requireNotNull(heldKey), MotionEvent.ACTION_DOWN, down)
        }
        SystemClock.sleep(300)
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertNull("Password keys never show text preview windows", field<Any?>("popup"))
            touch(requireNotNull(heldKey), MotionEvent.ACTION_UP, down)
            assertEquals("Long press keeps its original alternate insertion", "1", editor.text.toString())
        }
    }

    /** Keep environment/precondition assertions on the instrumentation thread, not Android main. */
    private fun onMainChecked(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try { block() } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }

    private fun assertEntryState(expected: EditorEntryPolicy.Entry) {
        assertEquals(expected.chinese, field<Boolean>("chinese"))
        assertEquals(expected.symbols, field<Boolean>("symbols"))
        assertFalse(field<Boolean>("symbolPage"))
        assertEquals(expected.secret, field<Boolean>("secret"))
    }

    private fun restartEditor() {
        service.onCreateInputMethodInterface().restartInput(connection, editorInfo)
        service.onStartInputView(editorInfo, true)
        attachKeyboard()
    }

    private fun touch(view: View, action: Int, down: Long) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
            view.width / 2f, view.height / 2f, 0)
        try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    private fun tool(description: String): View = descendants(root).first {
        it.contentDescription?.toString() == description
    }

    private fun textButton(text: String): TextView = descendants(root).filterIsInstance<TextView>().first {
        it.isClickable && it.text.toString() == text
    }

    private fun invoke(name: String) {
        io.secure.keyboard.SessionTestAccess.method(name).apply { isAccessible = true }.invoke(service)
    }

    private fun setField(name: String, value: Any) {
        io.secure.keyboard.SessionTestAccess.field(name).apply { isAccessible = true }.set(service, value)
    }

    private fun createService() {
        harness = Harness()
        service = requireNotNull(harness).create(application)
        assertSame("The service must use isolated application stores", application, service.applicationContext)
        service.onCreateInputView()
    }

    private fun recreateService() {
        service.onFinishInputView(true)
        service.onFinishInput()
        keyboardSlot.removeAllViews()
        harness?.close()
        harness = null
        createService()
    }

    private fun startEditor(
        inputType: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
        imeOptions: Int = 0,
        beforeAttach: (() -> Unit)? = null,
        guardPrivateReads: Boolean = false,
    ) {
        editor.inputType = if (inputType == InputType.TYPE_NULL) InputType.TYPE_CLASS_TEXT else inputType
        editor.imeOptions = imeOptions
        editor.setText("")
        editor.requestFocus()
        editorInfo = EditorInfo()
        val nativeConnection = requireNotNull(editor.onCreateInputConnection(editorInfo))
        // Keep a native, attached editor even when testing TYPE_NULL; its EditorInfo is the
        // authoritative classification input, not any EditText normalization of variations.
        editorInfo.inputType = inputType
        editorInfo.imeOptions = imeOptions
        connection = if (guardPrivateReads) object : InputConnectionWrapper(nativeConnection, false) {
            override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence? =
                throw AssertionError("Password completion must not read preceding text")
            override fun getTextAfterCursor(length: Int, flags: Int): CharSequence? =
                throw AssertionError("Password completion must not read following text")
            override fun getSelectedText(flags: Int): CharSequence? =
                throw AssertionError("Password completion must not read selected text")
        } else nativeConnection
        service.onCreateInputMethodInterface().startInput(connection, editorInfo)
        service.onStartInputView(editorInfo, false)
        beforeAttach?.invoke()
        attachKeyboard()
        assertSame("Fixture must use the real host InputConnection", connection, service.currentInputConnection)
    }

    private fun hideKeyboard() {
        service.onFinishInputView(false)
        keyboardSlot.visibility = View.GONE
        assertFalse(field<Boolean>("active"))
    }

    private fun reopenKeyboard() {
        // A warm reopen need not receive onStartInput; exercise that path explicitly.
        service.onStartInputView(editorInfo, false)
        attachKeyboard()
    }

    private fun attachKeyboard() {
        (root.parent as? ViewGroup)?.removeView(root)
        keyboardSlot.removeAllViews()
        keyboardSlot.addView(root, FrameLayout.LayoutParams(-1, -1))
        keyboardSlot.visibility = View.VISIBLE
        assertTrue("Production keyboard must be attached to the host window", root.isAttachedToWindow)
    }

    private fun selectLanguage(chinese: Boolean) {
        if (field<Boolean>("chinese") != chinese) key("切换中文和英文").performClick()
        assertLetterMode(chinese)
    }

    private fun enterSecondSymbolPage() {
        key("?123").performClick()
        assertSymbolPage(field("chinese"), secondary = false)
        key("=\\<").performClick()
        assertSymbolPage(field("chinese"), secondary = true)
    }

    private fun assertLetterMode(chinese: Boolean) {
        assertEquals(chinese, field<Boolean>("chinese"))
        assertFalse(field<Boolean>("symbols"))
        assertTrue(key("q").isShown)
        assertTrue(key("?123").isShown)
        val language = if (chinese) "五笔" else "English"
        assertTrue("Language label must be visible", key(language).isShown)
        assertTrue("Assert the rendered label, not just its accessibility description",
            descendants(key(language)).filterIsInstance<TextView>().any { it.text.toString() == language && it.isShown })
        assertTrue(key(if (chinese) "，。" else ". ,").isShown)
    }

    private fun assertSymbolPage(chinese: Boolean, secondary: Boolean) {
        assertEquals(chinese, field<Boolean>("chinese"))
        assertTrue(field<Boolean>("symbols"))
        assertEquals(secondary, field<Boolean>("symbolPage"))
        assertTrue(key("ABC").isShown)
        assertTrue(key(if (secondary) "~" else "1").isShown)
        assertFalse(keys().containsKey(if (secondary) "1" else "~"))
        assertTrue(key(if (secondary) { if (chinese) "「" else "{" }
            else { if (chinese) "（" else "(" }).isShown)
    }

    private fun assertHostTyping(chinese: Boolean) {
        editor.setText("")
        key("q").performClick()
        assertEquals(if (chinese) "" else "q", editor.text.toString())
        assertEquals(if (chinese) "q" else "", field<String>("code"))
        if (chinese) {
            key("回车").performClick()
            assertEquals("Chinese letter input must stay composition until raw-code Enter", "q", editor.text.toString())
        }
        assertEquals("", field<String>("code"))
        editor.setText("")
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
        else emptyList()

    private fun keys(): Map<String, KeyContainer> = descendants(root)
        .filterIsInstance<KeyContainer>().associateBy { it.contentDescription.toString() }

    private fun key(description: String): KeyContainer =
        requireNotNull(keys()[description]) { "Missing visible keyboard key: $description" }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T = io.secure.keyboard.SessionTestAccess.field(name).apply { isAccessible = true }.get(service) as T

    private class IsolatedApplication(context: Context) : Application() {
        init { attachBaseContext(context) }
        override fun getApplicationContext(): Context = this
    }

    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        private val namespace = "language-lifecycle-test-${UUID.randomUUID()}"
        val root = File(base.noBackupFilesDir, namespace)
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getExternalCacheDirs(): Array<File> = emptyArray()
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("$namespace-$name", mode)
    }
}
