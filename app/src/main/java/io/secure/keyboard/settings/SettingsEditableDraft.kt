package io.secure.keyboard.settings

import android.os.Bundle

/** Complete current edit only. No Editable, spans, UI objects, callback or persistent history. */
internal class SettingsEditableDraft(
    val route: String,
    val type: String,
    val key: String?,
    val kind: String?,
    val itemId: String?,
    fields: List<Field>,
    val focused: Int,
) {
    val fields: List<Field> = fields.toList()

    data class Field(val text: String, val selectionStart: Int, val selectionEnd: Int, val error: String?)

    /** May be large in memory when adapting a retained edit; never persist without the size gate. */
    fun toEditorBundle(): Bundle = Bundle().apply {
        putString("route", route)
        putString("type", type)
        putString("key", key)
        putString("kind", kind)
        putString("item_id", itemId)
        fields.forEachIndexed { index, field ->
            putString("field_$index", field.text)
            putInt("selection_start_$index", field.selectionStart)
            putInt("selection_end_$index", field.selectionEnd)
            putString("error_$index", field.error)
        }
        putInt("focused", focused)
    }
}

/** A conservative app budget, not a promise about Android's shared Binder transaction buffer. */
internal object SettingsDraftBudget {
    const val MAX_SAVED_TEXT_CODE_UNITS = 16_384

    // Count every persisted value, including descriptor/error strings. Subtraction avoids overflow
    // and accepts live Editables without copying large text merely to decide not to save it.
    fun accepts(parts: Sequence<CharSequence?>): Boolean {
        var remaining = MAX_SAVED_TEXT_CODE_UNITS
        for (part in parts) {
            val size = part?.length ?: 0
            if (size > remaining) return false
            remaining -= size
        }
        return true
    }
}
