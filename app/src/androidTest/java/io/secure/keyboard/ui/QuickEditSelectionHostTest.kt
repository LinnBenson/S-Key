package io.secure.keyboard.ui

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.text.InputType
import android.text.Selection
import android.text.method.MetaKeyKeyListener
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import io.secure.keyboard.MainActivity

/** Actual attached EditText, its native InputConnection, and asynchronous ViewRoot key dispatch. */
@Suppress("DEPRECATION")
class QuickEditSelectionHostTest : InstrumentationTestCase() {
    private lateinit var activity: Activity
    private lateinit var editor: EditText
    private lateinit var connection: GuardedConnection
    private val mode = QuickEditSelection()
    private val fixture = "A👩‍🚀e\u0301B"

    private class GuardedConnection(target: InputConnection) : InputConnectionWrapper(target, false) {
        var discardArrowDown = false
        var throwAt: Pair<Int, Int>? = null
        val events = mutableListOf<KeyEvent>()

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            events.add(KeyEvent(event))
            if (throwAt == (event.keyCode to event.action))
                throw IllegalStateException("Synthetic host dispatch failure")
            if (discardArrowDown && event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT &&
                event.action == KeyEvent.ACTION_DOWN) return false
            return super.sendKeyEvent(event)
        }

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? =
            throw AssertionError("Selection must not extract host text")
        override fun getSelectedText(flags: Int): CharSequence? =
            throw AssertionError("Selection must not read selected text")
        override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence? =
            throw AssertionError("Selection must not read text before the cursor")
        override fun getTextAfterCursor(length: Int, flags: Int): CharSequence? =
            throw AssertionError("Selection must not read text after the cursor")
        override fun setSelection(start: Int, end: Int): Boolean =
            throw AssertionError("The host owns the selection anchor")
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
            throw AssertionError("Selection must not rewrite host text")
    }

    override fun setUp() {
        super.setUp()
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        onMain {
            editor = EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                showSoftInputOnFocus = false
                setPadding(12, 12, 12, 12)
                setText(fixture)
            }
            activity.setContentView(editor)
            editor.requestFocus()
            connection = GuardedConnection(requireNotNull(editor.onCreateInputConnection(EditorInfo())))
        }
        await("attached, focused native editor") {
            activity.hasWindowFocus() && editor.isFocused && editor.layout != null
        }
        onMain { editor.setSelection(1) }
    }

    override fun tearDown() {
        if (::activity.isInitialized) onMain { mode.reset(); activity.finish() }
        super.tearDown()
    }

    fun testMetadataOnlyReproducesCollapseThenBalancedShiftSelectsGraphemes() {
        onMain {
            send(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)
        }
        assertSelection(6, 6) // The old implementation only moved; metadata did not select.
        onMain { editor.setSelection(1); mode.toggle(connection) }
        move(KeyEvent.KEYCODE_DPAD_RIGHT, 1, 6)
        move(KeyEvent.KEYCODE_DPAD_RIGHT, 1, 8)
        move(KeyEvent.KEYCODE_DPAD_LEFT, 1, 6)
        move(KeyEvent.KEYCODE_DPAD_LEFT, 1, 1)
        move(KeyEvent.KEYCODE_DPAD_LEFT, 1, 0)
        move(KeyEvent.KEYCODE_DPAD_RIGHT, 1, 1)
        move(KeyEvent.KEYCODE_DPAD_RIGHT, 1, 6)
        onMain { assertFalse(mode.toggle(connection)) }
        move(KeyEvent.KEYCODE_DPAD_RIGHT, 6, 6)
        move(KeyEvent.KEYCODE_DPAD_RIGHT, 8, 8)
        onMain { assertEquals(fixture, editor.text.toString()) }
    }

    fun testVisualLineSelectionPreservesHostAnchorAndReleasesShift() {
        var expectedDown = 0 to 0
        var expectedUp = 0 to 0
        onMain { editor.setText("abcdefghij ".repeat(60) + "\n第二行👩‍🚀文字\nlast line") }
        instrumentation.waitForIdleSync()
        onMain {
            editor.setSelection(3)
            val layout = requireNotNull(editor.layout)
            assertTrue(layout.lineCount > 3)
            Selection.extendDown(editor.text, layout)
            expectedDown = editor.selectionStart to editor.selectionEnd
            assertTrue(expectedDown.second > 3)
            Selection.extendUp(editor.text, layout)
            expectedUp = editor.selectionStart to editor.selectionEnd
            editor.setSelection(3)
            mode.toggle(connection)
        }
        move(KeyEvent.KEYCODE_DPAD_DOWN, expectedDown.first, expectedDown.second)
        move(KeyEvent.KEYCODE_DPAD_UP, expectedUp.first, expectedUp.second)
    }

    fun testUnhandledArrowDoesNotLatchShiftOrUppercaseNextLetter() {
        onMain {
            connection.discardArrowDown = true
            mode.toggle(connection)
            assertFalse(mode.move(connection, KeyEvent.KEYCODE_DPAD_RIGHT))
        }
        assertSelection(1, 1)
        assertShiftReleased()
        onMain { mode.reset(); connection.discardArrowDown = false; send(KeyEvent.KEYCODE_A) }
        await("lowercase a after unhandled selection arrow") { editor.text.toString() == "Aa👩‍🚀e\u0301B" }
        move(KeyEvent.KEYCODE_DPAD_LEFT, 1, 1)
    }

    fun testThrowingArrowDownReleasesShiftAndLaterPlainArrowDoesNotSelect() {
        onMain {
            mode.toggle(connection)
            connection.throwAt = KeyEvent.KEYCODE_DPAD_RIGHT to KeyEvent.ACTION_DOWN
            assertFalse(mode.move(connection, KeyEvent.KEYCODE_DPAD_RIGHT))
            assertFalse(mode.enabled)
        }
        assertSelection(1, 1)
        assertShiftReleased()
        onMain { connection.throwAt = null }
        move(KeyEvent.KEYCODE_DPAD_RIGHT, 6, 6)
        onMain { send(KeyEvent.KEYCODE_A) }
        await("lowercase a after throwing arrow down") { editor.text.toString() == "A👩‍🚀ae\u0301B" }
    }

    fun testThrowingArrowUpStillReleasesShiftAfterNativeSelection() {
        onMain {
            mode.toggle(connection)
            connection.throwAt = KeyEvent.KEYCODE_DPAD_RIGHT to KeyEvent.ACTION_UP
            assertFalse(mode.move(connection, KeyEvent.KEYCODE_DPAD_RIGHT))
            assertFalse(mode.enabled)
        }
        assertSelection(1, 6)
        assertShiftReleased()
        onMain { connection.throwAt = null }
        move(KeyEvent.KEYCODE_DPAD_RIGHT, 6, 6)
        onMain { send(KeyEvent.KEYCODE_A) }
        await("lowercase a after throwing arrow up") { editor.text.toString() == "A👩‍🚀ae\u0301B" }
    }

    private fun move(key: Int, start: Int, end: Int) {
        onMain { mode.move(connection, key) }
        assertSelection(start, end)
        assertShiftReleased()
    }

    private fun assertSelection(start: Int, end: Int) = await("selection $start..$end") {
        editor.selectionStart == start && editor.selectionEnd == end
    }

    private fun assertShiftReleased() {
        instrumentation.waitForIdleSync()
        onMain { assertEquals("No pressed, latched or locked Shift after the transaction", 0,
            MetaKeyKeyListener.getMetaState(editor.text, MetaKeyKeyListener.META_SHIFT_ON)) }
    }

    private fun send(key: Int, meta: Int = 0) {
        val time = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) connection.sendKeyEvent(
            KeyEvent(time, SystemClock.uptimeMillis(), action, key, 0, meta,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE, InputDevice.SOURCE_KEYBOARD))
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        do {
            instrumentation.waitForIdleSync()
            var success = false
            onMain { success = condition() }
            if (success) return
            SystemClock.sleep(20)
        } while (SystemClock.uptimeMillis() < deadline)
        var actual = ""
        onMain { actual = "${editor.selectionStart}..${editor.selectionEnd}, shift=" +
            MetaKeyKeyListener.getMetaState(editor.text, MetaKeyKeyListener.META_SHIFT_ON) }
        fail("Timed out waiting for $description; actual $actual")
    }

    private fun onMain(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
}
