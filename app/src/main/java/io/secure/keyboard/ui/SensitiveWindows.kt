package io.secure.keyboard.ui

import android.view.Window
import android.view.WindowManager

internal object SensitiveWindows {
    private val pages = setOf("api", "clipboard", "phrases", "dictionary", "english_dictionary",
        "custom_ai", "builtin_ai_prompts", "builtin_dictionaries", "cleanup", "recovery", "emoji")
    fun settingsPage(page: String): Boolean = page in pages
    fun securePopup(popup: android.widget.PopupWindow, sensitive: Boolean = true) {
        val root = popup.contentView.rootView
        val params = root.layoutParams as? WindowManager.LayoutParams ?: return
        params.flags = if (sensitive && io.secure.keyboard.data.SettingsStore(root.context).screenshotProtection)
            params.flags or WindowManager.LayoutParams.FLAG_SECURE
        else params.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
        (root.context.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager).updateViewLayout(root, params)
    }
    fun apply(window: Window?, sensitive: Boolean) {
        if (window != null && sensitive && io.secure.keyboard.data.SettingsStore(window.context).screenshotProtection) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
