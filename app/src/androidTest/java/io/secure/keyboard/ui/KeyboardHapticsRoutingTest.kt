package io.secure.keyboard.ui

import android.content.Context
import android.test.AndroidTestCase
import android.view.HapticFeedbackConstants
import android.view.View

/** Verifies API routing, not the physical actuator or its perceived intensity. */
@Suppress("DEPRECATION")
class KeyboardHapticsRoutingTest : AndroidTestCase() {
    private class RecordingView(context: Context) : View(context) {
        var shown = true
        var calls = 0
        var lastEffect = -1

        override fun isShown() = shown

        override fun performHapticFeedback(feedbackConstant: Int): Boolean {
            calls++
            lastEffect = feedbackConstant
            return true
        }
    }

    fun testTenPercentRequestsSystemKeyboardEffect() {
        val view = RecordingView(context)
        KeyboardHaptics.perform(view, true, 10)
        assertEquals(1, view.calls)
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, view.lastEffect)
    }

    fun testDisabledOrHiddenControlsDoNotRequestFeedback() {
        val view = RecordingView(context)
        KeyboardHaptics.perform(view, false, 10)
        view.isHapticFeedbackEnabled = false
        KeyboardHaptics.perform(view, true, 10)
        view.isHapticFeedbackEnabled = true
        view.shown = false
        KeyboardHaptics.perform(view, true, 10)
        assertEquals(0, view.calls)
    }
}
