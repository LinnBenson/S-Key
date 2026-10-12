package io.secure.keyboard.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Compact preview with independent, reserved pin/delete targets. The original text is never cut. */
class ContentEntryRow(
    context: Context,
    text: String,
    pinned: Boolean,
    textColor: Int,
    surfaceColor: Int,
    actionColor: Int,
    pinnedColor: Int,
    deleteColor: Int,
    categoryLabel: String,
    onInsert: (String) -> Unit,
    onPin: (Boolean) -> Unit,
    onDelete: () -> Unit,
) : LinearLayout(context) {
    private val fullText = text
    val textView: TextView
    val pinAction: View
    val deleteAction: View

    init {
        orientation = HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(52)
        setPadding(dp(12), dp(2), dp(4), dp(2))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(surfaceColor)
        }
        textView = object : TextView(context) {
            override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(info)
                info.className = "android.widget.Button"
            }
        }.apply {
            KeyboardFeedback.disableAutomatic(this)
            this.text = fullText
            textSize = 15f
            setTextColor(textColor)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            minWidth = 0
            setPadding(0, 0, dp(8), 0)
            isClickable = true
            isFocusable = true
            defaultFocusHighlightEnabled = false
            background = ripple(actionColor)
            setOnClickListener { onInsert(fullText) }
        }
        addView(textView, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        pinAction = ActionTarget(
            context,
            if (pinned) "unpin" else "pin",
            if (pinned) "取消置顶$categoryLabel" else "置顶$categoryLabel",
            if (pinned) pinnedColor else actionColor,
        ).apply {
            isSelected = pinned
            setOnClickListener { onPin(!pinned) }
        }
        deleteAction = ActionTarget(context, "delete", "删除$categoryLabel", deleteColor).apply {
            setOnClickListener { onDelete() }
        }
        addView(pinAction, LayoutParams(dp(48), dp(48)))
        addView(deleteAction, LayoutParams(dp(48), dp(48)))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun ripple(color: Int): RippleDrawable = RippleDrawable(
        ColorStateList.valueOf((color and 0x00FFFFFF) or 0x22000000),
        null,
        GradientDrawable().apply {
            setColor(android.graphics.Color.WHITE)
            cornerRadius = dp(12).toFloat()
        },
    )

    private inner class ActionTarget(context: Context, kind: String, label: String, color: Int) :
        FrameLayout(context) {
        init {
            KeyboardFeedback.disableAutomatic(this)
            isClickable = true
            isFocusable = true
            defaultFocusHighlightEnabled = false
            contentDescription = label
            background = ripple(color)
            addView(
                ToolIcon(context, kind, color).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
            )
        }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = "android.widget.Button"
        }
    }
}
