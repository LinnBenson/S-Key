package io.secure.keyboard.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import io.secure.keyboard.ui.KeyboardIcons

/** Shared Miuix artwork for legacy View-based navigation. */
internal class SettingsChevron(context: Context, back: Boolean = false) : Drawable() {
    private val size = SettingsPalette.dp(context, 24)
    private val artwork = context.getDrawable(KeyboardIcons.resource(if (back) "back" else "forward"))!!.mutate().apply {
        setTint(if (back) SettingsPalette.ink else SettingsPalette.secondaryText)
    }
    override fun draw(canvas: Canvas) { artwork.bounds = bounds; artwork.draw(canvas) }
    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
    override fun setAlpha(alpha: Int) { artwork.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { artwork.colorFilter = filter; invalidateSelf() }
    @Deprecated("Drawable opacity is no longer used")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
