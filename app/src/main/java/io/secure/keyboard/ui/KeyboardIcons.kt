package io.secure.keyboard.ui
import io.secure.keyboard.R
/** Shared UI icon catalog: Miuix plus dedicated Material keyboard symbols. */
object KeyboardIcons {
    fun resource(kind: String): Int = when(kind) {
        "ai" -> R.drawable.ic_keyboard_auto_awesome
        "translate" -> R.drawable.ic_miuix_translate
        "globe" -> R.drawable.ic_keyboard_language
        "enter" -> R.drawable.ic_keyboard_keyboard_return
        "shift" -> R.drawable.ic_keyboard_shift
        "caps" -> R.drawable.ic_keyboard_keyboard_capslock
        "backspace" -> R.drawable.ic_keyboard_backspace
        "more" -> R.drawable.ic_miuix_gridview
        "hide" -> R.drawable.ic_miuix_chevrondown
        "back" -> R.drawable.ic_miuix_back
        "forward" -> R.drawable.ic_miuix_chevronforward
        "clear" -> R.drawable.ic_miuix_close
        "edit" -> R.drawable.ic_miuix_edit
        "pin" -> R.drawable.ic_miuix_pin
        "unpin" -> R.drawable.ic_miuix_unpin
        "delete" -> R.drawable.ic_miuix_delete
        "clipboard" -> R.drawable.ic_keyboard_content_paste
        "phrases" -> R.drawable.ic_miuix_notes
        "voice" -> R.drawable.ic_miuix_mic
        "emoji" -> R.drawable.ic_keyboard_mood
        "recent" -> R.drawable.ic_miuix_recent
        "drag_handle" -> R.drawable.ic_keyboard_drag_handle
        "resize" -> R.drawable.ic_keyboard_resize_diagonal
        "quickedit" -> R.drawable.ic_keyboard_edit_note
        "arrow_up" -> R.drawable.ic_keyboard_arrow_upward
        "arrow_down" -> R.drawable.ic_keyboard_arrow_downward
        "arrow_left" -> R.drawable.ic_keyboard_arrow_back
        "arrow_right" -> R.drawable.ic_keyboard_arrow_forward
        "undo" -> R.drawable.ic_miuix_undo
        "layout" -> R.drawable.ic_keyboard_open_in_full
        "floating" -> R.drawable.ic_keyboard_floating_enter
        "floating_enter" -> R.drawable.ic_keyboard_floating_enter
        "floating_exit" -> R.drawable.ic_keyboard_floating_exit
        "split" -> R.drawable.ic_keyboard_split_keyboard
        "dictionary" -> R.drawable.ic_keyboard_menu_book
        "english_dictionary" -> R.drawable.ic_keyboard_dictionary
        "theme" -> R.drawable.ic_miuix_theme
        "settings" -> R.drawable.ic_miuix_settings
        "input" -> R.drawable.ic_miuix_tune
        "keyboard" -> R.drawable.ic_keyboard_keyboard
        "check" -> R.drawable.ic_miuix_ok
        else -> R.drawable.ic_miuix_gridview
    }
}
