package io.secure.keyboard.settings

import android.content.Context
/** Companion application chrome is deliberately independent of every keyboard palette. */
internal object SettingsPalette {
    const val background: Int = 0xFFF6F6F3.toInt()
    const val surface: Int = 0xFFFFFFFF.toInt()
    const val functional: Int = 0xFFF0F0EC.toInt()
    const val ink: Int = 0xFF2B2B2B.toInt()
    const val accent: Int = 0xFF2B2B2B.toInt()
    const val onAccent: Int = 0xFFFFFFFF.toInt()
    const val secondaryText: Int = 0xFF73736F.toInt()
    const val outline: Int = 0xFF8A8A83.toInt()
    const val divider: Int = 0xFFE3E3DD.toInt()
    const val error: Int = 0xFF9B3038.toInt()
    const val ripple: Int = 0x1F2B2B2B

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + .5f).toInt()

}
