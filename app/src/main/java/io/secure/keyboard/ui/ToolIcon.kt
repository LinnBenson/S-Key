package io.secure.keyboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Small deterministic line icons: no icon-font or emoji rendering dependency. */
class ToolIcon
@JvmOverloads
constructor(
    context: Context,
    private val kind: String = "more",
    var tint: Int = android.graphics.Color.BLACK,
    private val artworkSizeDp: Float = 26f,
) : View(context) {
    init {
        // Cursor DPAD events can leave touch mode and focus the first toolbar control. Android's
        // default focus drawable then paints a solid, theme-external gray block behind this icon.
        // Keep keyboard/accessibility focus and click semantics, but leave the toolbar background
        // to the keyboard palette instead of the framework's default focus highlight.
        defaultFocusHighlightEnabled = false
    }

    private val p =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.8f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    private val iconPath = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        p.color = tint
        canvas.save()
        val density = resources.displayMetrics.density
        val size = minOf(artworkSizeDp * density, (width - 8f * density).coerceAtLeast(12f * density))
        canvas.translate((width - size) / 2, (height - size) / 2)
        canvas.scale(size / 24, size / 24)
        fun line(a: Float, b: Float, c: Float, d: Float) = canvas.drawLine(a, b, c, d, p)
        fun rect(a: Float, b: Float, c: Float, d: Float) =
            canvas.drawRoundRect(a, b, c, d, 2f, 2f, p)
        when (kind) {
            "globe" -> {
                canvas.drawCircle(12f, 12f, 9f, p)
                canvas.drawOval(8f, 3f, 16f, 21f, p)
                line(3f, 12f, 21f, 12f)
            }
            "enter" -> {
                val path =
                    iconPath.apply {
                        rewind()
                        moveTo(20f, 5f)
                        lineTo(20f, 11f)
                        quadTo(20f, 14f, 17f, 14f)
                        lineTo(4f, 14f)
                        moveTo(9f, 9f)
                        lineTo(4f, 14f)
                        lineTo(9f, 19f)
                    }
                canvas.drawPath(path, p)
            }
            "shift",
            "caps" -> {
                val path =
                    iconPath.apply {
                        rewind()
                        moveTo(4f, 11f)
                        lineTo(12f, 3f)
                        lineTo(20f, 11f)
                        lineTo(16f, 11f)
                        lineTo(16f, 18f)
                        lineTo(8f, 18f)
                        lineTo(8f, 11f)
                        close()
                    }
                canvas.drawPath(path, p)
                if (kind == "caps") line(8f, 22f, 16f, 22f)
            }
            "backspace" -> {
                val path =
                    iconPath.apply {
                        rewind()
                        moveTo(8f, 5f)
                        lineTo(21f, 5f)
                        lineTo(21f, 19f)
                        lineTo(8f, 19f)
                        lineTo(2f, 12f)
                        close()
                    }
                canvas.drawPath(path, p)
                line(11f, 9f, 17f, 15f)
                line(17f, 9f, 11f, 15f)
            }
            "more" -> {
                for (i in 0..1) for (j in 0..1) {
                    val x = 3f + i * 11f
                    val y = 3f + j * 11f
                    rect(x, y, x + 7, y + 7)
                }
            }
            "hide" -> {
                line(5f, 9f, 12f, 16f)
                line(12f, 16f, 19f, 9f)
            }
            "back" -> {
                line(14f, 5f, 7f, 12f)
                line(7f, 12f, 14f, 19f)
            }
            "clear" -> {
                line(6f, 6f, 18f, 18f)
                line(18f, 6f, 6f, 18f)
            }
            "edit" -> {
                val path =
                    iconPath.apply {
                        rewind()
                        moveTo(4f, 16f)
                        lineTo(15f, 5f)
                        lineTo(19f, 9f)
                        lineTo(8f, 20f)
                        lineTo(3f, 21f)
                        close()
                    }
                canvas.drawPath(path, p)
                line(13f, 7f, 17f, 11f)
                line(15f, 5f, 17f, 3f)
                line(17f, 3f, 21f, 7f)
                line(21f, 7f, 19f, 9f)
            }
            "pin",
            "unpin" -> {
                val path =
                    iconPath.apply {
                        rewind()
                        moveTo(8f, 3f)
                        lineTo(16f, 3f)
                        moveTo(9f, 3f)
                        lineTo(9f, 10f)
                        lineTo(6f, 14f)
                        lineTo(18f, 14f)
                        lineTo(15f, 10f)
                        lineTo(15f, 3f)
                        moveTo(12f, 14f)
                        lineTo(12f, 22f)
                    }
                canvas.drawPath(path, p)
                if (kind == "unpin") line(3f, 3f, 21f, 21f)
            }
            "delete" -> {
                line(4f, 6f, 20f, 6f)
                line(9f, 3f, 15f, 3f)
                line(9f, 3f, 8f, 6f)
                line(15f, 3f, 16f, 6f)
                val path =
                    iconPath.apply {
                        rewind()
                        moveTo(6f, 6f)
                        lineTo(7f, 21f)
                        lineTo(17f, 21f)
                        lineTo(18f, 6f)
                    }
                canvas.drawPath(path, p)
                line(10f, 10f, 10.5f, 17f)
                line(14f, 10f, 13.5f, 17f)
            }
            "clipboard" -> {
                rect(5f, 5f, 19f, 22f)
                rect(9f, 2f, 15f, 7f)
                line(9f, 11f, 15f, 11f)
                line(9f, 15f, 15f, 15f)
            }
            "phrases" -> {
                rect(5f, 2f, 19f, 22f)
                for (i in 0..2) {
                    val y = 7f + i * 5f
                    line(9f, y, 15f, y)
                }
            }
            "voice" -> {
                rect(9f, 2f, 15f, 15f)
                canvas.drawArc(5f, 4f, 19f, 19f, 0f, 180f, false, p)
                line(12f, 19f, 12f, 23f)
            }
            "emoji" -> {
                canvas.drawCircle(12f, 12f, 9f, p)
                canvas.drawCircle(8.5f, 9f, .7f, p)
                canvas.drawCircle(15.5f, 9f, .7f, p)
                canvas.drawArc(7f, 10f, 17f, 17f, 0f, 180f, false, p)
            }
            "recent" -> {
                canvas.drawCircle(12f, 12f, 9f, p)
                line(12f, 6f, 12f, 12f)
                line(12f, 12f, 16f, 14f)
            }
            "drag_handle" -> {
                val previous = p.strokeWidth
                p.strokeWidth = 3f
                line(6f, 12f, 18f, 12f)
                p.strokeWidth = previous
            }
            "resize" -> {
                line(5f, 19f, 19f, 5f)
                line(13f, 5f, 19f, 5f)
                line(19f, 5f, 19f, 11f)
                line(5f, 13f, 5f, 19f)
                line(5f, 19f, 11f, 19f)
            }
            "quickedit" -> {
                // Selected C text-and-pencil SVG; retain the palette tint and view bounds.
                val previous = p.strokeWidth
                p.strokeWidth = 1.7f
                val path = iconPath.apply {
                    rewind()
                    moveTo(3f, 5f)
                    lineTo(17f, 5f)
                    moveTo(3f, 10f)
                    lineTo(12f, 10f)
                    moveTo(3f, 15f)
                    lineTo(7f, 15f)
                    moveTo(11f, 21f)
                    lineTo(12f, 17f)
                    lineTo(18.7f, 10.3f)
                    // Radius-1.3 endpoint arcs from the source SVG, converted to circle bounds.
                    arcTo(18.3f, 9.938083f, 20.9f, 12.538083f, -133.81306f, 87.62612f, false)
                    lineTo(21.4f, 11.2f)
                    arcTo(19.161917f, 10.8f, 21.761917f, 13.4f, -43.81306f, 87.62612f, false)
                    lineTo(15f, 20f)
                    lineTo(11f, 21f)
                    close()
                    moveTo(17.5f, 11.5f)
                    lineTo(20.2f, 14.2f)
                }
                canvas.drawPath(path, p)
                p.strokeWidth = previous
            }
            "arrow_up",
            "arrow_down",
            "arrow_left",
            "arrow_right" -> {
                val angle =
                    when (kind) {
                        "arrow_down" -> 180f
                        "arrow_left" -> -90f
                        "arrow_right" -> 90f
                        else -> 0f
                    }
                canvas.rotate(angle, 12f, 12f)
                line(12f, 20f, 12f, 4f)
                line(5f, 11f, 12f, 4f)
                line(12f, 4f, 19f, 11f)
            }
            "undo" -> {
                val path =
                    iconPath.apply {
                        rewind()
                        moveTo(4f, 11f)
                        cubicTo(9f, 5f, 20f, 7f, 20f, 14f)
                        cubicTo(20f, 18f, 17f, 20f, 12f, 20f)
                        moveTo(4f, 5f)
                        lineTo(4f, 11f)
                        lineTo(10f, 11f)
                    }
                canvas.drawPath(path, p)
            }
            "layout" -> {
                line(3f, 8f, 3f, 3f)
                line(3f, 3f, 8f, 3f)
                line(16f, 3f, 21f, 3f)
                line(21f, 3f, 21f, 8f)
                line(3f, 16f, 3f, 21f)
                line(3f, 21f, 8f, 21f)
                line(16f, 21f, 21f, 21f)
                line(21f, 21f, 21f, 16f)
                line(8f, 8f, 16f, 16f)
                line(16f, 8f, 8f, 16f)
            }
            "floating_enter",
            "floating_exit" -> {
                // Selected A artwork, directly transcribed from its 24 × 24 SVG pair.
                // Only the drawing changes; the existing toolbar view owns sizing and touch.
                val previous = p.strokeWidth
                p.strokeWidth = 1.7f
                val exiting = kind == "floating_exit"
                val top = if (exiting) 3f else 12f
                canvas.drawRoundRect(4f, top, 20f, top + 9f, 1.8f, 1.8f, p)
                for (x in floatArrayOf(7f, 10.3f, 13.7f, 17f))
                    line(x, top + 3f, x + .01f, top + 3f)
                line(9f, top + 6f, 15f, top + 6f)
                val path = iconPath.apply {
                    rewind()
                    if (exiting) {
                        moveTo(12f, 14.5f)
                        lineTo(12f, 19.5f)
                        moveTo(9f, 17f)
                        lineTo(12f, 20f)
                        lineTo(15f, 17f)
                        moveTo(3f, 22f)
                        lineTo(21f, 22f)
                    } else {
                        moveTo(12f, 9f)
                        lineTo(12f, 3f)
                        moveTo(9f, 6f)
                        lineTo(12f, 3f)
                        lineTo(15f, 6f)
                    }
                }
                canvas.drawPath(path, p)
                p.strokeWidth = previous
            }
            "split" -> {
                rect(1f, 5f, 9f, 19f)
                rect(15f, 5f, 23f, 19f)
            }
            "dictionary", "english_dictionary" -> {
                val path =
                    iconPath.apply {
                        rewind()
                        moveTo(12f, 5f)
                        quadTo(7f, 2f, 2f, 4f)
                        lineTo(2f, 20f)
                        quadTo(7f, 18f, 12f, 21f)
                        quadTo(17f, 18f, 22f, 20f)
                        lineTo(22f, 4f)
                        quadTo(17f, 2f, 12f, 5f)
                    }
                canvas.drawPath(path, p)
                line(12f, 5f, 12f, 21f)
                if (kind == "english_dictionary") {
                    // Native strokes keep the English book identifiable without a font glyph.
                    val previousWidth = p.strokeWidth
                    p.strokeWidth = 1.25f
                    line(15f, 15f, 17.5f, 8f)
                    line(17.5f, 8f, 20f, 15f)
                    line(16f, 12.5f, 19f, 12.5f)
                    p.strokeWidth = previousWidth
                }
            }
            "theme" -> {
                canvas.drawCircle(12f, 12f, 9f, p)
                p.style = Paint.Style.FILL
                canvas.drawArc(3f, 3f, 21f, 21f, 90f, 180f, true, p)
                p.style = Paint.Style.STROKE
            }
            "settings" -> {
                canvas.drawCircle(12f, 12f, 7f, p)
                canvas.drawCircle(12f, 12f, 2.5f, p)
                for (i in 0..7) {
                    canvas.save()
                    canvas.rotate(i * 45f, 12f, 12f)
                    line(12f, 2f, 12f, 5f)
                    canvas.restore()
                }
            }
            else -> canvas.drawCircle(12f, 12f, 8f, p)
        }
        canvas.restore()
    }
}
