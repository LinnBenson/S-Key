package io.secure.keyboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.view.Gravity
import android.widget.TextView

/** Centers punctuation ink at a chosen position, independent of advance box and font baseline. */
class PunctuationKeyLabel(context: Context) : TextView(context) {
    private val inkBounds = Rect()
    var inkCenterYFraction: Float = .5f
        set(value) {
            field = value
            invalidate()
        }

    init {
        gravity = Gravity.CENTER
        includeFontPadding = false
        setSingleLine(true)
    }

    override fun onDraw(canvas: Canvas) {
        // Keep TextView's text, sizing, colors and accessibility semantics. Both symbols in
        // the combined punctuation key use this renderer, with equal sizes and stacked ink.
        val glyph = text.toString()
        if (glyph.isEmpty()) return
        paint.color = currentTextColor
        paint.drawableState = drawableState
        paint.getTextBounds(glyph, 0, glyph.length, inkBounds)
        if (inkBounds.isEmpty) return
        // Attached single-line TextView can scroll its wide internal text layout during
        // pre-draw. View draws us in that scrolled coordinate system, so center the glyph
        // in the visible viewport, not at the (potentially far-offscreen) content origin.
        val centerX = scrollX + paddingLeft + (width - paddingLeft - paddingRight) / 2f
        val centerY = scrollY + paddingTop +
            (height - paddingTop - paddingBottom) * inkCenterYFraction
        canvas.drawText(
            glyph,
            centerX - inkBounds.exactCenterX(),
            centerY - inkBounds.exactCenterY(),
            paint,
        )
    }
}
