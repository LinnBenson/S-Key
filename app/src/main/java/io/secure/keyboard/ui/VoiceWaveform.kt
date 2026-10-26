package io.secure.keyboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/** Sample-driven bars: silence stays still, with no background animation or audio storage. */
class VoiceWaveform(context: Context, color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val levels = FloatArray(17)
    private val expireLevel = Runnable { reset() }
    fun setLevel(level: Float) {
        removeCallbacks(expireLevel)
        postDelayed(expireLevel, 500)
        for (i in 0 until levels.lastIndex) levels[i] = levels[i + 1]
        levels[levels.lastIndex] = level.coerceIn(0f, 1f)
        invalidate()
    }
    fun reset() { removeCallbacks(expireLevel); levels.fill(0f); invalidate() }
    override fun onDetachedFromWindow() {
        reset()
        super.onDetachedFromWindow()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val span = minOf(width * .72f, 240 * density)
        val step = span / levels.size
        val barWidth = minOf(6 * density, step * .5f)
        val maxHeight = minOf(height * .8f, 72 * density)
        for (i in levels.indices) {
            val barHeight = maxOf(barWidth, maxHeight * levels[i])
            val x = (width - span) / 2 + (i + .5f) * step
            canvas.drawRoundRect(x - barWidth / 2, (height - barHeight) / 2,
                x + barWidth / 2, (height + barHeight) / 2, barWidth / 2, barWidth / 2, paint)
        }
    }
}
