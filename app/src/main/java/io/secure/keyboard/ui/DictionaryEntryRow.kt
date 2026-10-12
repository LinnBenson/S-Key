package io.secure.keyboard.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.secure.keyboard.data.ContentStore

/** Bounded text columns cannot consume the two trailing 48dp action targets. */
class DictionaryEntryRow(
    context: Context,
    entry: ContentStore.DictionaryEntry,
    textColor: Int,
    codeColor: Int,
    surfaceColor: Int,
    editColor: Int,
    deleteColor: Int,
    onEdit: (ContentStore.DictionaryEntry) -> Unit,
    onDelete: (ContentStore.DictionaryEntry) -> Unit,
) : LinearLayout(context) {
    val codeView: TextView
    val wordView: TextView
    val editAction: View
    val deleteAction: View

    init {
        orientation = HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        setPadding(dp(4), dp(4), 0, dp(4))
        background =
            GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(surfaceColor)
            }
        fun preview(value: String, color: Int) =
            TextView(context).apply {
                text = value
                textSize = 15f
                includeFontPadding = false
                setTextColor(color)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                minWidth = 0
                setPadding(0, 0, dp(8), 0)
            }
        codeView =
            preview(entry.reading, codeColor).apply {
                textSize = 12f
                typeface = Typeface.MONOSPACE
                textDirection = View.TEXT_DIRECTION_LTR
            }
        wordView = preview(entry.word, textColor).apply { CandidateFont.apply(this) }
        val labels = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(wordView, LayoutParams(-1, -2))
            addView(codeView, LayoutParams(-1, -2).apply { topMargin = dp(3) })
        }
        addView(labels, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        editAction =
            ActionTarget(context, "edit", "编辑词条", editColor).apply {
                setOnClickListener { onEdit(entry) }
            }
        deleteAction =
            ActionTarget(context, "delete", "删除词条", deleteColor).apply {
                setOnClickListener { onDelete(entry) }
            }
        addView(editAction, LayoutParams(dp(48), dp(48)))
        addView(deleteAction, LayoutParams(dp(48), dp(48)))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private class ActionTarget(context: Context, kind: String, label: String, color: Int) :
        FrameLayout(context) {
        init {
            isClickable = true
            isFocusable = true
            contentDescription = label
            background =
                RippleDrawable(
                    ColorStateList.valueOf(0x1F2B2B2B),
                    null,
                    GradientDrawable().apply {
                        setColor(android.graphics.Color.WHITE)
                        cornerRadius = 12f * resources.displayMetrics.density
                    },
                )
            addView(
                ToolIcon(context, kind, color, artworkSizeDp = 20f).apply {
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
