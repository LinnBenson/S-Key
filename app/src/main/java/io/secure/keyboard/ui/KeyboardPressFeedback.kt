package io.secure.keyboard.ui

import android.view.MotionEvent

/** One tactile response at touch-down; non-touch clicks retain their feedback. */
class KeyboardPressFeedback(
    private val onPress: () -> Unit = {},
    private val onClick: (Boolean) -> Unit = {},
) {
    private var touchFeedback = false

    fun onTouch(event: MotionEvent) = onAction(event.actionMasked)

    fun cancel() { touchFeedback = false }

    fun press() {
        touchFeedback = true
        onPress()
    }

    internal fun onAction(action: Int) {
        when (action) {
            MotionEvent.ACTION_DOWN -> press()
            MotionEvent.ACTION_CANCEL -> cancel()
        }
    }

    fun takeClick(): () -> Unit {
        val vibrate = !touchFeedback
        touchFeedback = false
        return { onClick(vibrate) }
    }

    fun click() = takeClick().invoke()
}
