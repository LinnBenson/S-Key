package io.secure.keyboard.ui

import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * ViewGroup splits fingers between different keys, but fingers on one key share a stream.
 * Preserve the existing single-finger long-press/swipe listener. Once fingers overlap on the
 * same key, cancel its hold/drag and track each tap separately, committing in release order.
 * A delete repeat or cursor swipe that already acted is never converted into an extra tap.
 * No touch is delayed, debounced, or coalesced. Accessibility still uses normal performClick.
 */
internal class KeyTouchRouter(
    private val singleTouch: View.OnTouchListener,
    private val canCompleteOriginalTap: () -> Boolean,
    private val completeTap: (Long) -> Unit,
    private val horizontalCancelDistance: Float,
    private val verticalCancelSlop: Float,
    private val onAdditionalPress: () -> Unit = {},
) : View.OnTouchListener {
    private data class Tap(val downX: Float, var canceled: Boolean)
    private var originalPointer = -1
    private var originalX = 0f
    private var overlapping: MutableMap<Int, Tap>? = null

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            overlapping = null
            originalPointer = event.getPointerId(0)
            originalX = event.x
            return singleTouch.onTouch(view, event)
        }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            var taps = overlapping
            if (taps == null) {
                taps = linkedMapOf()
                if (originalPointer >= 0)
                    taps[originalPointer] = Tap(originalX, !canCompleteOriginalTap())
                overlapping = taps
                // Revoke the old per-key timer/popup before entering independent tap mode.
                val cancel = MotionEvent.obtain(event)
                try {
                    cancel.action = MotionEvent.ACTION_CANCEL
                    singleTouch.onTouch(view, cancel)
                } finally {
                    cancel.recycle()
                }
            }
            val index = event.actionIndex
            val x = event.getX(index)
            val y = event.getY(index)
            // A new pointer outside all children can be routed to an existing ViewGroup target.
            // Do not turn a deliberate row/split-layout spacer into this key.
            taps[event.getPointerId(index)] = Tap(x,
                !x.isFinite() || !y.isFinite() || x < 0 || y < 0 || x >= view.width || y >= view.height)
            if (taps[event.getPointerId(index)]?.canceled == false) onAdditionalPress()
            view.alpha = .65f
            return true
        }
        val taps = overlapping
        if (taps != null) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    for (index in 0 until event.pointerCount) {
                        val tap = taps[event.getPointerId(index)] ?: continue
                        val x = event.getX(index)
                        val y = event.getY(index)
                        if (!x.isFinite() || !y.isFinite() ||
                            abs(x - tap.downX) > horizontalCancelDistance ||
                            y < -verticalCancelSlop || y > view.height + verticalCancelSlop)
                            tap.canceled = true
                    }
                }
                MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                    val index = event.actionIndex
                    val tap = taps.remove(event.getPointerId(index))
                    if (tap != null && !tap.canceled && event.getX(index).isFinite() && event.getY(index).isFinite())
                        completeTap(event.eventTime)
                    if (taps.isEmpty()) {
                        overlapping = null
                        originalPointer = -1
                        view.alpha = 1f
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    overlapping = null
                    originalPointer = -1
                    view.alpha = 1f
                    return singleTouch.onTouch(view, event)
                }
            }
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
            originalPointer = -1
        return singleTouch.onTouch(view, event)
    }
}
