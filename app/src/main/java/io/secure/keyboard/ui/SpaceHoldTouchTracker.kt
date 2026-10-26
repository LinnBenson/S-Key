package io.secure.keyboard.ui

import android.view.MotionEvent

/**
 * Root-level ownership for one held space finger. A key view can disappear when voice replaces
 * the body; the root still receives its original pointer's release/cancel. Ordinary streams are
 * passed through untouched. Once claimed, the remaining stream is consumed until every finger
 * is up, so neither a detached space key nor a newly rendered key can manufacture a tap.
 */
internal class SpaceHoldTouchTracker(
    private val onRelease: () -> Unit,
    private val onCancel: () -> Unit,
) {
    private val pointers = mutableSetOf<Int>()
    private var ownedPointer = -1
    private var captured = false
    private var overlapped = false
    var version = 0L
        private set

    fun canClaim(pointerId: Int, downVersion: Long) =
        !captured && !overlapped && ownedPointer < 0 && version == downVersion &&
            pointers.size == 1 && pointerId in pointers

    fun claim(pointerId: Int, downVersion: Long): Boolean {
        if (!canClaim(pointerId, downVersion)) return false
        ownedPointer = pointerId
        captured = true
        return true
    }

    /** Call before child dispatch; true means this event belongs to the claimed stream. */
    fun observe(event: MotionEvent): Boolean {
        val consume = captured
        if (captured && ownedPointer >= 0 && event.actionMasked != MotionEvent.ACTION_CANCEL) {
            val index = event.findPointerIndex(ownedPointer)
            if (index < 0 || !event.getX(index).isFinite() || !event.getY(index).isFinite()) {
                ownedPointer = -1
                onCancel()
            }
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val cancel = ownedPointer >= 0
                reset()
                pointers.add(event.getPointerId(0))
                if (cancel) onCancel()
                // A fresh DOWN is a new stream, even after a malformed/missing old terminal event.
                return false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                version++ // Any overlap before the timer fires disqualifies every hold in it.
                overlapped = true
                pointers.add(event.getPointerId(event.actionIndex))
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val id = event.getPointerId(event.actionIndex)
                pointers.remove(id)
                val release = ownedPointer == id
                if (release) ownedPointer = -1
                if (event.actionMasked == MotionEvent.ACTION_UP || pointers.isEmpty()) {
                    captured = false
                    pointers.clear()
                }
                if (release) onRelease()
            }
            MotionEvent.ACTION_CANCEL -> {
                val cancel = ownedPointer >= 0
                reset()
                if (cancel) onCancel()
            }
        }
        return consume
    }

    /** Revoke the callback, but keep swallowing a claimed stream's stale terminal events. */
    fun revoke() { ownedPointer = -1 }

    /** Use only when replacing the root or beginning a new Android touch stream. */
    fun reset() {
        ownedPointer = -1
        captured = false
        overlapped = false
        pointers.clear()
        version++
    }
}
