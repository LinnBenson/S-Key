package io.secure.keyboard

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
import android.view.inputmethod.InputConnection
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.secure.keyboard.data.SettingsStore
import io.secure.keyboard.ui.KeyContainer
import java.io.File
import java.util.UUID

/** Production symbol-row key taps against a real host; service stores are fixture-local. */
@Suppress("DEPRECATION")
class KeyboardEnglishSymbolPairTest : InstrumentationTestCase() {
    private lateinit var activity: Activity
    private lateinit var application: IsolatedApplication
    private lateinit var sandbox: IsolatedContext
    private lateinit var service: OfflineKeyboardService
    private var harness: Harness? = null
    private lateinit var editor: EditText
    private lateinit var keyboardSlot: FrameLayout
    private lateinit var connection: InputConnection
    private val context get() = instrumentation.targetContext
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
        SettingsStore(application).apply {
            vibration = false
            englishCompletion = false
            fourCodeAutoCommit = false
            longPressSymbols = false
        }
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
            harness = Harness()
            service = requireNotNull(harness).create(application)
            assertSame("The service must use isolated stores", application, service.applicationContext)
            service.onCreateInputView()
            editor.requestFocus()
            val info = EditorInfo()
            connection = requireNotNull(editor.onCreateInputConnection(info))
            service.onCreateInputMethodInterface().startInput(connection, info)
            service.onStartInputView(info, false)
            (root.parent as? ViewGroup)?.removeView(root)
            keyboardSlot.addView(root, FrameLayout.LayoutParams(-1, -1))
            assertTrue("Production keyboard must be attached to the host window", root.isAttachedToWindow)
            assertSame("Use the real host InputConnection", connection, service.currentInputConnection)
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

    fun testFourEnglishSymbolPairsReplaceSelectionAndReturnToLetters() {
        instrumentation.runOnMainSync {
            selectLanguage(chinese = false)
            val pairs = listOf('"' to '"', '\'' to '\'', '{' to '}', '[' to ']')
            // Unicode outside the insertion/selection must remain byte-for-byte unchanged.
            val prefix = "前🙂"
            val suffix = "后"
            val hosts = listOf(
                HostCase("", 0, 0),
                HostCase(prefix + suffix, prefix.length, prefix.length),
                HostCase(prefix + "remove" + suffix, prefix.length, prefix.length + "remove".length),
                HostCase(prefix + "remove" + suffix, prefix.length + "remove".length, prefix.length),
            )
            for ((opener, closer) in pairs) {
                for (host in hosts) {
                    enterSymbols(secondary = true)
                    editor.setText(host.text)
                    editor.setSelection(host.start, host.end)
                    val before = host.text.substring(0, minOf(host.start, host.end))
                    val after = host.text.substring(maxOf(host.start, host.end))
                    val case = "opener U+${opener.code.toString(16)}, selection ${host.start}..${host.end}"

                    key(opener.toString()).performClick()

                    assertEquals("Only the selected span is replaced by the requested pair",
                        "$before$opener$closer$after", editor.text.toString())
                    assertCursor(before.length + 1, case)
                    assertEnglishLetters()
                    key("a").performClick()
                    assertEquals("The next letter must be inserted inside the pair",
                        "${before}${opener}a$closer$after", editor.text.toString())
                    assertCursor(before.length + 2, "after next letter: $case")
                    assertEnglishLetters()
                }
            }
        }
    }

