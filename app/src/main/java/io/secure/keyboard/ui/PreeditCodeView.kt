package io.secure.keyboard.ui

import android.content.Context
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView

/** Native raw-code label that fits vertically inside the existing, fixed 18dp row. */
class PreeditCodeView(context: Context) : TextView(context) {
    private val sizingPaint = TextPaint()
    private var lastRequestedSize = Float.NaN
    private var lastAvailableHeight = -1
    private var fittedSize = Float.NaN

    init {
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        // Keep the complete font envelope, including descenders. The row itself never grows.
        includeFontPadding = true
        setSingleLine(true)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val requested = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics,
        )
        // One physical pixel at each edge protects antialiased ink from integer rounding.
        val available = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED)
            Int.MAX_VALUE
        else (MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom - 2).coerceAtLeast(1)
        // Text edits and width changes reuse the fit; typing must not repeat font-metric searches.
        if (requested != lastRequestedSize || available != lastAvailableHeight ||
            sizingPaint.typeface != paint.typeface || sizingPaint.flags != paint.flags ||
            sizingPaint.textScaleX != paint.textScaleX || sizingPaint.textSkewX != paint.textSkewX ||
            sizingPaint.isElegantTextHeight != paint.isElegantTextHeight ||
            sizingPaint.fontVariationSettings != paint.fontVariationSettings) {
            sizingPaint.set(paint)
            fittedSize = requested
            fun fits(size: Float): Boolean {
                sizingPaint.textSize = size
                val metrics = sizingPaint.fontMetricsInt
                return metrics.bottom - metrics.top <= available
            }
            if (!fits(requested)) {
                var low = 0f
                var high = requested
                repeat(16) {
                    val middle = (low + high) / 2f
                    if (fits(middle)) low = middle else high = middle
                }
                fittedSize = low
            }
            lastRequestedSize = requested
            lastAvailableHeight = available
        }
        // Height alone limits the size: a long code must not become microscopic to fit its width.
        if (textSize != fittedSize) setTextSize(TypedValue.COMPLEX_UNIT_PX, fittedSize)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
