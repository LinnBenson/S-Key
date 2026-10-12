package io.secure.keyboard.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.secure.keyboard.R
import kotlin.math.max
import kotlin.math.min

/** Shared native dialogs. Styling never replaces the caller's validation/show/dismiss listeners. */
internal object SettingsDialogs {
    fun builder(activity: Activity): AlertDialog.Builder =
        AlertDialog.Builder(ContextThemeWrapper(activity, R.style.Theme_SKey_Settings_Dialog))

    fun show(dialog: AlertDialog, onShown: ((AlertDialog) -> Unit)? = null): AlertDialog {
        dialog.show()
        style(dialog)
        onShown?.invoke(dialog)
        return dialog
    }

    /** Invoke after showing an existing dialog; installed Save/Cancel callbacks remain unchanged. */
    fun style(dialog: AlertDialog) {
        val window = dialog.window ?: return
        val context = dialog.context
        val dp: (Int) -> Int = { SettingsPalette.dp(context, it) }
        val screenWidth = if (Build.VERSION.SDK_INT >= 30) {
            context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds.width()
        } else context.resources.displayMetrics.widthPixels
        val horizontalInsets = if (Build.VERSION.SDK_INT >= 30) {
            val insets = window.decorView.rootWindowInsets?.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            (insets?.left ?: 0) + (insets?.right ?: 0)
        } else 0
        val available = screenWidth - horizontalInsets - dp(32)
        val width = min(dp(560), available.coerceAtLeast(dp(48)))
        window.setBackgroundDrawable(SettingsPalette.shape(context, SettingsPalette.surface, 16))
        window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (Build.VERSION.SDK_INT >= 29) window.decorView.isForceDarkAllowed = false
        dialog.findViewById<TextView>(android.R.id.message)?.apply {
            setTextColor(SettingsPalette.ink)
            textSize = 15f
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        val titleId = context.resources.getIdentifier("alertTitle", "id", "android")
        if (titleId != 0) dialog.findViewById<TextView>(titleId)?.apply {
            setTextColor(SettingsPalette.ink)
            textSize = 20f
            setSingleLine(false)
            maxLines = Int.MAX_VALUE
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }
        styleFields(window.decorView)
        dialog.listView?.apply {
            setBackgroundColor(SettingsPalette.surface)
            divider = null
        }
        val buttons = listOf(AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_POSITIVE)
            .mapNotNull { which -> dialog.getButton(which)?.takeIf { it.visibility == View.VISIBLE }?.let { which to it } }
        buttons.forEach { (which, button) ->
            val destructive = which == AlertDialog.BUTTON_POSITIVE &&
                listOf("删除", "清空").any { button.text.startsWith(it) }
            SettingsPalette.styleButton(button, primary = which == AlertDialog.BUTTON_POSITIVE && !destructive, destructive = destructive)
            button.gravity = Gravity.CENTER
            button.setSingleLine(false)
        }
        val panel = buttons.firstOrNull()?.second?.parent as? LinearLayout
        if (panel != null && panel !is DialogActionLayout) {
            val host = panel.parent as? ViewGroup
            if (host != null) {
                val index = host.indexOfChild(panel)
                val layoutParams = panel.layoutParams
                val actions = DialogActionLayout(context).apply {
                    // Preserve this row's identity; some Android versions wrap it in a separate
                    // native buttonPanel ScrollView, so the row itself may legitimately have NO_ID.
                    id = panel.id
                }
                // Keep the native Button instances and listeners; replace only their adaptive layout.
                // The platform's internal ButtonBarLayout otherwise overrides custom stacking.
                buttons.forEach { (_, button) ->
                    panel.removeView(button)
                    actions.addView(button)
                }
                host.removeViewAt(index)
                host.addView(actions, index, layoutParams)
            }
        }
    }

    private class DialogActionLayout(context: Context) : LinearLayout(context) {
        init {
            tag = "settings_dialog_actions"
            // TOP respects the asymmetric top-only child margins. CENTER_VERTICAL shifts a
            // horizontal row half that margin into bottom padding, clipping the button corners.
            gravity = Gravity.END or Gravity.TOP
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            var needed = 0
            for (index in 0 until childCount) {
                val button = getChildAt(index) as Button
                needed += button.paint.measureText(button.text.toString()).toInt() + dp(36)
            }
            val stacked = needed > available || resources.configuration.fontScale >= 1.5f
            val direction = if (stacked) VERTICAL else HORIZONTAL
            if (orientation != direction) orientation = direction
            val width = if (stacked) -1 else 0
            val weight = if (stacked) 0f else 1f
            for (index in 0 until childCount) {
                val button = getChildAt(index)
                val previous = button.layoutParams as? LayoutParams
                if (previous == null || previous.width != width || previous.weight != weight) {
                    button.layoutParams = LayoutParams(width, -2, weight).apply {
                        setMargins(dp(4), dp(4), dp(4), 0)
                    }
                }
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            // The native button-panel wrapper resolves its minimum through this single child.
            // Reserve actual scaled/wrapped labels, not a fixed 48dp assumption.
            var childHeight = 0
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                if (child.visibility == View.GONE) continue
                val params = child.layoutParams as LayoutParams
                val extent = child.measuredHeight + params.topMargin + params.bottomMargin
                childHeight = if (stacked) childHeight + extent else max(childHeight, extent)
            }
            val required = paddingTop + paddingBottom + childHeight
            if (minimumHeight != required) {
                minimumHeight = required
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }

        private fun dp(value: Int) = SettingsPalette.dp(context, value)
    }

    /** Keep the dialog body scrollable while native title/actions stay outside the scroll area. */
    fun scrollable(context: Context, content: View): ScrollView =
        BoundedDialogScroll(context).apply {
            tag = "settings_dialog_scroll"
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            // Callers with their own content insets keep them; otherwise reserve a consistent
            // inner gutter rather than letting labels and edit-field borders touch the dialog edge.
            val horizontal = SettingsPalette.dp(context, 20)
            val vertical = SettingsPalette.dp(context, 8)
            setPadding(
                (horizontal - content.paddingLeft).coerceAtLeast(0),
                (vertical - content.paddingTop).coerceAtLeast(0),
                (horizontal - content.paddingRight).coerceAtLeast(0),
                (vertical - content.paddingBottom).coerceAtLeast(0),
            )
            addView(content, ViewGroup.LayoutParams(-1, -2))
        }

    private fun styleFields(view: View) {
        if (view is EditText) SettingsInputs.style(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) styleFields(view.getChildAt(i))
    }

    private class BoundedDialogScroll(context: Context) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val totalHeight = if (Build.VERSION.SDK_INT >= 30) {
                context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds.height()
            } else resources.displayMetrics.heightPixels
            val insets = if (Build.VERSION.SDK_INT >= 30) rootWindowInsets?.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime(),
            ) else null
            val bars = if (Build.VERSION.SDK_INT >= 30) (insets?.top ?: 0) + (insets?.bottom ?: 0)
                else (rootWindowInsets?.systemWindowInsetTop ?: 0) + (rootWindowInsets?.systemWindowInsetBottom ?: 0)
            // Reserve title, margin and two stacked 48dp action targets at large font sizes.
            val reserved = SettingsPalette.dp(context, if (resources.configuration.fontScale >= 1.5f) 256 else 188)
            val cap = max(SettingsPalette.dp(context, 72), totalHeight - bars - reserved)
            val limit = when (MeasureSpec.getMode(heightMeasureSpec)) {
                MeasureSpec.UNSPECIFIED -> cap
                else -> min(MeasureSpec.getSize(heightMeasureSpec), cap)
            }
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
        }
    }
}
