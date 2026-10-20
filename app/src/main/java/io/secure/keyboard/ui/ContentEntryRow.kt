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
    private val actionColor: Int,
    private val pinnedColor: Int,
    deleteColor: Int,
    private val categoryLabel: String,
    onInsert: (String) -> Unit,
    onPin: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onOpenLink: (() -> Unit)? = null,
    private val feedback: () -> KeyboardPressFeedback = { KeyboardPressFeedback() },
) : LinearLayout(context) {
    private var fullText = text
    private var insertAction = onInsert
    private var pinCallback = onPin
    private var deleteCallback = onDelete
    private var openLinkCallback = onOpenLink
    private var isPinned = pinned
    val textView: TextView
    val openLinkAction: View
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
            val press = installFeedback(this)
            setOnClickListener { press.click(); insertAction(fullText) }
        }
        addView(textView, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        openLinkAction = ActionTarget(context, "open_link", "在浏览器中打开链接", actionColor).apply {
            visibility = if (openLinkCallback != null) View.VISIBLE else View.GONE
            val press = installFeedback(this)
            setOnClickListener { press.click(); openLinkCallback?.invoke() }
        }
        addView(openLinkAction, LayoutParams(dp(48), dp(48)))
        pinAction = ActionTarget(
            context,
            if (pinned) "unpin" else "pin",
            if (pinned) "取消置顶$categoryLabel" else "置顶$categoryLabel",
            if (pinned) pinnedColor else actionColor,
        ).apply {
            isSelected = pinned
            val press = installFeedback(this)
            setOnClickListener { press.click(); pinCallback(!isPinned) }
        }
        deleteAction = ActionTarget(context, "delete", "删除$categoryLabel", deleteColor).apply {
            val press = installFeedback(this)
            setOnClickListener { press.click(); deleteCallback() }
        }
        addView(pinAction, LayoutParams(dp(48), dp(48)))
        addView(deleteAction, LayoutParams(dp(48), dp(48)))
    }

    /** List recycling replaces callbacks as well as labels; no former entry stays captured. */
    fun bind(text: String, pinned: Boolean, onInsert: (String) -> Unit,
             onPin: (Boolean) -> Unit, onDelete: () -> Unit, onOpenLink: (() -> Unit)? = null) {
        openLinkCallback = onOpenLink
        openLinkAction.visibility = if (onOpenLink != null) View.VISIBLE else View.GONE
        fullText = text
        textView.text = text
        insertAction = onInsert
        pinCallback = onPin
        deleteCallback = onDelete
        isPinned = pinned
        pinAction.isSelected = pinned
        pinAction.contentDescription = if (pinned) "取消置顶$categoryLabel" else "置顶$categoryLabel"
        val target = pinAction as FrameLayout
        val color = if (pinned) pinnedColor else actionColor
        target.background = ripple(color)
        target.removeAllViews()
        target.addView(ToolIcon(context, if (pinned) "unpin" else "pin", color).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(-1, -1))
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility") // View still dispatches normal and accessibility clicks.
    private fun installFeedback(view: View) = feedback().also { press ->
        view.setOnTouchListener { _, event -> press.onTouch(event); false }
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