    fun testAssistantPairsPlaceCaretInsideAndKeepHostUntouched() {
        instrumentation.runOnMainSync {
            selectLanguage(chinese = false)
            val open = io.secure.keyboard.SessionTestAccess.method("openTool", String::class.java).apply { isAccessible = true }
            for (mode in listOf("translate", "ai")) {
                open.invoke(service, mode)
                for (opener in listOf("\"", "'", "[", "{")) {
                    val closer = when (opener) { "[" -> "]"; "{" -> "}"; else -> opener }
                    enterSymbols(secondary = true)
                    val source = field<EditText>("translationTextView")
                    source.setText("前🙂remove后")
                    source.setSelection(3, 9)
                    val hostBefore = editor.text.toString()
                    key(opener).performClick()
                    val updated = field<EditText>("translationTextView")
                    assertEquals("前🙂$opener${closer}后", updated.text.toString())
                    assertEquals(4, updated.selectionStart)
                    assertEnglishLetters()
                    key("a").performClick()
                    assertEquals("前🙂${opener}a${closer}后", field<EditText>("translationTextView").text.toString())
                    assertEquals(hostBefore, editor.text.toString())
                }
                enterSymbols(secondary = true)
                val source = field<EditText>("translationTextView")
                source.setText("x".repeat(4999)); source.setSelection(4999)
                key("'").performClick()
                assertEquals(4999, field<EditText>("translationTextView").length())
                assertTrue(field<Boolean>("symbols"))
                key("ABC").performClick()
                open.invoke(service, mode)
            }
        }
    }

    fun testChineseSymbolsEnglishParenthesesAndRightClosersStayLiteralOnSymbolPages() {
        instrumentation.runOnMainSync {
            val cases = listOf(
                LiteralPage(chinese = true, secondary = false, symbols = "（）"),
                LiteralPage(chinese = true, secondary = true, symbols = "“”「」‘’［］"),
                LiteralPage(chinese = false, secondary = false, symbols = "()"),
                LiteralPage(chinese = false, secondary = true, symbols = "}]"),
            )
            for (page in cases) {
                selectLanguage(page.chinese)
                enterSymbols(page.secondary)
                for (symbol in page.symbols) {
                    editor.setText("leftREMOVEright")
                    editor.setSelection(4, 10)
                    key(symbol.toString()).performClick()
                    assertEquals("Non-trigger symbols must insert exactly one literal character",
                        "left${symbol}right", editor.text.toString())
                    assertCursor(5)
                    assertSymbolPage(page.chinese, page.secondary)
                }
            }
        }
    }

    fun testEditableRulesApplyImmediatelyAndCountUnicodeCharacters() {
        instrumentation.runOnMainSync {
            val store=io.secure.keyboard.data.SymbolClosureStore(application)
            selectLanguage(false)
            for ((closing,back,expectedCursor) in listOf(Triple("}",0,2),Triple("🙂]",1,3),Triple("🙂]",2,1))) {
                store.save("{",io.secure.keyboard.data.SymbolClosureStore.Rule("{",closing,back))
                enterSymbols(true);editor.setText("");editor.setSelection(0)
                key("{").performClick()
                assertEquals("{"+closing,editor.text.toString());assertCursor(expectedCursor)
                assertEnglishLetters()
            }
            store.delete("{")
            enterSymbols(true);editor.setText("");editor.setSelection(0)
            key("{").performClick()
            assertEquals("{",editor.text.toString());assertCursor(1);assertSymbolPage(false,true)
            store.save(null,io.secure.keyboard.data.SymbolClosureStore.Rule("（","）",1))
            selectLanguage(true);enterSymbols(false);editor.setText("");editor.setSelection(0)
            key("（").performClick()
            assertEquals("（）",editor.text.toString());assertCursor(1)
            assertFalse(field<Boolean>("symbols"));assertTrue(field<Boolean>("chinese"))
        }
    }

    fun testAssistantCustomOffsetKeepsHostUntouched() {
        instrumentation.runOnMainSync {
            val store=io.secure.keyboard.data.SymbolClosureStore(application)
            selectLanguage(false)
            io.secure.keyboard.SessionTestAccess.method("openTool",String::class.java).apply { isAccessible=true }.invoke(service,"ai")
            for((back,cursor) in listOf(0 to 4,1 to 2,2 to 1)) {
                store.save("[",io.secure.keyboard.data.SymbolClosureStore.Rule("[","]🙂",back))
                enterSymbols(true)
                val source=field<EditText>("translationTextView")
                source.setText("替换");source.setSelection(0,2)
                val host=editor.text.toString()
                key("[").performClick()
                assertEquals("[]🙂",field<EditText>("translationTextView").text.toString())
                assertEquals(cursor,field<EditText>("translationTextView").selectionStart)
                assertEquals(host,editor.text.toString())
            }
        }
    }

