package io.secure.keyboard.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.widget.ImageView

/** Official vector artwork aligned by visible bounds, rather than differing source viewports. */
class ToolIcon @JvmOverloads constructor(
    context: Context,
    private var kind: String = "more",
    tint: Int = android.graphics.Color.BLACK,
    private val artworkSizeDp: Float = 24f,
    private val artworkRightInsetDp: Float? = null,
    private val opticalOffsetYFraction: Float = 0f,
) : ImageView(context) {
    private var resource = KeyboardIcons.resource(kind)
    var tint: Int = tint
        set(value) { field = value; imageTintList = ColorStateList.valueOf(value) }
    init {
        defaultFocusHighlightEnabled = false
        scaleType = ScaleType.MATRIX
        setImageResource(resource)
        imageTintList = ColorStateList.valueOf(tint)
    }
    fun setArtwork(value: String) {
        if (kind == value) return
        kind = value
        resource = KeyboardIcons.resource(value)
        setImageResource(resource)
        onSizeChanged(width, height, width, height)
        invalidate()
    }

    private val scriptPaint = if (kind == "script" || kind == "script_traditional" || kind.startsWith("mode_"))
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        } else null
    override fun onDraw(canvas: Canvas) {
        if (kind == "script" || kind == "script_traditional" || kind.startsWith("mode_")) {
            val paint = checkNotNull(scriptPaint).apply {
                color = tint
                textSize = minOf(20f * resources.displayMetrics.density, minOf(width, height) * 0.6f)
            }
            canvas.drawText(if (kind.startsWith("mode_")) io.secure.keyboard.engine.ChineseInputMode.fromId(kind.removePrefix("mode_")).glyph else if (kind == "script_traditional") "繁" else "简", width / 2f,
                height / 2f - (paint.ascent() + paint.descent()) / 2f, paint)
        } else super.onDraw(canvas)
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val artwork = drawable ?: return
        // Both Shift states use the locked artwork's bounds, so the arrow never
        // changes scale or position when the underline appears.
        val geometryResource = if (kind == "shift" || kind == "caps") KeyboardIcons.resource("caps") else resource
        val bounds = visibleBounds.getOrPut(geometryResource) {
            // Measure once per resource; tint and theme changes never invalidate the geometry.
            val sample = context.getDrawable(geometryResource)!!.mutate()
            val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            sample.setBounds(0, 0, 96, 96)
            sample.draw(Canvas(bitmap))
            val pixels = IntArray(96 * 96)
            bitmap.getPixels(pixels, 0, 96, 0, 0, 96, 96)
            bitmap.recycle()
            var left = 96; var top = 96; var right = -1; var bottom = -1
            pixels.forEachIndexed { index, pixel ->
                if (pixel ushr 24 >= 32) {
                    val x = index % 96; val y = index / 96
                    left = minOf(left, x); right = maxOf(right, x)
                    top = minOf(top, y); bottom = maxOf(bottom, y)
                }
            }
            if (right < left) VisibleBounds(0f, 0f, 1f, 1f)
            else VisibleBounds(left / 96f, top / 96f, (right + 1) / 96f, (bottom + 1) / 96f)
        }
        val iw = artwork.intrinsicWidth.toFloat()
        val ih = artwork.intrinsicHeight.toFloat()
        // Diagonal cross strokes look larger than enclosed icons at the same bounds.
        // Keep clear compact, independently of its unchanged 42dp touch target.
        // Resize with the actual cell, including live floating-keyboard drags. Keep
        // breathing room instead of allowing fixed-size artwork to fill small keys.
        val available = minOf(w - paddingLeft - paddingRight, h - paddingTop - paddingBottom)
            .coerceAtLeast(0).toFloat()
        val nominal = minOf(artworkSizeDp * resources.displayMetrics.density, available * 0.6f)
        val target = nominal * (when (kind) { "clear" -> 16f; "hide" -> 18f; else -> 20f }) / 24f
        val scale = target / maxOf((bounds.right - bounds.left) * iw, (bounds.bottom - bounds.top) * ih)
        imageMatrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(
                artworkRightInsetDp?.let { w - it * resources.displayMetrics.density - bounds.right * iw * scale }
                    ?: (w / 2f - (bounds.left + bounds.right) * iw * scale / 2f),
                h / 2f - (bounds.top + bounds.bottom) * ih * scale / 2f + target * opticalOffsetYFraction)
        }
    }
    private data class VisibleBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)
    companion object {
        private val visibleBounds = mutableMapOf<Int, VisibleBounds>()
    }
}
