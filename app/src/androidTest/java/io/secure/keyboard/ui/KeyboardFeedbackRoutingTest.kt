package io.secure.keyboard.ui

import android.content.Context
import android.content.ContextWrapper
import android.test.AndroidTestCase
import android.view.HapticFeedbackConstants
import android.view.View

/** Checks request routing; it does not claim to measure audible sound or physical vibration. */
@Suppress("DEPRECATION")
class KeyboardFeedbackRoutingTest : AndroidTestCase() {
    private class AudioUnavailableContext(context: Context) : ContextWrapper(context) {
        var audioLookups = 0
        val requests = mutableListOf<String>()
        override fun getSystemService(name: String): Any? {
            if (name == Context.AUDIO_SERVICE) {
                audioLookups++
                requests.add("sound")
                throw IllegalStateException("No audio service in this controlled fixture")
            }
            return super.getSystemService(name)
        }
    }

    private class RecordingView(context: Context) : View(context) {
        var shown = true
        val effects = mutableListOf<Int>()
        override fun isShown() = shown
        override fun performHapticFeedback(feedbackConstant: Int): Boolean {
            effects.add(feedbackConstant)
            (context as? AudioUnavailableContext)?.requests?.add("haptic")
            return true
        }
    }

    fun testSoundOffDoesNotLookUpAudioAndKeepsIndependentHaptic() {
        val source = AudioUnavailableContext(context)
        val view = RecordingView(source)
        KeyboardFeedback.perform(view, sound = false, vibration = true, strength = 10)
        assertEquals(0, source.audioLookups)
        assertEquals(listOf(HapticFeedbackConstants.KEYBOARD_TAP), view.effects)
        KeyboardFeedback.perform(view, sound = false, vibration = false, strength = 10)
        assertEquals(0, source.audioLookups)
        assertEquals(1, view.effects.size)
    }

    fun testUnavailableSoundDoesNotBlockIndependentHapticAndHiddenViewsAreSilent() {
        val source = AudioUnavailableContext(context)
        val view = RecordingView(source)
        KeyboardFeedback.perform(view, sound = true, vibration = true, strength = 10)
        assertEquals(1, source.audioLookups)
        assertEquals(listOf("sound", "haptic"), source.requests)
        assertEquals(listOf(HapticFeedbackConstants.KEYBOARD_TAP), view.effects)
        view.shown = false
        KeyboardFeedback.perform(view, sound = true, vibration = true, strength = 10)
        assertEquals(1, source.audioLookups)
        assertEquals(1, view.effects.size)
    }

    fun testKeyboardOwnedControlsDisableImplicitClickAndLongPressEffects() {
        val view = View(context)
        KeyboardFeedback.disableAutomatic(view)
        assertFalse(view.isSoundEffectsEnabled)
        assertFalse(view.isHapticFeedbackEnabled)
    }
}
