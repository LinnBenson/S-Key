package io.secure.keyboard.ui

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

/** A tap toggles selection; a four-way drag moves without toggling on release. */
internal class SelectionKeySwipe(private val step: Float, private val move: (Int) -> Unit) : View.OnTouchListener {
    private var active = false
    private var dragged = false
    private var startX = 0f
    private var startY = 0f
    private var stepsX = 0
    private var stepsY = 0

    init { require(step.isFinite() && step > 0) }

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            active = view.isEnabled && event.rawX.isFinite() && event.rawY.isFinite()
            dragged = false
            startX = event.rawX; startY = event.rawY
            stepsX = 0; stepsY = 0
            view.isPressed = active
            return true
        }
        if (!active) return true
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN || event.pointerCount != 1 || !event.rawX.isFinite() || !event.rawY.isFinite() ||
            event.actionMasked == MotionEvent.ACTION_CANCEL) {
            active = false; view.isPressed = false
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val x = ((event.rawX - startX) / step).toInt()
                val y = ((event.rawY - startY) / step).toInt()
                if (x != stepsX || y != stepsY) {
                    dragged = true
                    view.isPressed = false
                    // Bound malformed jumps; normal drags remain one native arrow per 18dp.
                    repeat(kotlin.math.abs(x.toLong() - stepsX).coerceAtMost(64).toInt()) {
                        move(if (x < stepsX) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
                    }
                    repeat(kotlin.math.abs(y.toLong() - stepsY).coerceAtMost(64).toInt()) {
                        move(if (y < stepsY) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN)
                    }
                    stepsX = x; stepsY = y
                }
            }
            MotionEvent.ACTION_UP -> {
                active = false; view.isPressed = false
                if (!dragged) view.performClick()
            }
        }
        return true
    }
}
