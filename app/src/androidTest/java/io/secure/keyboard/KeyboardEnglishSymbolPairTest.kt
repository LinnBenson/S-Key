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
            val open = OfflineKeyboardService::class.java.getDeclaredMethod("openTool", String::class.java).apply { isAccessible = true }
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

    private data class HostCase(val text: String, val start: Int, val end: Int)
    private data class LiteralPage(val chinese: Boolean, val secondary: Boolean, val symbols: String)

    private fun selectLanguage(chinese: Boolean) {
        if (field<Boolean>("symbols")) key("ABC").performClick()
        if (field<Boolean>("chinese") != chinese) key("切换中文和英文").performClick()
        assertEquals(chinese, field<Boolean>("chinese"))
        assertFalse(field<Boolean>("symbols"))
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
        assertTrue(key(if (secondary) "~" else "1").isShown)
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
    private fun <T> field(name: String): T = OfflineKeyboardService::class.java
        .getDeclaredField(name).apply { isAccessible = true }.get(service) as T

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
