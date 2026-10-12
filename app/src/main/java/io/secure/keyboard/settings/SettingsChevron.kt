package io.secure.keyboard.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** Application navigation only; independent of keyboard artwork and palettes. */
internal class SettingsChevron(context: Context, private val back: Boolean = false) : Drawable() {
    private val size = SettingsPalette.dp(context, 24)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (back) SettingsPalette.ink else SettingsPalette.secondaryText
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path().apply {
        moveTo(if (back) 15f else 9f, 4f)
        lineTo(if (back) 7f else 17f, 12f)
        lineTo(if (back) 15f else 9f, 20f)
    }
    override fun draw(canvas: Canvas) {
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        canvas.drawPath(path, paint)
        canvas.restore()
    }
    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Deprecated("Drawable opacity is no longer used")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
