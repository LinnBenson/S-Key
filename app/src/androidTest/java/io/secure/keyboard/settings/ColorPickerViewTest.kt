package io.secure.keyboard.settings

import android.os.Bundle
import android.test.InstrumentationTestCase
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo

/** Focused actual Android touch/key/accessibility checks; no preferences or Activity fixtures. */
@Suppress("DEPRECATION")
class ColorPickerViewTest : InstrumentationTestCase() {
    fun testHueTapDragClampsAndWrapsAtRedEndpoint() = instrumentation.runOnMainSync {
        val view = picker(ColorPickerView.Component.HUE)
        var hue = -1f
        var finished = 0
        view.onChange = { h, _, _, end -> hue = h; if (end) finished++ }
        touch(view, MotionEvent.ACTION_DOWN, 150f, 28f)
        assertEquals(180f, hue, .01f)
        touch(view, MotionEvent.ACTION_MOVE, -100f, 28f)
        assertEquals(0f, hue, .01f)
        touch(view, MotionEvent.ACTION_MOVE, 900f, 28f)
        assertEquals(0f, hue, .01f)
        touch(view, MotionEvent.ACTION_UP, 150f, 28f)
        assertEquals(180f, hue, .01f)
        assertEquals(1, finished)
        view.close()
    }

    fun testSaturationValueEndpointsMidpointAndCancelRemainLocal() = instrumentation.runOnMainSync {
        val view = picker(ColorPickerView.Component.SATURATION_VALUE)
        var saturation = -1f
        var value = -1f
        var finished = 0
        view.onChange = { _, s, v, end -> saturation = s; value = v; if (end) finished++ }
        touch(view, MotionEvent.ACTION_DOWN, 150f, 100f)
        assertEquals(.5f, saturation, .01f)
        assertEquals(.5f, value, .01f)
        touch(view, MotionEvent.ACTION_MOVE, -100f, -100f)
        assertEquals(0f, saturation, 0f)
        assertEquals(1f, value, 0f)
        touch(view, MotionEvent.ACTION_MOVE, 900f, 900f)
        assertEquals(1f, saturation, 0f)
        assertEquals(0f, value, 0f)
        touch(view, MotionEvent.ACTION_CANCEL, 900f, 900f)
        assertEquals(0, finished)
        assertFalse(view.isPressed)
        view.close()
    }

    fun testActivePointerLiftEndsGestureWithoutFollowingRemainingFinger() = instrumentation.runOnMainSync {
        val view = picker(ColorPickerView.Component.HUE)
        var events = 0
        var finished = 0
        view.onChange = { _, _, _, end -> events++; if (end) finished++ }
        touch(view, MotionEvent.ACTION_DOWN, 100f, 28f)
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0 }, MotionEvent.PointerProperties().apply { id = 1 })
        val coordinates = arrayOf(MotionEvent.PointerCoords().apply { x = 100f; y = 28f }, MotionEvent.PointerCoords().apply { x = 250f; y = 28f })
        val event = MotionEvent.obtain(0, 1, MotionEvent.ACTION_POINTER_UP, 2, properties, coordinates, 0, 0, 1f, 1f, 0, 0, 0, 0)
        try { view.onTouchEvent(event) } finally { event.recycle() }
        val countAfterLift = events
        touch(view, MotionEvent.ACTION_MOVE, 250f, 28f)
        assertEquals(countAfterLift, events)
        assertEquals(1, finished)
        assertFalse(view.isPressed)
        view.close()
    }

    fun testDpadAdjustsHueSaturationAndValue() = instrumentation.runOnMainSync {
        val hue = picker(ColorPickerView.Component.HUE)
        hue.setSelection(359f, 1f, 1f)
        var selected = -1f
        hue.onChange = { h, _, _, _ -> selected = h }
        assertTrue(hue.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
        assertEquals(0f, selected, 0f)
        val sv = picker(ColorPickerView.Component.SATURATION_VALUE)
        sv.setSelection(120f, .5f, .5f)
        var saturation = -1f
        var value = -1f
        sv.onChange = { _, s, v, _ -> saturation = s; value = v }
        sv.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        sv.onKeyDown(KeyEvent.KEYCODE_DPAD_UP, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(.51f, saturation, .001f)
        assertEquals(.51f, value, .001f)
        hue.close(); sv.close()
    }

    fun testHueAccessibilityRangeProgressAndCleanup() = instrumentation.runOnMainSync {
        val view = picker(ColorPickerView.Component.HUE)
        val node = AccessibilityNodeInfo.obtain()
        try {
            view.onInitializeAccessibilityNodeInfo(node)
            assertEquals("android.widget.SeekBar", node.className)
            assertEquals(0f, node.rangeInfo.min, 0f)
            assertEquals(359f, node.rangeInfo.max, 0f)
        } finally { node.recycle() }
        var hue = -1f
        view.onChange = { h, _, _, _ -> hue = h }
        assertTrue(view.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
            Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 240f) }))
        assertEquals(240f, hue, 0f)
        assertTrue(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
        assertEquals(241f, hue, 0f)
        view.close()
        assertNull(view.onChange)
    }

    private fun picker(component: ColorPickerView.Component): ColorPickerView =
        ColorPickerView(instrumentation.targetContext, component).apply {
            measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(if (component == ColorPickerView.Component.HUE) 56 else 200, View.MeasureSpec.EXACTLY))
            layout(0, 0, measuredWidth, measuredHeight)
        }

    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0, 1, action, x, y, 0)
        try { assertTrue(view.onTouchEvent(event)) } finally { event.recycle() }
    }
}
