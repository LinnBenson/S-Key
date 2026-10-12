package io.secure.keyboard.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.roundToInt

/** Two small, instance-owned Canvas surfaces; no bitmaps, global listeners, or Activity cache. */
internal class ColorPickerView(context: Context, val component: Component) : View(context) {
    enum class Component { SATURATION_VALUE, HUE }

    var onChange: ((hue: Float, saturation: Float, value: Float, finished: Boolean) -> Unit)? = null
    private var hue = 0f
    private var saturation = 0f
    private var value = 0f
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private var gradient: LinearGradient? = null
    private var shade: LinearGradient? = null
    private val inset = dp(10).toFloat()

    init {
        minimumWidth = dp(48)
        minimumHeight = dp(48)
        isFocusable = true
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        updateDescription()
    }

    fun setSelection(hue: Float, saturation: Float, value: Float) {
        val nextHue = ColorPickerState.wrapHue(hue)
        val hueChanged = nextHue != this.hue
        this.hue = nextHue
        this.saturation = ColorPickerState.unit(saturation)
        this.value = ColorPickerState.unit(value)
        if (hueChanged && component == Component.SATURATION_VALUE) rebuildGradient()
        updateDescription()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = if (component == Component.HUE) dp(56) else dp(204)
        setMeasuredDimension(resolveSize(dp(260), widthMeasureSpec), resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val halfHeight = h / 2f
        if (component == Component.HUE) bounds.set(inset, halfHeight - dp(12), w - inset, halfHeight + dp(12))
        else bounds.set(inset, inset, w - inset, h - inset)
        rebuildGradient(true)
    }

    private fun rebuildGradient(sizeChanged: Boolean = false) {
        if (bounds.width() <= 0f || bounds.height() <= 0f) return
        gradient = if (component == Component.HUE) {
            LinearGradient(bounds.left, 0f, bounds.right, 0f,
                intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED),
                null, Shader.TileMode.CLAMP)
        } else {
            LinearGradient(bounds.left, 0f, bounds.right, 0f, Color.WHITE,
                ColorPickerState.hsvToColor(hue, 1f, 1f), Shader.TileMode.CLAMP)
        }
        // Size-only shading is reused when just hue changes.
        if (component == Component.SATURATION_VALUE && (sizeChanged || shade == null)) {
            shade = LinearGradient(0f, bounds.top, 0f, bounds.bottom, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        }
        gradientPaint.shader = gradient
        shadePaint.shader = shade
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bounds.isEmpty) return
        canvas.drawRect(bounds, gradientPaint)
        if (component == Component.SATURATION_VALUE) canvas.drawRect(bounds, shadePaint)
        markerPaint.style = Paint.Style.STROKE
        markerPaint.strokeWidth = dp(1).toFloat()
        markerPaint.color = SettingsPalette.outline
        canvas.drawRect(bounds, markerPaint)
        val x = bounds.left + bounds.width() * if (component == Component.HUE) hue / 360f else saturation
        val y = if (component == Component.HUE) bounds.centerY() else bounds.top + bounds.height() * (1f - value)
        markerPaint.strokeWidth = dp(3).toFloat()
        markerPaint.color = Color.WHITE
        canvas.drawCircle(x, y, dp(7).toFloat(), markerPaint)
        markerPaint.strokeWidth = dp(1).toFloat()
        markerPaint.color = SettingsPalette.ink
        canvas.drawCircle(x, y, dp(9).toFloat(), markerPaint)
        canvas.drawCircle(x, y, dp(5).toFloat(), markerPaint)
        if (hasFocus()) {
            markerPaint.strokeWidth = dp(2).toFloat()
            markerPaint.color = SettingsPalette.accent
            canvas.drawRect(1f, 1f, width - 1f, height - 1f, markerPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || bounds.width() <= 0 || bounds.height() <= 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = event.getPointerId(0)
                parent?.requestDisallowInterceptTouchEvent(true)
                isPressed = true
                requestFocus()
                updateAt(event.x, event.y, false)
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (index < 0) endGesture(false) else updateAt(event.getX(index), event.getY(index), false)
            }
            MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == pointerId) endGesture(true)
            MotionEvent.ACTION_UP -> {
                val index = event.findPointerIndex(pointerId)
                if (index >= 0) updateAt(event.getX(index), event.getY(index), false)
                endGesture(true)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> endGesture(false)
        }
        return true
    }

    private fun updateAt(x: Float, y: Float, finished: Boolean) {
        if (component == Component.HUE) hue = ColorPickerState.wrapHue(((x - bounds.left) / bounds.width()).coerceIn(0f, 1f) * 360f)
        else {
            saturation = ((x - bounds.left) / bounds.width()).coerceIn(0f, 1f)
            value = 1f - ((y - bounds.top) / bounds.height()).coerceIn(0f, 1f)
        }
        emit(finished)
    }

    private fun emit(finished: Boolean) {
        updateDescription()
        invalidate()
        onChange?.invoke(hue, saturation, value, finished)
        if (finished) announceForAccessibility(contentDescription)
    }

    private fun endGesture(finished: Boolean) {
        if (pointerId != MotionEvent.INVALID_POINTER_ID && finished) emit(true)
        pointerId = MotionEvent.INVALID_POINTER_ID
        isPressed = false
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val step = if (event.isShiftPressed) 10f else 1f
        if (component == Component.HUE) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> hue = ColorPickerState.wrapHue(hue + step)
                KeyEvent.KEYCODE_DPAD_LEFT -> hue = ColorPickerState.wrapHue(hue - step)
                else -> return super.onKeyDown(keyCode, event)
            }
        } else {
            val beforeSaturation = saturation
            val beforeValue = value
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> saturation = (saturation + step / 100f).coerceAtMost(1f)
                KeyEvent.KEYCODE_DPAD_LEFT -> saturation = (saturation - step / 100f).coerceAtLeast(0f)
                KeyEvent.KEYCODE_DPAD_UP -> value = (value + step / 100f).coerceAtMost(1f)
                KeyEvent.KEYCODE_DPAD_DOWN -> value = (value - step / 100f).coerceAtLeast(0f)
                else -> return super.onKeyDown(keyCode, event)
            }
            if (beforeSaturation == saturation && beforeValue == value) return super.onKeyDown(keyCode, event)
        }
        emit(true)
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (component == Component.HUE) {
            info.className = "android.widget.SeekBar"
            info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0f, 359f, hue.coerceAtMost(359f))
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (component == Component.HUE && isEnabled) {
            val next = when (action) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> hue + 1f
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> hue - 1f
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id -> arguments?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE) ?: return false
                else -> return super.performAccessibilityAction(action, arguments)
            }
            hue = ColorPickerState.wrapHue(next)
            emit(true)
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }

    private fun updateDescription() {
        contentDescription = if (component == Component.HUE) "色相 ${hue.roundToInt()} 度，可拖动或按方向键调整"
        else "饱和度 ${(saturation * 100).roundToInt()}%，明度 ${(value * 100).roundToInt()}%。横向调整饱和度，纵向调整明度；也可使用下方精确调整或 HEX 输入"
    }

    /** Explicit disposal also handles a dialog that never attached. */
    fun close() {
        endGesture(false)
        onChange = null
        gradientPaint.shader = null
        shadePaint.shader = null
        gradient = null
        shade = null
    }

    override fun onDetachedFromWindow() { close(); super.onDetachedFromWindow() }

    private fun dp(value: Int) = SettingsPalette.dp(context, value)
}
