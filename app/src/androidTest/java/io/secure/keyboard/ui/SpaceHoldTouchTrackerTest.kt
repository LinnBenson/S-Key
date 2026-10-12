package io.secure.keyboard.ui

import android.test.AndroidTestCase
import android.view.InputDevice
import android.view.MotionEvent

/** Root-stream state tests. No recognition fixtures, real microphone, or per-key click shortcuts. */
@Suppress("DEPRECATION")
class SpaceHoldTouchTrackerTest : AndroidTestCase() {
    private var released = 0
    private var canceled = 0
    private lateinit var tracker: SpaceHoldTouchTracker

    override fun setUp() {
        super.setUp()
        tracker = SpaceHoldTouchTracker({ released++ }, { canceled++ })
    }

    fun testOrdinaryTapAndMultitouchEventsAreNeverIntercepted() {
        assertFalse(event(MotionEvent.ACTION_DOWN, 7))
        assertFalse(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), 7, 22))
        assertFalse(event(pointer(MotionEvent.ACTION_POINTER_UP, 0), 7, 22))
        assertFalse(event(MotionEvent.ACTION_UP, 22))
        assertEquals(0, released)
        assertEquals(0, canceled)
    }

    fun testClaimedPointerReleaseSurvivesUnrelatedPointerAndConsumesRemainder() {
        event(MotionEvent.ACTION_DOWN, 7)
        assertTrue(tracker.claim(7, tracker.version))
        assertTrue(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), 7, 22))
        assertTrue(event(pointer(MotionEvent.ACTION_POINTER_UP, 0), 7, 22))
        assertEquals(1, released)
        assertTrue("Lingering finger cannot click the newly rendered keyboard", event(MotionEvent.ACTION_UP, 22))
        assertEquals(1, released)
        assertEquals(0, canceled)
        assertFalse(event(MotionEvent.ACTION_DOWN, 3))
        assertFalse(event(MotionEvent.ACTION_UP, 3))
    }

    fun testUnrelatedPointerReleaseDoesNotFinishHeldOwner() {
        event(MotionEvent.ACTION_DOWN, 7)
        assertTrue(tracker.claim(7, tracker.version))
        event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), 7, 22)
        event(pointer(MotionEvent.ACTION_POINTER_UP, 1), 7, 22)
        assertEquals(0, released)
        event(MotionEvent.ACTION_UP, 7)
        assertEquals(1, released)
    }

    fun testOverlapBeforeDeadlinePermanentlyDisqualifiesThatHold() {
        event(MotionEvent.ACTION_DOWN, 7)
        val version = tracker.version
        event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), 7, 22)
        event(pointer(MotionEvent.ACTION_POINTER_UP, 1), 7, 22)
        assertFalse(tracker.canClaim(7, version))
        assertFalse(tracker.claim(7, version))
        assertFalse(event(MotionEvent.ACTION_UP, 7))
        assertEquals(0, released)
    }

    fun testSecondFingerCannotBecomeEligibleAfterOriginalFingerLifts() {
        event(MotionEvent.ACTION_DOWN, 7)
        event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), 7, 22)
        val secondFingerVersion = tracker.version
        event(pointer(MotionEvent.ACTION_POINTER_UP, 0), 7, 22)
        assertFalse(tracker.canClaim(22, secondFingerVersion))
        assertFalse(event(MotionEvent.ACTION_UP, 22))
    }

    fun testRealCancelDiscardsOnceAndNeverBecomesRelease() {
        event(MotionEvent.ACTION_DOWN, 7)
        assertTrue(tracker.claim(7, tracker.version))
        assertTrue(event(MotionEvent.ACTION_CANCEL, 7))
        event(MotionEvent.ACTION_UP, 7)
        assertEquals(1, canceled)
        assertEquals(0, released)
    }

    fun testEditorRevocationConsumesStaleUpWithoutCallingEitherCallback() {
        event(MotionEvent.ACTION_DOWN, 7)
        assertTrue(tracker.claim(7, tracker.version))
        tracker.revoke()
        assertTrue(event(MotionEvent.ACTION_UP, 7))
        assertEquals(0, canceled)
        assertEquals(0, released)
    }

    fun testFreshDownRevokesMissingOldTerminalBeforePassingNewStream() {
        event(MotionEvent.ACTION_DOWN, 7)
        assertTrue(tracker.claim(7, tracker.version))
        assertFalse(event(MotionEvent.ACTION_DOWN, 22))
        assertEquals(1, canceled)
        assertFalse(event(MotionEvent.ACTION_UP, 22))
        assertEquals(0, released)
    }

    fun testMalformedOwnedPointerCancelsAndNeverFlushes() {
        event(MotionEvent.ACTION_DOWN, 7)
        assertTrue(tracker.claim(7, tracker.version))
        // A stream missing its owner is invalid. Drop the session rather than finalizing it.
        assertTrue(event(MotionEvent.ACTION_MOVE, 22))
        assertEquals(1, canceled)
        assertTrue(event(MotionEvent.ACTION_UP, 22))
        assertEquals(0, released)
    }

    private fun pointer(action: Int, index: Int) = action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    private fun event(action: Int, vararg ids: Int): Boolean {
        val properties = ids.map { id -> MotionEvent.PointerProperties().apply {
            this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER
        } }.toTypedArray()
        val coordinates = ids.map { MotionEvent.PointerCoords().apply {
            x = 20f; y = 20f; pressure = 1f; size = .1f
        } }.toTypedArray()
        val event = MotionEvent.obtain(1L, 2L, action, ids.size, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        return try { tracker.observe(event) } finally { event.recycle() }
    }
}
