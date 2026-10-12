package io.secure.keyboard.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout

/** Five full-height key cells with exactly mirrored pixel widths and a centered spacebar. */
class SymmetricBottomRow(context: Context) : LinearLayout(context) {
    init {
        orientation = HORIZONTAL
        isMotionEventSplittingEnabled = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (childCount != 5) return
        val widths = BottomRowGeometry.widths((measuredWidth - paddingLeft - paddingRight).coerceAtLeast(0))
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            child.measure(
                MeasureSpec.makeMeasureSpec(widths[index], MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(child.measuredHeight, MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (childCount != 5) {
            super.onLayout(changed, left, top, right, bottom)
            return
        }
        var x = paddingLeft
        val indices = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) 4 downTo 0 else 0..4
        for (index in indices) {
            val child = getChildAt(index)
            child.layout(x, paddingTop, x + child.measuredWidth, paddingTop + child.measuredHeight)
            x += child.measuredWidth
        }
    }
}
