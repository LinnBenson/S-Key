package io.secure.keyboard.settings

import android.content.Context
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Local field styling; no keyboard input type, filters or persistence semantics are changed. */
internal object SettingsInputs {
    fun field(context: Context, hint: String = "", singleLine: Boolean = false): EditText =
        EditText(context).apply {
            this.hint = hint
            setSingleLine(singleLine)
            style(this)
        }

    fun style(field: EditText): EditText = field.apply {
        textSize = 16f
        setTextColor(SettingsPalette.readableText())
        setHintTextColor(SettingsPalette.secondaryText)
        highlightColor = 0x332B2B2B
        minimumHeight = SettingsPalette.dp(context, 48)
        minHeight = SettingsPalette.dp(context, 48)
        setPadding(SettingsPalette.dp(context, 12), SettingsPalette.dp(context, 12), SettingsPalette.dp(context, 12), SettingsPalette.dp(context, 12))
        backgroundTintList = null
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_activated), SettingsPalette.shape(context, SettingsPalette.functional, 16, SettingsPalette.error))
            addState(intArrayOf(android.R.attr.state_focused), SettingsPalette.shape(context, SettingsPalette.functional, 16, SettingsPalette.accent))
            addState(intArrayOf(), SettingsPalette.shape(context, SettingsPalette.functional, 16))
        }
        if (Build.VERSION.SDK_INT >= 29) {
            textCursorDrawable?.mutate()?.setTint(SettingsPalette.accent)
            textSelectHandle?.mutate()?.setTint(SettingsPalette.accent)
            textSelectHandleLeft?.mutate()?.setTint(SettingsPalette.accent)
            textSelectHandleRight?.mutate()?.setTint(SettingsPalette.accent)
        }
    }

    fun labeled(parent: LinearLayout, label: String, field: EditText): TextView {
        if (field.id == View.NO_ID) field.id = View.generateViewId()
        val title = TextView(parent.context).apply {
            text = label
            textSize = 14f
            setTextColor(SettingsPalette.ink)
            labelFor = field.id
            setPadding(0, SettingsPalette.dp(context, 8), 0, SettingsPalette.dp(context, 8))
        }
        parent.addView(title)
        parent.addView(field, LinearLayout.LayoutParams(-1, -2))
        return title
    }

    fun error(field: EditText, message: String?) {
        field.isActivated = message != null
        field.error = message
        if (message != null) {
            field.requestFocus()
            field.announceForAccessibility(message)
        }
    }
}