    fun testRulesPersistValidateAndAllowDeletingAllDefaults() {
        val store=io.secure.keyboard.data.SymbolClosureStore(application)
        assertEquals(4,store.rules().size)
        for(rule in store.rules()) store.delete(rule.opening)
        assertTrue(io.secure.keyboard.data.SymbolClosureStore(application).rules().isEmpty())
        store.save(null,io.secure.keyboard.data.SymbolClosureStore.Rule("(",")",1))
        store.save("(",io.secure.keyboard.data.SymbolClosureStore.Rule("<",">",0))
        assertEquals("<",io.secure.keyboard.data.SymbolClosureStore(application).rules().single().opening)
        for(rule in listOf(io.secure.keyboard.data.SymbolClosureStore.Rule("<",">",1),
            io.secure.keyboard.data.SymbolClosureStore.Rule("(",")",3),io.secure.keyboard.data.SymbolClosureStore.Rule("ab",">",1))) {
            try { store.save(null,rule);fail("Invalid or duplicate rule accepted") } catch(_:IllegalArgumentException) {}
        }
        assertEquals(1,store.rules().size)
    }

    fun testVisualSymbolPagesAreIndependentAndUseSavedUnicodeKeys() {
        val store=io.secure.keyboard.data.SymbolKeyboardStore(application)
        val pages=listOf(Triple(true,false,"①"),Triple(true,true,"🙂"),Triple(false,false,"③"),Triple(false,true,"④"))
        pages.forEach { (chinese,second,symbol) -> store.save(chinese,second,0,0,symbol) }
        instrumentation.runOnMainSync {
            for((chinese,second,symbol) in pages) {
                assertEquals(symbol,io.secure.keyboard.data.SymbolKeyboardStore(application).rows(chinese,second)[0][0])
                selectLanguage(chinese);enterSymbols(second)
                editor.setText("");editor.setSelection(0)
                key(symbol).performClick()
                assertEquals(symbol,editor.text.toString());assertCursor(symbol.length)
                assertTrue(field<Boolean>("symbols"))
                assertTrue(key("ABC").isShown);assertTrue(key("删除").isShown)
            }
        }
        store.restore(true,true)
        assertEquals("~",store.rows(true,true)[0][0])
        assertEquals("①",store.rows(true,false)[0][0])
        assertEquals("③",store.rows(false,false)[0][0])
        assertEquals("④",store.rows(false,true)[0][0])
        for(invalid in listOf("","ab","\n")) {
            try { store.save(true,false,0,0,invalid);fail("Invalid symbol accepted") } catch(_:IllegalArgumentException) {}
        }
    }

