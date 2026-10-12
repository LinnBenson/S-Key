package io.secure.keyboard.settings

import org.junit.Assert.*
import org.junit.Test

class ColorPickerStateTest {
    @Test fun acceptsOnlyOpaqueSixDigitHexAndNormalizesLocaleIndependently() {
        assertEquals("#ABCDEF", ColorPickerState.normalizeHex("  #abcdef  "))
        listOf("", "#", "#123", "123456", "#12345", "#1234567", "#FF123456", "#12X456", "transparent").forEach {
            assertNull(it, ColorPickerState.normalizeHex(it))
        }
    }

    @Test fun openingAndConfirmingPreservesExactOriginalRgb() {
        listOf("#000000", "#FFFFFF", "#17283E", "#123457", "#F0F4FC", "#88BFFF", "#010203", "#FEFDFC").forEach {
            val state = ColorPickerState(it)
            assertEquals(it, state.originalHex)
            assertEquals(it, state.pendingHex)
            assertEquals(it, state.confirmedHex())
        }
    }

    @Test fun validTypingUpdatesPreviewWithoutRewritingUsersText() {
        val state = ColorPickerState("#000000")
        assertTrue(state.typeHex("#abc123"))
        assertEquals("#ABC123", state.pendingHex)
        assertEquals("#abc123", state.rawHex)
        assertEquals("#ABC123", state.confirmedHex())
        assertEquals("#000000", state.originalHex)
    }

    @Test fun incompleteAndInvalidTypingRetainsLastValidVisualColor() {
        val state = ColorPickerState("#336699")
        val before = state.snapshot()
        listOf("#", "#abc", "#XYZXYZ", "#FF000000").forEach {
            assertFalse(state.typeHex(it))
            assertEquals(it, state.rawHex)
            assertEquals(before.pendingHex, state.pendingHex)
            assertEquals(before.hue, state.hue, 0f)
            assertEquals(before.saturation, state.saturation, 0f)
            assertEquals(before.value, state.value, 0f)
            assertNull(state.confirmedHex())
        }
    }

    @Test fun graphicalMovementReplacesInvalidTextWithFreshValidHex() {
        val state = ColorPickerState("#FF0000")
        state.typeHex("#invalid")
        state.chooseHue(120f)
        assertEquals("#00FF00", state.rawHex)
        assertTrue(state.isValid)
    }

    @Test fun grayAndWhiteRetainUsefulHue() {
        val state = ColorPickerState("#0000FF")
        for (gray in listOf("#808080", "#FFFFFF")) {
            state.typeHex(gray)
            assertEquals(240f, state.hue, .001f)
            assertEquals(0f, state.saturation, 0f)
            state.chooseSaturationValue(1f, 1f)
            assertEquals("#0000FF", state.pendingHex)
        }
    }

    @Test fun blackRetainsHueAndChromaForBrightnessRecovery() {
        val state = ColorPickerState("#00FF00")
        state.typeHex("#000000")
        assertEquals(120f, state.hue, .001f)
        assertEquals(1f, state.saturation, 0f)
        assertEquals(0f, state.value, 0f)
        state.chooseSaturationValue(state.saturation, 1f)
        assertEquals("#00FF00", state.pendingHex)
    }

    @Test fun hueWrapsAtBothEndsAndRejectsNonfiniteInputSafely() {
        val state = ColorPickerState("#FF0000")
        state.chooseHue(360f)
        assertEquals(0f, state.hue, 0f)
        assertEquals("#FF0000", state.pendingHex)
        state.chooseHue(-120f)
        assertEquals("#0000FF", state.pendingHex)
        state.chooseHue(840f)
        assertEquals("#00FF00", state.pendingHex)
        state.chooseHue(Float.NaN)
        assertEquals(0f, state.hue, 0f)
    }

    @Test fun saturationAndValueClampToVisibleBounds() {
        val state = ColorPickerState("#FF0000")
        state.chooseSaturationValue(-3f, 5f)
        assertEquals("#FFFFFF", state.pendingHex)
        state.chooseSaturationValue(5f, -3f)
        assertEquals("#000000", state.pendingHex)
        state.chooseSaturationValue(Float.POSITIVE_INFINITY, Float.NaN)
        assertEquals(0f, state.saturation, 0f)
        assertEquals(0f, state.value, 0f)
    }

    @Test fun hsvPrimariesAndMidpointsAreOpaqueAndCorrect() {
        listOf(0f to "#FF0000", 60f to "#FFFF00", 120f to "#00FF00", 180f to "#00FFFF", 240f to "#0000FF", 300f to "#FF00FF").forEach { (h, hex) ->
            assertEquals(hex, ColorPickerState.formatOpaque(ColorPickerState.hsvToColor(h, 1f, 1f)))
        }
        assertEquals("#804040", ColorPickerState.formatOpaque(ColorPickerState.hsvToColor(0f, .5f, .5f)))
        for (h in 0..359) assertEquals(255, ColorPickerState.hsvToColor(h.toFloat(), .67f, .82f) ushr 24)
    }

    @Test fun aCancelledDraftDoesNotMutateItsOriginalOrAnIndependentDraft() {
        val original = "#336699"
        val cancelled = ColorPickerState(original)
        val independent = ColorPickerState(original)
        cancelled.chooseHsv(20f, 1f, 1f)
        assertEquals(original, cancelled.originalHex)
        assertEquals(original, independent.pendingHex)
        assertEquals(original, ColorPickerState(original).confirmedHex())
    }

    @Test fun snapshotRoundTripKeepsInvalidRawTextAndExactPendingColor() {
        val state = ColorPickerState("#123457")
        state.typeHex("#aBc123")
        state.typeHex("#unfinished")
        val restored = requireNotNull(ColorPickerState.restore(state.snapshot()))
        assertEquals(state.snapshot(), restored.snapshot())
        assertNull(restored.confirmedHex())
    }

    @Test fun snapshotRoundTripKeepsHueAtBlack() {
        val state = ColorPickerState("#FF00FF")
        state.typeHex("#000000")
        val restored = requireNotNull(ColorPickerState.restore(state.snapshot()))
        restored.chooseSaturationValue(1f, 1f)
        assertEquals("#FF00FF", restored.pendingHex)
    }

    @Test fun corruptSnapshotCannotInjectAnInvalidRgbOrUnboundedCoordinates() {
        val valid = ColorPickerState("#336699").snapshot()
        assertNull(ColorPickerState.restore(valid.copy(originalHex = "bad")))
        assertNull(ColorPickerState.restore(valid.copy(pendingHex = "#FF123456")))
        val restored = requireNotNull(ColorPickerState.restore(valid.copy(hue = 721f, saturation = 5f, value = -1f)))
        assertEquals(1f, restored.hue, 0f)
        assertEquals(1f, restored.saturation, 0f)
        assertEquals(0f, restored.value, 0f)
        assertEquals("#336699", restored.pendingHex)
    }
}
