package io.secure.keyboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.widget.TextView

/** Compensates the empty right side of full-width punctuation without moving its hit area. */
internal class CornerHintLabel(context: Context) : TextView(context) {
    private val inkBounds = Rect()

    override fun onDraw(canvas: Canvas) {
        val glyph = text.toString()
        if (glyph.length != 1 || glyph[0] !in "，。、：；！？") {
            super.onDraw(canvas)
            return
        }
        paint.getTextBounds(glyph, 0, glyph.length, inkBounds)
        val shift = (paint.measureText(glyph) - inkBounds.right)
            .coerceIn(0f, paint.textSize * .5f)
        val saved = canvas.save()
        canvas.translate(shift, 0f)
        super.onDraw(canvas)
        canvas.restoreToCount(saved)
    }
}
