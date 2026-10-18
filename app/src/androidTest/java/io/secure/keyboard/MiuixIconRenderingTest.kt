package io.secure.keyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.test.InstrumentationTestCase
import io.secure.keyboard.ui.KeyboardIcons

/** Render all shared icons: invalid paths and transforms can compile but draw blank. */
@Suppress("DEPRECATION")
class MiuixIconRenderingTest : InstrumentationTestCase() {
    fun testResizeOptionsPreview() {
        val bitmap = Bitmap.createBitmap(960, 360, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.rgb(239,239,241))
        val paint = android.graphics.Paint(3).apply { color = android.graphics.Color.rgb(101,120,142); textSize = 30f; textAlign = android.graphics.Paint.Align.CENTER }
        for (i in 0..2) {
            val x = 160f + i * 320f
            canvas.drawText(('A'+i).toString(), x, 48f, paint)
            if (i < 2) {
                val drawable = instrumentation.targetContext.getDrawable(if (i == 0) io.secure.keyboard.R.drawable.ic_keyboard_resize_diagonal else io.secure.keyboard.R.drawable.ic_keyboard_open_in_full)!!.mutate()
                drawable.setTint(paint.color)
                drawable.setBounds((x-44).toInt(), 90, (x+44).toInt(), 178)
                drawable.draw(canvas)
                drawable.setBounds((x-18).toInt(), 255, (x+18).toInt(), 291)
                drawable.draw(canvas)
            } else {
                val path = androidx.core.graphics.PathParser.createPathFromPathData("M200-120q-33 0-56.5-23.5T120-200v-560q0-33 23.5-56.5T200-840h240q17 0 28.5 11.5T480-800q0 17-11.5 28.5T440-760H200v560h560v-240q0-17 11.5-28.5T800-480q17 0 28.5 11.5T840-440v240q0 33-23.5 56.5T760-120H200Zm560-584L416-360q-11 11-28 11t-28-11q-11-11-11-28t11-28l344-344H600q-17 0-28.5-11.5T560-800q0-17 11.5-28.5T600-840h200q17 0 28.5 11.5T840-800v200q0 17-11.5 28.5T800-560q-17 0-28.5-11.5T760-600v-104Z")!!
                for ((top, size) in listOf(90f to 88f, 255f to 36f)) {
                    canvas.save(); canvas.translate(x-size/2, top); canvas.scale(size/960f, size/960f); canvas.translate(0f,960f)
                    canvas.drawPath(path, paint); canvas.restore()
                }
            }
        }
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "resize-options.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }

    fun testCompactGripRightAlignment() {
        instrumentation.runOnMainSync {
            val density = instrumentation.targetContext.resources.displayMetrics.density
            for (widthDp in listOf(40, 72)) {
                val w = (widthDp*density).toInt(); val h = (32*density).toInt()
                val icon = io.secure.keyboard.ui.ToolIcon(instrumentation.targetContext, "resize",
                    artworkSizeDp = 14f, artworkRightInsetDp = 10f, opticalOffsetYFraction = -0.13f)
                icon.layout(0, 0, w, h)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                icon.draw(Canvas(bitmap))
                var right = 0; var mass = 0.0; var weightedY = 0.0
                for (y in 0 until h) for (x in 0 until w) {
                    val alpha = bitmap.getPixel(x,y) ushr 24
                    if (alpha >= 32) right = maxOf(right, x+1)
                    mass += alpha; weightedY += (y+0.5)*alpha
                }
                assertTrue("right inset", kotlin.math.abs(w-right-10*density) <= 2f)
                assertTrue("optical alignment with drag bar", kotlin.math.abs(weightedY/mass-h/2.0) <= 1.5)
                bitmap.recycle()
            }
        }
    }

    fun testIconsAdaptToLiveCellResize() {
        val density = instrumentation.targetContext.resources.displayMetrics.density
        for (kind in listOf("shift", "backspace", "globe", "enter", "floating_exit", "hide", "resize", "drag_handle")) {
            instrumentation.runOnMainSync {
                val icon = io.secure.keyboard.ui.ToolIcon(instrumentation.targetContext, kind)
                // Reuse the view: resizing must update the matrix in both directions.
                for (sizeDp in listOf(48, 24, 16, 48)) {
                    val size = (sizeDp * density).toInt()
                    icon.layout(0, 0, size, size)
                    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                    icon.draw(Canvas(bitmap))
                    var left = size; var top = size; var right = -1; var bottom = -1
                    for (y in 0 until size) for (x in 0 until size) {
                        if (bitmap.getPixel(x, y) ushr 24 >= 32) {
                            left = minOf(left, x); right = maxOf(right, x)
                            top = minOf(top, y); bottom = maxOf(bottom, y)
                        }
                    }
                    val expected = minOf(24f * density, size * 0.6f) * (if (kind == "hide") 18f else 20f) / 24f
                    assertTrue("$kind $sizeDp size", kotlin.math.abs(maxOf(right-left+1, bottom-top+1)-expected) <= 2f)
                    assertTrue("$kind center x", kotlin.math.abs((left+right+1)/2f-size/2f) <= 1.5f)
                    assertTrue("$kind center y", kotlin.math.abs((top+bottom+1)/2f-size/2f) <= 1.5f)
                    bitmap.recycle()
                }
            }
        }
    }

    fun testToolbarOpticalAlignment() {
        val kinds = listOf("more", "floating_enter", "clipboard", "quickedit", "emoji", "voice", "hide", "floating_exit", "clear")
        val preview = Bitmap.createBitmap(1008, 144, Bitmap.Config.ARGB_8888)
        val previewCanvas = Canvas(preview)
        previewCanvas.drawColor(android.graphics.Color.rgb(239,239,241))
        for ((index, kind) in kinds.withIndex()) {
            val bitmap = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888)
            instrumentation.runOnMainSync {
                val icon = io.secure.keyboard.ui.ToolIcon(instrumentation.targetContext, kind, android.graphics.Color.rgb(23,40,62))
                icon.measure(android.view.View.MeasureSpec.makeMeasureSpec(144, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(144, android.view.View.MeasureSpec.EXACTLY))
                icon.layout(0, 0, 144, 144)
                icon.draw(Canvas(bitmap))
            }
            var left = 144; var top = 144; var right = -1; var bottom = -1
            for (y in 0..143) for (x in 0..143) if (bitmap.getPixel(x,y) ushr 24 >= 32) {
                left = minOf(left,x); right = maxOf(right,x); top = minOf(top,y); bottom = maxOf(bottom,y)
            }
            assertTrue("$kind horizontal center", kotlin.math.abs((left+right+1)/2f - 72f) <= 1.5f)
            assertTrue("$kind vertical center", kotlin.math.abs((top+bottom+1)/2f - 72f) <= 1.5f)
            val expected = instrumentation.targetContext.resources.displayMetrics.density * (when (kind) { "clear" -> 16; "hide" -> 18; else -> 20 })
            assertTrue("$kind visible size", kotlin.math.abs(maxOf(right-left+1,bottom-top+1)-expected) <= 2f)
            if (index < 7) previewCanvas.drawBitmap(bitmap,index*144f,0f,null)
            bitmap.recycle()
        }
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "toolbar-aligned.png").outputStream().use {
            preview.compress(Bitmap.CompressFormat.PNG,100,it)
        }
        preview.recycle()
    }
    fun testRenderFloatingOptions() {
        val bitmap = Bitmap.createBitmap(1120, 430, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.rgb(239,239,241))
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(23,40,62); textSize = 26f; textAlign = android.graphics.Paint.Align.CENTER
        }
        canvas.drawText("开启悬浮",90f,150f,paint)
        canvas.drawText("恢复停靠",90f,335f,paint)
        for (i in 0..3) {
            val center = 280+i*230
            canvas.drawText(('A'+i).toString(),center.toFloat(),45f,paint)
            for (dock in listOf(false,true)) {
                val context = if (i == 0) instrumentation.targetContext else instrumentation.context
                val resource = if (i == 0) KeyboardIcons.resource(if (dock) "floating_exit" else "floating_enter") else
                    context.resources.getIdentifier("floating_option_" + ('a' + i) + (if (dock) "_dock" else "_float"), "drawable", context.packageName)
                val icon = context.getDrawable(resource)!!.mutate()
                icon.setTint(paint.color)
                val top = if (dock) 275 else 90
                icon.setBounds(center-42,top,center+42,top+84); icon.draw(canvas)
            }
        }
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "floating-options.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG,100,it)
        }
        bitmap.recycle()
    }
    fun testSaveSemanticIconPreview() {
        val labels = listOf("globe", "enter", "shift", "caps", "backspace", "more", "hide", "back", "forward", "clear", "edit", "pin", "unpin", "delete", "clipboard", "phrases", "voice", "emoji", "recent", "drag_handle", "resize", "quickedit", "arrow_up", "arrow_down", "arrow_left", "arrow_right", "undo", "layout", "floating", "floating_enter", "floating_exit", "split", "dictionary", "english_dictionary", "theme", "settings", "input", "keyboard", "check").associateWith { it }
        val bitmap = Bitmap.createBitmap(1050, 1200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(23, 40, 62); textSize = 18f; textAlign = android.graphics.Paint.Align.CENTER
        }
        labels.entries.forEachIndexed { i, (kind, label) ->
            val x = (i % 5) * 210; val y = (i / 5) * 150
            val icon = instrumentation.targetContext.getDrawable(KeyboardIcons.resource(kind))!!.mutate()
            icon.setTint(paint.color); icon.setBounds(x+77, y+22, x+133, y+78); icon.draw(canvas)
            canvas.drawText(label, x+105f, y+112f, paint)
        }
        val file = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "semantic-icons.png")
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
    fun testAllMiuixIconsRenderVisibleArtwork() {
        val kinds = listOf("globe", "enter", "shift", "caps", "backspace", "more", "hide", "back", "forward", "clear", "edit", "pin", "unpin", "delete", "clipboard", "phrases", "voice", "emoji", "recent", "drag_handle", "resize", "quickedit", "arrow_up", "arrow_down", "arrow_left", "arrow_right", "undo", "layout", "floating", "floating_enter", "floating_exit", "split", "dictionary", "english_dictionary", "theme", "settings", "input", "keyboard", "check")
        for (kind in kinds) {
            val drawable = instrumentation.targetContext.getDrawable(KeyboardIcons.resource(kind))!!
            val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, 96, 96)
            drawable.draw(Canvas(bitmap))
            val pixels = IntArray(96 * 96)
            bitmap.getPixels(pixels, 0, 96, 0, 0, 96, 96)
            val visible = pixels.count { (it ushr 24) > 0 }
            assertTrue("$kind is empty or fills its entire bounds: $visible", visible in 50..8000)
            bitmap.recycle()
        }
    }
}
