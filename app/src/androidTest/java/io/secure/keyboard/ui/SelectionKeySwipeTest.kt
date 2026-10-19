package io.secure.keyboard.ui

import android.test.InstrumentationTestCase
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection

@Suppress("DEPRECATION")
class SelectionKeySwipeTest : InstrumentationTestCase() {
    fun testFourWayDragWithSelectionOnAndOffAndTap() {
        instrumentation.runOnMainSync {
            for (selected in listOf(false, true)) {
                val view = View(instrumentation.targetContext)
                val events = mutableListOf<KeyEvent>()
                val connection = object : BaseInputConnection(view, true) {
                    override fun sendKeyEvent(event: KeyEvent): Boolean { events.add(event); return true }
                }
                val selection = QuickEditSelection()
                if (selected) selection.toggle(connection)
                var clicks = 0
                view.setOnClickListener { clicks++; selection.toggle(connection) }
                val swipe = SelectionKeySwipe(18f) { selection.move(connection, it) }
                fun send(action: Int, x: Float, y: Float) {
                    val e = MotionEvent.obtain(0, 10, action, x, y, 0)
                    try { swipe.onTouch(view, e) } finally { e.recycle() }
                }
                send(MotionEvent.ACTION_DOWN, 100f, 100f)
                send(MotionEvent.ACTION_MOVE, 136f, 118f)
                send(MotionEvent.ACTION_MOVE, 100f, 82f)
                send(MotionEvent.ACTION_UP, 100f, 82f)
                val arrows = events.filter { it.action == KeyEvent.ACTION_DOWN && it.keyCode != KeyEvent.KEYCODE_SHIFT_LEFT }
                assertEquals(listOf(22, 22, 20, 21, 21, 19, 19), arrows.map { it.keyCode })
                assertTrue(arrows.all { it.isShiftPressed == selected })
                assertEquals(selected, selection.enabled)
                assertEquals(0, clicks)
                send(MotionEvent.ACTION_DOWN, 100f, 100f)
                send(MotionEvent.ACTION_UP, 101f, 100f)
                assertEquals(1, clicks)
                assertEquals(!selected, selection.enabled)
            }
        }
    }

    fun testCanceledAndOverlappingTouchNeverClickOrMoveAfterCancel() {
        instrumentation.runOnMainSync {
            for (cancel in listOf(MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN)) {
                val view = View(instrumentation.targetContext)
                var clicks = 0; var moves = 0
                view.setOnClickListener { clicks++ }
                val swipe = SelectionKeySwipe(18f) { moves++ }
                fun send(action: Int) {
                    val e = MotionEvent.obtain(0, 10, action, 50f, 50f, 0)
                    try { swipe.onTouch(view, e) } finally { e.recycle() }
                }
                send(MotionEvent.ACTION_DOWN); send(cancel)
                send(MotionEvent.ACTION_UP)
                assertEquals(0, clicks); assertEquals(0, moves)
            }
        }
    }
}