    fun testVisibleSymbolKeyRefreshAndClosureUseEditedSymbol() {
        val store=io.secure.keyboard.data.SymbolKeyboardStore(application)
        instrumentation.runOnMainSync { selectLanguage(false);enterSymbols(false) }
        store.save(false,false,1,0,"≤")
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            editor.setText("");editor.setSelection(0);key("≤").performClick()
            assertEquals("≤",editor.text.toString())
        }
        store.save(false,false,1,0,"{")
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            editor.setText("");editor.setSelection(0);key("{").performClick()
            assertEquals("{}",editor.text.toString());assertCursor(1);assertEnglishLetters()
        }
    }

    private data class HostCase(val text: String, val start: Int, val end: Int)
    private data class LiteralPage(val chinese: Boolean, val secondary: Boolean, val symbols: String)

    private fun selectLanguage(chinese: Boolean) {
        if (field<Boolean>("symbols")) key("ABC").performClick()
        if (field<Boolean>("chinese") != chinese) key("切换中文和英文").performClick()
        assertEquals(chinese, field<Boolean>("chinese"))
        assertFalse(field<Boolean>("symbols"))
    }

    fun testQuickPasteToolbarPasteDismissAndFinish() {
        instrumentation.runOnMainSync {
            val session = service.session
            val state = session.quickPasteState
            assertTrue(SettingsStore(application).quickPaste)
            state.open(System.currentTimeMillis(), "完整复制内容\n第二行", 0) {}
            session.renderHeader()
            val preview = descendants(root).first { it.contentDescription?.startsWith("快捷粘贴：") == true }
            assertTrue(preview.isShown)
            preview.performClick()
            assertEquals("完整复制内容\n第二行", editor.text.toString())
            assertNull(state.text)
            preview.performClick()
            assertEquals("完整复制内容\n第二行", editor.text.toString())
            state.open(System.currentTimeMillis(), "dismiss", 101) {}
            session.renderHeader()
            descendants(root).first { it.contentDescription == "关闭快捷粘贴" }.performClick()
            assertNull(state.text)
            assertFalse(descendants(root).any { it.contentDescription == "关闭快捷粘贴" })
            state.open(System.currentTimeMillis(), "unused", 102) {}
            session.renderHeader()
            service.onFinishInputView(false)
            assertNull(state.text)
            assertFalse(descendants(root).any { it.contentDescription?.startsWith("快捷粘贴：") == true })
        }
    }

    fun testQuickPasteExpiresWhileKeyboardStaysOpen() {
        instrumentation.runOnMainSync {
            val session = service.session
            session.quickPasteState.open(System.currentTimeMillis() - 299_500L, "expired preview", 0) {}
            session.renderHeader()
            assertTrue(descendants(root).any { it.contentDescription == "关闭快捷粘贴" })
            session.main.postDelayed(session.quickPasteExpiry, session.quickPasteState.remainingMillis)
        }
        Thread.sleep(800)
        instrumentation.runOnMainSync {
            assertNull(service.session.quickPasteState.text)
            assertFalse(descendants(root).any { it.contentDescription == "关闭快捷粘贴" })
            assertEquals("", editor.text.toString())
        }
    }

    fun testExpiredQueuedQuickPasteClickDoesNotInsert() {
        instrumentation.runOnMainSync {
            val session = service.session
            session.quickPasteState.open(System.currentTimeMillis() - 299_500L, "expired click", 0) {}
            session.renderHeader()
            val preview = descendants(root).first { it.contentDescription?.startsWith("快捷粘贴：") == true }
            // Simulate a delayed UI queue: click validation must not depend on the timer running first.
            Thread.sleep(600)
            preview.performClick()
            assertNull(session.quickPasteState.text)
            assertEquals("", editor.text.toString())
        }
    }

    fun testAnyInputDismissesQuickPaste() {
        instrumentation.runOnMainSync {
            val session = service.session
            val actions: List<() -> Unit> = listOf(
                { session.type("a") },
                { session.type("!") },
                { session.space() },
                { session.enter() },
                { session.backspace() },
                { session.commitInputText("paste") },
            )
            actions.forEachIndexed { index, action ->
                session.clearPending()
                session.clearComposition()
                session.quickPasteState.open(System.currentTimeMillis(), "pending", 0) {}
                session.renderHeader()
                assertTrue(descendants(root).any { it.contentDescription == "关闭快捷粘贴" })
                action()
                assertNull(session.quickPasteState.text)
                assertFalse(descendants(root).any { it.contentDescription == "关闭快捷粘贴" })
            }
        }
    }

    fun testTraditionalOutputToggleAndHostInsertion() {
        instrumentation.runOnMainSync {
            val session = service.session
            val settings = SettingsStore(application)
            assertFalse(settings.traditionalOutput)
            session.quickPasteState.clear()
            settings.toolbarPins = settings.toolbarPins + "script"
            session.renderHeader()
            descendants(root).first { it.contentDescription == "简体输出，点击切换繁体" }.performClick()
            assertTrue(SettingsStore(application).traditionalOutput)
            assertTrue(descendants(root).any { it.contentDescription == "繁体输出，点击切换简体" })
            session.commitInputText("头发发展\n汉字😀")
            assertEquals("頭髮發展\n漢字😀", editor.text.toString())
            descendants(root).first { it.contentDescription == "繁体输出，点击切换简体" }.performClick()
            session.commitInputText("简体")
            assertEquals("頭髮發展\n漢字😀简体", editor.text.toString())
        }
    }

    fun testTraditionalClipboardAndAssistantOutput() {
        instrumentation.runOnMainSync {
            val session = service.session
            SettingsStore(application).traditionalOutput = true
            session.quickPasteState.open(System.currentTimeMillis(), "剪贴板汉字", 0) {}
            session.renderHeader()
            descendants(root).first { it.contentDescription?.startsWith("快捷粘贴：") == true }.performClick()
            assertEquals("剪貼板漢字", editor.text.toString())
            editor.setText("")
            session.translation.enabled = true
            session.commitInputText("头发")
            assertEquals("头发", session.translation.text)
            assertEquals("", editor.text.toString())
            val token = session.translation.begin()
            session.translation.complete(token, "头发发展")
            session.insertAssistantResult()
            assertEquals("頭髮發展", editor.text.toString())
        }
    }

    fun testClearingClipboardRevokesQuickPastePreview() {
        instrumentation.runOnMainSync {
            service.session.quickPasteState.open(System.currentTimeMillis(), "synthetic private clip", 0) {}
            service.session.renderHeader()
            service.session.content.clearClipboard()
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertNull(service.session.quickPasteState.text)
            assertFalse(descendants(root).any { it.contentDescription?.startsWith("快捷粘贴：") == true })
        }
    }

    private fun enterSymbols(secondary: Boolean) {
        assertFalse("Each symbol entry must start from letters", field<Boolean>("symbols"))
        key("?123").performClick()
        assertSymbolPage(field("chinese"), secondary = false)
        if (secondary) key("=\\<").performClick()
        assertSymbolPage(field("chinese"), secondary)
    }

    private fun assertEnglishLetters() {
        assertFalse("Pair insertion must retain English", field<Boolean>("chinese"))
        assertFalse("Pair insertion must immediately return to letters", field<Boolean>("symbols"))
        assertTrue(key("q").isShown)
        assertTrue(key("a").isShown)
        assertTrue(key("?123").isShown)
        assertFalse(keys().containsKey("ABC"))
        assertTrue("English label must be visibly rendered",
            descendants(key("English")).filterIsInstance<TextView>()
                .any { it.text.toString() == "English" && it.isShown })
    }

    private fun assertSymbolPage(chinese: Boolean, secondary: Boolean) {
        assertEquals(chinese, field<Boolean>("chinese"))
        assertTrue("Literal symbols must remain on a symbol page", field<Boolean>("symbols"))
        assertEquals(secondary, field<Boolean>("symbolPage"))
        assertTrue(key("ABC").isShown)
        assertTrue(key(io.secure.keyboard.data.SymbolKeyboardStore(application).rows(chinese,secondary)[0][0]).isShown)
        assertFalse(keys().containsKey("q"))
    }

    private fun assertCursor(position: Int, description: String = "literal symbol") {
        assertEquals("Host selection start ($description)", position, editor.selectionStart)
        assertEquals("Host selection end ($description)", position, editor.selectionEnd)
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
        private val namespace = "english-symbol-pair-test-${UUID.randomUUID()}"
        val root = File(base.noBackupFilesDir, namespace)
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getExternalCacheDirs(): Array<File> = emptyArray()
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("$namespace-$name", mode)
    }
}
