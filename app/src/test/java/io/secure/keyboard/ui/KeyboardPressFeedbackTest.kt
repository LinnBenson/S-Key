package io.secure.keyboard.ui

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardPressFeedbackTest {
    @Test fun delayedCandidateFeedbackRetainsItsOriginalTouchState() {
        val clicks = mutableListOf<Boolean>()
        val feedback = KeyboardPressFeedback(onClick = { clicks.add(it) })
        feedback.press()
        val delayed = feedback.takeClick()
        feedback.click()
        delayed()
        assertEquals(listOf(true, false), clicks)
    }

    @Test fun touchRequestsHapticBeforeReleaseAndDoesNotRepeatAtClick() {
        val events = mutableListOf<String>()
        val feedback = KeyboardPressFeedback({ events.add("press") }, { events.add("click:$it") })
        feedback.onAction(MotionEvent.ACTION_DOWN)
        assertEquals(listOf("press"), events)
        feedback.onAction(MotionEvent.ACTION_MOVE)
        feedback.onAction(MotionEvent.ACTION_UP)
        assertEquals(listOf("press"), events)
        feedback.click()
        assertEquals(listOf("press", "click:false"), events)
    }

    @Test fun canceledTouchDoesNotSwallowSubsequentAccessibilityFeedback() {
        val events = mutableListOf<String>()
        val feedback = KeyboardPressFeedback({ events.add("press") }, { events.add("click:$it") })
        feedback.onAction(MotionEvent.ACTION_DOWN)
        feedback.onAction(MotionEvent.ACTION_CANCEL)
        feedback.click()
        assertEquals(listOf("press", "click:true"), events)
    }

    @Test fun rapidTapsEachReceiveExactlyOnePress() {
        var presses = 0
        val clicks = mutableListOf<Boolean>()
        val feedback = KeyboardPressFeedback({ presses++ }, { clicks.add(it) })
        repeat(10) {
            feedback.onAction(MotionEvent.ACTION_DOWN)
            feedback.onAction(MotionEvent.ACTION_UP)
            feedback.click()
        }
        assertEquals(10, presses)
        assertEquals(List(10) { false }, clicks)
    }

    @Test fun feedbackIsIndependentForOverlappingKeys() {
        val clicks = mutableListOf<Boolean>()
        val first = KeyboardPressFeedback(onClick = { clicks.add(it) })
        val second = KeyboardPressFeedback(onClick = { clicks.add(it) })
        first.onAction(MotionEvent.ACTION_DOWN)
        second.onAction(MotionEvent.ACTION_DOWN)
        second.click()
        first.click()
        first.click()
        assertEquals(listOf(false, false, true), clicks)
    }
}
