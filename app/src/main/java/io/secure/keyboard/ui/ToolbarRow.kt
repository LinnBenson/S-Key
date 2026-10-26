package io.secure.keyboard.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import kotlin.math.roundToInt

/** Equal gaps ending at the separate hide control; narrow keyboards shrink touch slots to fit. */
internal class ToolbarRow(context: Context) : LinearLayout(context) {
    private fun iconsOnly() = childCount > 0 && (0 until childCount).all { getChildAt(it) is ToolIcon }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (iconsOnly()) {
            val available = (View.MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
            val slot = minOf((44 * resources.displayMetrics.density).roundToInt(), available / childCount)
            for (index in 0 until childCount) {
                val params = getChildAt(index).layoutParams as LayoutParams
                params.width = slot
                params.weight = 0f
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (!iconsOnly()) return // Quick-paste chips keep the normal weighted LinearLayout layout.
        val available = width - paddingLeft - paddingRight
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val offset = (index * available.toFloat() / childCount).roundToInt()
            val left = if (layoutDirection == View.LAYOUT_DIRECTION_RTL)
                width - paddingRight - offset - child.measuredWidth else paddingLeft + offset
            child.layout(left, child.top, left + child.measuredWidth, child.bottom)
        }
    }
}
