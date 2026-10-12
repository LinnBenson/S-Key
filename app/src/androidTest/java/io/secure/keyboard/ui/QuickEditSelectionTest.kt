package io.secure.keyboard.ui

import android.test.InstrumentationTestCase
import android.view.KeyEvent
import android.view.KeyCharacterMap
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest

/** Event/privacy contract; native EditText movement is also covered by QuickEditSelectionHostTest. */
@Suppress("DEPRECATION")
class QuickEditSelectionTest : InstrumentationTestCase() {
    private lateinit var first: RecordingConnection
    private lateinit var second: RecordingConnection
    private lateinit var mode: QuickEditSelection

    private class RecordingConnection(view: View) : BaseInputConnection(view, true) {
        val events = mutableListOf<KeyEvent>()
        var throwAt: Pair<Int, Int>? = null
        var accepted = true
        val clearedMeta = mutableListOf<Int>()

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            events.add(event)
            if (throwAt == (event.keyCode to event.action))
                throw IllegalStateException("Synthetic detached editor")
            return accepted
        }

        override fun clearMetaKeyStates(states: Int): Boolean {
            clearedMeta.add(states)
            return true
        }

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? =
            throw AssertionError("Selection mode must not pull host text")
        override fun getSelectedText(flags: Int): CharSequence? =
            throw AssertionError("Selection mode must not read host selections")
        override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence? =
            throw AssertionError("Selection mode must not read text before cursor")
        override fun getTextAfterCursor(length: Int, flags: Int): CharSequence? =
            throw AssertionError("Selection mode must not read text after cursor")
        override fun setSelection(start: Int, end: Int): Boolean =
            throw AssertionError("Native host, not the IME, owns the selection anchor")
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
            throw AssertionError("Selection movement must not rewrite editor text")
    }

    override fun setUp() {
        super.setUp()
        instrumentation.runOnMainSync {
            first = RecordingConnection(View(instrumentation.targetContext))
            second = RecordingConnection(View(instrumentation.targetContext))
            mode = QuickEditSelection()
        }
    }

    fun testToggleDoesNotTouchHostAndEveryDirectionHasBalancedShiftTransaction() {
        assertTrue(mode.toggle(first))
        assertTrue(first.events.isEmpty())
        val directions = listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
        directions.forEach { key ->
            assertTrue(mode.move(first, key))
            val transaction = first.events.takeLast(4)
            assertEquals(listOf(KeyEvent.KEYCODE_SHIFT_LEFT, key, key, KeyEvent.KEYCODE_SHIFT_LEFT),
                transaction.map { it.keyCode })
            assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_DOWN,
                KeyEvent.ACTION_UP, KeyEvent.ACTION_UP), transaction.map { it.action })
            val pair = transaction.subList(1, 3)
            assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), pair.map { it.action })
            assertTrue(pair.all { it.keyCode == key && it.isShiftPressed })
            assertEquals(pair.first().downTime, pair.last().downTime)
            assertEquals(0, transaction.last().metaState)
            assertTrue(transaction.all {
                it.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD &&
                    it.flags and KeyEvent.FLAG_SOFT_KEYBOARD != 0 &&
                    it.flags and KeyEvent.FLAG_KEEP_TOUCH_MODE != 0
            })
        }
        assertEquals(KeyCharacterMap.MODIFIER_BEHAVIOR_CHORDED,
            KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).modifierBehavior)
        assertFalse(mode.toggle(first))
        assertTrue(mode.move(first, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertTrue("Subsequent plain arrows have no modifier", first.events.takeLast(2).all {
            it.metaState == 0 && !it.isShiftPressed
        })
    }

    fun testDifferentConnectionCannotInheritModeOrReceiveOldHostEvents() {
        mode.toggle(first)
        assertTrue(mode.move(second, KeyEvent.KEYCODE_DPAD_DOWN))
        assertFalse(mode.enabled)
        assertTrue(first.events.isEmpty())
        assertEquals(2, second.events.size)
        assertTrue(second.events.all { it.metaState == 0 })
        assertTrue(mode.toggle(second))
        assertTrue(mode.move(second, KeyEvent.KEYCODE_DPAD_UP))
        assertTrue(second.events.takeLast(4).subList(1, 3).all { it.isShiftPressed })
    }

    fun testResetIsIdempotentAndNeverNeedsTextOrModifierRelease() {
        mode.toggle(first)
        mode.reset()
        mode.reset()
        assertFalse(mode.enabled)
        assertTrue(first.events.isEmpty())
        mode.move(first, KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue(first.events.all { it.metaState == 0 })
        assertFalse(mode.toggle(null))
        assertFalse(mode.move(null, KeyEvent.KEYCODE_DPAD_RIGHT))
    }

    fun testDetachedConnectionStillGetsKeyUpAndReleasesMode() {
        mode.toggle(first)
        first.throwAt = KeyEvent.KEYCODE_DPAD_RIGHT to KeyEvent.ACTION_DOWN
        assertFalse(mode.move(first, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertFalse(mode.enabled)
        assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_DOWN,
            KeyEvent.ACTION_UP, KeyEvent.ACTION_UP), first.events.map { it.action })
        assertEquals(KeyEvent.KEYCODE_SHIFT_LEFT, first.events.last().keyCode)
        assertEquals(listOf(KeyEvent.META_SHIFT_ON), first.clearedMeta)
        first.throwAt = null
        assertTrue(mode.move(first, KeyEvent.KEYCODE_DPAD_LEFT))
        assertTrue(first.events.takeLast(2).all { it.metaState == 0 })
    }

    fun testFalseDispatchResultStillReleasesShiftAndSendsTheWholeTransaction() {
        first.accepted = false
        mode.toggle(first)
        assertFalse(mode.move(first, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(listOf(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_SHIFT_LEFT), first.events.map { it.keyCode })
        assertEquals(KeyEvent.ACTION_UP, first.events.last().action)
        assertEquals(0, first.events.last().metaState)
    }

    fun testThrowingShiftDownStillReleasesOnlyTheCapturedConnection() {
        mode.toggle(first)
        first.throwAt = KeyEvent.KEYCODE_SHIFT_LEFT to KeyEvent.ACTION_DOWN
        assertFalse(mode.move(first, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertFalse(mode.enabled)
        assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), first.events.map { it.action })
        assertTrue(first.events.all { it.keyCode == KeyEvent.KEYCODE_SHIFT_LEFT })
        assertEquals(listOf(KeyEvent.META_SHIFT_ON), first.clearedMeta)
        assertTrue(second.events.isEmpty())
        mode.move(second, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue(second.events.all { it.metaState == 0 })
    }

    fun testThrowingArrowUpStillReleasesShift() {
        mode.toggle(first)
        first.throwAt = KeyEvent.KEYCODE_DPAD_RIGHT to KeyEvent.ACTION_UP
        assertFalse(mode.move(first, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertFalse(mode.enabled)
        assertEquals(4, first.events.size)
        assertEquals(KeyEvent.KEYCODE_SHIFT_LEFT, first.events.last().keyCode)
        assertEquals(KeyEvent.ACTION_UP, first.events.last().action)
        assertEquals(listOf(KeyEvent.META_SHIFT_ON), first.clearedMeta)
    }
}
