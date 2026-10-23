package io.secure.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.*
import org.junit.Test

class EditorEntryPolicyTest {
    @Test
    fun passwordUriAndBothEmailVariationsStartEnglish() {
        for (variation in listOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
        )) for (remembered in listOf(true, false)) {
            val mode = resolve(InputType.TYPE_CLASS_TEXT or variation, remembered)
            assertFalse("English text variation $variation", mode.chinese)
            assertFalse(mode.symbols)
            assertEquals(variation in setOf(0x80, 0x90, 0xe0), mode.secret)
        }
    }

    @Test
    fun everyNumberVariationStartsSymbolsAndOnlyNumberPasswordIsSecret() {
        for (variation in (0..255).map { it shl 4 }) {
            for (remembered in listOf(true, false)) {
                val mode = resolve(InputType.TYPE_CLASS_NUMBER or variation, remembered)
                assertTrue("Numeric class variation $variation", mode.symbols)
                assertEquals(variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD, mode.secret)
                assertEquals(remembered && !mode.secret, mode.chinese)
            }
        }
    }

    @Test
    fun signedDecimalFlagsDoNotDefeatNumericPasswordPrecedence() {
        for (flags in listOf(0, InputType.TYPE_NUMBER_FLAG_SIGNED,
            InputType.TYPE_NUMBER_FLAG_DECIMAL,
            InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL)) {
            for (ascii in listOf(0, EditorInfo.IME_FLAG_FORCE_ASCII)) {
                val mode = EditorEntryPolicy.resolve(InputType.TYPE_CLASS_NUMBER or
                    InputType.TYPE_NUMBER_VARIATION_PASSWORD or flags, ascii, true)
                assertEquals(EditorEntryPolicy.Entry(false, true, true), mode)
            }
        }
    }

    @Test
    fun allOtherClassesAndVariationsRestoreRememberedLanguageWithoutGuessing() {
        val specialText = setOf(0x10, 0x20, 0x80, 0x90, 0xd0, 0xe0)
        for (inputClass in 0..15) for (variation in (0..255).map { it shl 4 }) {
            if (inputClass == InputType.TYPE_CLASS_NUMBER ||
                (inputClass == InputType.TYPE_CLASS_TEXT && variation in specialText)) continue
            for (remembered in listOf(true, false)) {
                assertEquals("Class $inputClass, variation $variation",
                    EditorEntryPolicy.Entry(remembered, false, false),
                    resolve(inputClass or variation, remembered))
            }
        }
    }

    @Test
    fun forceAsciiIsTemporaryAndNumberClassStillStartsOnSymbols() {
        for (inputType in listOf(InputType.TYPE_NULL, InputType.TYPE_CLASS_TEXT,
            InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME, InputType.TYPE_CLASS_NUMBER)) {
            assertEquals(EditorEntryPolicy.Entry(false,
                inputType == InputType.TYPE_CLASS_NUMBER, false),
                EditorEntryPolicy.resolve(inputType,
                    EditorInfo.IME_FLAG_FORCE_ASCII or EditorInfo.IME_ACTION_DONE, true))
        }
    }

    @Test
    fun unrelatedTextAndImeFlagsDoNotChangeEntryClassification() {
        for (inputType in listOf(InputType.TYPE_CLASS_TEXT,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)) {
            for (flags in listOf(InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS,
                InputType.TYPE_TEXT_FLAG_AUTO_CORRECT, InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE)) {
                assertEquals(resolve(inputType, true), EditorEntryPolicy.resolve(inputType or flags,
                    EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
                    true))
            }
        }
    }

    @Test
    fun privateEditorsDisableLearningOnlyForTheirSession() {
        assertFalse(EditorEntryPolicy.allowsPersonalizedLearning(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_SEARCH))
        assertTrue(EditorEntryPolicy.allowsPersonalizedLearning(EditorInfo.IME_ACTION_SEARCH))
    }

    private fun resolve(inputType: Int, remembered: Boolean) =
        EditorEntryPolicy.resolve(inputType, 0, remembered)
}
