package io.secure.keyboard.settings

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.SeekBar
import io.secure.keyboard.R

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

    fun shape(context: Context, color: Int, radius: Int = 24, stroke: Int? = null) =
        GradientDrawable().apply {
            cornerRadius = dp(context, radius).toFloat()
            setColor(color)
            stroke?.let { setStroke(dp(context, 1), it) }
        }

    fun readableText(enabled: Int = ink) = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(secondaryText, enabled),
    )

    fun buttonBackground(context: Context, primary: Boolean = false): RippleDrawable {
        val states = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), shape(context, functional, 18))
            addState(intArrayOf(android.R.attr.state_focused), shape(context, if (primary) accent else functional, 18, outline))
            addState(intArrayOf(), shape(context, if (primary) accent else functional, 18))
        }
        return RippleDrawable(ColorStateList.valueOf(ripple), states, null)
    }

    fun styleButton(button: Button, primary: Boolean = false, destructive: Boolean = false) {
        val context = button.context
        button.isAllCaps = false
        button.textSize = 15f
        button.minHeight = dp(context, 52)
        button.minimumHeight = dp(context, 52)
        button.minWidth = dp(context, 48)
        button.minimumWidth = dp(context, 48)
        button.setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10))
        button.setTextColor(readableText(if (destructive) error else if (primary) onAccent else ink))
        button.backgroundTintList = null
        button.background = buttonBackground(context, primary)
        button.elevation = 0f
        button.stateListAnimator = null
    }

    fun styleCompound(control: CompoundButton) {
        control.setTextColor(readableText())
        control.textSize = 15f
        control.minimumHeight = dp(control.context, 56)
        val selected = ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(outline, accent, outline),
        )
        control.buttonTintList = selected
        if (control is android.widget.Switch) {
            control.thumbTintList = selected
            control.trackTintList = ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(divider, outline, functional),
            )
            control.switchPadding = dp(control.context, 16)
        }
    }

    fun styleSlider(slider: SeekBar) {
        slider.minimumHeight = dp(slider.context, 48)
        slider.thumbTintList = ColorStateList.valueOf(accent)
        slider.progressTintList = ColorStateList.valueOf(accent)
        slider.progressBackgroundTintList = ColorStateList.valueOf(functional)
        slider.splitTrack = false
    }
}

/** Call before super.onCreate; only companion Activities may opt into this theme. */
internal fun Activity.applySettingsActivityTheme() {
    setTheme(R.style.Theme_SKey_Settings)
    if (Build.VERSION.SDK_INT >= 29) window.decorView.isForceDarkAllowed = false
}
