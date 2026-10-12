package io.secure.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo

/** Pure, content-blind initial-mode policy. It never reads an editor or writes preferences. */
internal object EditorEntryPolicy {
    data class Entry(val chinese: Boolean, val symbols: Boolean, val secret: Boolean)

    fun resolve(inputType: Int, imeOptions: Int, rememberedChinese: Boolean): Entry {
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val text = inputClass == InputType.TYPE_CLASS_TEXT
        val number = inputClass == InputType.TYPE_CLASS_NUMBER
        val secret = (text && variation in textPasswords) ||
            (number && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        val english = secret ||
            (text && variation in englishTextFields) ||
            (imeOptions and EditorInfo.IME_FLAG_FORCE_ASCII != 0)
        return Entry(
            chinese = !english && rememberedChinese,
            // Number class wins even for numeric passwords and FORCE_ASCII editors.
            symbols = number,
            secret = secret,
        )
    }

    private val textPasswords = setOf(
        InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
    )
    private val englishTextFields = setOf(
        InputType.TYPE_TEXT_VARIATION_URI,
        InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
    )
}
