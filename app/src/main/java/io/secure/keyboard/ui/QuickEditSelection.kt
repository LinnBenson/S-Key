package io.secure.keyboard.ui

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.InputConnection

/**
 * A transient selection toggle, never an editor-text mirror or an input history.
 * The host owns the anchor, grapheme boundaries, bidi behavior, and visual line movement.
 */
internal class QuickEditSelection {
    var enabled: Boolean = false
        private set
    private var editor: InputConnection? = null

    fun toggle(connection: InputConnection?): Boolean {
        validate(connection)
        if (enabled || connection == null) reset()
        else {
            editor = connection
            enabled = true
        }
        return enabled
    }

    fun reset() {
        enabled = false
        editor = null
    }

    fun validate(connection: InputConnection?) {
        if (enabled && connection !== editor) reset()
    }

    fun move(connection: InputConnection?, key: Int): Boolean {
        require(key in DIRECTIONS)
        validate(connection)
        if (connection == null) return false
        // Android's ArrowKeyMovementMethod reads Shift spans on the host Editable,
        // not just the arrow's metaState. Bracket each arrow with a complete Shift
        // pair on this captured connection; never hold a modifier between taps.
        val selecting = enabled
        val meta = if (selecting) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        val time = SystemClock.uptimeMillis()
        fun event(action: Int, code: Int = key, modifiers: Int = meta) = KeyEvent(
            time, SystemClock.uptimeMillis(), action, code, 0, modifiers,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
            InputDevice.SOURCE_KEYBOARD,
        )
        fun arrow(): Boolean = try { connection.sendKeyEvent(event(KeyEvent.ACTION_DOWN)) }
            finally { connection.sendKeyEvent(event(KeyEvent.ACTION_UP)) }
        return try {
            if (!selecting) arrow()
            else try {
                // A false result cannot abort: BaseInputConnection returns false
                // even after dispatching the event to its host window.
                connection.sendKeyEvent(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT))
                arrow()
            } finally {
                // VIRTUAL_KEYBOARD is FULL/CHORDED: its Shift-up removes the host
                // span even if the arrow was unhandled, so it cannot latch Shift.
                connection.sendKeyEvent(event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0))
            }
        } catch (_: RuntimeException) {
            // A detached/unsupported connection must not retain selection mode.
            reset()
            if (selecting) try {
                connection.clearMetaKeyStates(KeyEvent.META_SHIFT_ON)
            } catch (_: RuntimeException) { /* The old connection may already be closed. */ }
            false
        }
    }

    private companion object {
        val DIRECTIONS = setOf(
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        )
    }
}
