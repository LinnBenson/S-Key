package io.secure.keyboard.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.view.View
import android.widget.LinearLayout
import java.util.IdentityHashMap

/**
 * Keeps LinearLayout's original weighted/rounded visual positions, but lets the two outer keys
 * own the otherwise empty side indents. In particular, the .45 / nine keys / .45 allocation
 * remains identical to the unexpanded row, including at odd pixel widths and in split mode.
 *
 * These are real, nonoverlapping child bounds, not a TouchDelegate. Android can consequently
 * split simultaneous pointers across keys/rows, while two fingers on an edge key (including
 * one in its indent) remain one normal multipointer stream for KeyTouchRouter.
 */
class IndentedKeyRow(context: Context) : LinearLayout(context) {
    private class Cell(val view: KeyContainer) {
        val background: Drawable? = view.background
        val leftPadding = view.paddingLeft
        val topPadding = view.paddingTop
        val rightPadding = view.paddingRight
        val bottomPadding = view.paddingBottom
        var width = 0
        var height = 0
        var leftExtension = 0
        var rightExtension = 0

        fun extend(left: Int, right: Int) {
            if (left != leftExtension || right != rightExtension) {
                view.background =
                    if (left == 0 && right == 0) background
                    else background?.let { InsetDrawable(it, left, 0, right, 0) }
                // Content and background receive exactly the same extra inset, so their
                // original screen-space geometry is untouched by the wider touch target.
                view.setPadding(
                    leftPadding + left,
                    topPadding,
                    rightPadding + right,
                    bottomPadding,
                )
                leftExtension = left
                rightExtension = right
            }
        }
    }

    private val cells = IdentityHashMap<KeyContainer, Cell>()

    init {
        orientation = HORIZONTAL
        isMotionEventSplittingEnabled = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Always let the existing spacers/weights decide integer pixel allocation. Expanded
        // padding cannot affect a weighted key's exact width or the row's exact height.
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        for (index in 0 until childCount) {
            val key = getChildAt(index) as? KeyContainer ?: continue
            val cell = cells[key] ?: Cell(key).also { cells[key] = it }
            cell.width = key.measuredWidth
            cell.height = key.measuredHeight
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        // A parent can request another layout without another measurement. Restore the
        // weighted widths first, otherwise the previous expansion could move the next key.
        val visibleCells = cells.values.filter { it.view.parent === this && it.view.visibility != View.GONE }
        visibleCells.forEach { cell -> measureCell(cell.view, cell.width, cell.height) }
        super.onLayout(changed, left, top, right, bottom)
        val first = visibleCells.minByOrNull { it.view.left } ?: return
        val last = visibleCells.maxByOrNull { it.view.right } ?: return
        visibleCells.forEach { cell ->
            val key = cell.view
            val extraLeft = if (cell === first) (key.left - paddingLeft).coerceAtLeast(0) else 0
            val extraRight = if (cell === last) (width - paddingRight - key.right).coerceAtLeast(0) else 0
            val keyLeft = key.left - extraLeft
            val keyTop = key.top
            cell.extend(extraLeft, extraRight)
            measureCell(key, cell.width + extraLeft + extraRight, cell.height)
            key.layout(keyLeft, keyTop, keyLeft + key.measuredWidth, keyTop + key.measuredHeight)
        }
    }

    private fun measureCell(key: KeyContainer, width: Int, height: Int) {
        key.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
    }
}
