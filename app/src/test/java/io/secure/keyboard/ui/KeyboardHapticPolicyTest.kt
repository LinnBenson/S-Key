package io.secure.keyboard.ui

import org.junit.Assert.*
import org.junit.Test

class KeyboardHapticPolicyTest {
    @Test
    fun tenPercentAlwaysUsesSystemKeyboardFeedback() {
        for (sdk in 26..36) assertTrue(KeyboardHapticPolicy.useSystemFeedback(10, sdk))
    }

    @Test
    fun legacyAndApi30KeepSystemFallbackAtEveryStrength() {
        for (sdk in 26..30) for (strength in 1..100) {
            assertTrue(KeyboardHapticPolicy.useSystemFeedback(strength, sdk))
        }
    }

    @Test
    fun higherStrengthCanUsePrimitivesOnlyOnReliableApis() {
        for (sdk in 31..36) {
            assertTrue(KeyboardHapticPolicy.useSystemFeedback(1, sdk))
            assertFalse(KeyboardHapticPolicy.useSystemFeedback(11, sdk))
            assertFalse(KeyboardHapticPolicy.useSystemFeedback(100, sdk))
        }
    }

    @Test
    fun scaleIsBoundedAndMonotonicallyIncreasing() {
        assertEquals(0.01f, KeyboardHapticPolicy.primitiveScale(-20), 0.0001f)
        assertEquals(1f, KeyboardHapticPolicy.primitiveScale(500), 0.0001f)
        for (strength in 11..99) assertTrue(
            KeyboardHapticPolicy.primitiveScale(strength) <
                KeyboardHapticPolicy.primitiveScale(strength + 1)
        )
    }
}
