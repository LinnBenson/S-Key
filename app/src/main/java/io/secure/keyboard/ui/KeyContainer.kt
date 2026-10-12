package io.secure.keyboard.ui

import android.content.Context
import android.widget.FrameLayout

/** Supports both touchscreen gestures and accessibility ACTION_CLICK. */
class KeyContainer(context: Context) : FrameLayout(context) {
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

/** Keeps native GridView item accessibility while adding category-swipe handling. */
class EmojiGrid(context: Context) : android.widget.GridView(context) {
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
