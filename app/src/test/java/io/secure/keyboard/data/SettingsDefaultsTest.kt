package io.secure.keyboard.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsDefaultsTest {
    @Test
    fun absentLanguagePreferenceDefaultsToChinese() {
        assertEquals(true, SettingsStore.DEFAULT_CHINESE_LANGUAGE)
    }

    @Test
    fun lightPaletteMatchesAllFifteenScreenshotValues() {
        assertEquals(
            linkedMapOf(
                "keyboardBackground" to "#EFEFF1",
                "normalKeyBackground" to "#FFFFFF",
                "specialKeyBackground" to "#D0D2D9",
                "text" to "#17283E",
                "hint" to "#65788E",
                "toolbarIcon" to "#17283E",
                "candidate" to "#17283E",
                "candidateSelected" to "#007AFF",
                "accent" to "#007AFF",
                "enterText" to "#FFFFFF",
                "preedit" to "#007AFF",
                "divider" to "#CFD8DB",
                "specialKeyText" to "#17283E",
                "selectedKeyBackground" to "#007AFF",
                "selectedKeyText" to "#FFFFFF",
            ),
            SettingsStore.defaultThemeTokens(false).asMap(),
        )
    }

    @Test
    fun darkPaletteKeepsEveryPreviousDefault() {
        assertEquals(
            linkedMapOf(
                "keyboardBackground" to "#172029",
                "normalKeyBackground" to "#303D49",
                "specialKeyBackground" to "#3D5164",
                "text" to "#F0F4FC",
                "hint" to "#AFBED0",
                "toolbarIcon" to "#F0F4FC",
                "candidate" to "#F0F4FC",
                "candidateSelected" to "#88BFFF",
                "accent" to "#88BFFF",
                "enterText" to "#17283E",
                "preedit" to "#88BFFF",
                "divider" to "#3D5164",
                "specialKeyText" to "#F0F4FC",
                "selectedKeyBackground" to "#88BFFF",
                "selectedKeyText" to "#17283E",
            ),
            SettingsStore.defaultThemeTokens(true).asMap(),
        )
    }

    @Test
    fun requestedProfilesHaveExactDefaultsAndKeepOtherOptions() {
        assertEquals(
            SettingsStore.LayoutProfile(heightDp = 285, sideGapDp = 0, bottomGapDp = 0),
            SettingsStore.defaultLayoutProfile(outer = true, landscape = false),
        )
        assertEquals(
            SettingsStore.LayoutProfile(heightDp = 240, sideGapDp = 120, bottomGapDp = 0),
            SettingsStore.defaultLayoutProfile(outer = false, landscape = true),
        )
        assertEquals(
            SettingsStore.LayoutProfile(heightDp = 270, sideGapDp = 20, bottomGapDp = 0),
            SettingsStore.defaultLayoutProfile(outer = false, landscape = false),
        )
    }

    @Test
    fun unspecifiedOuterLandscapeKeepsItsExistingDefaults() {
        assertEquals(
            SettingsStore.LayoutProfile(heightDp = 210),
            SettingsStore.defaultLayoutProfile(outer = true, landscape = true),
        )
    }

    @Test
    fun userFacingDefaultsMatchRequestedValues() {
        assertEquals(10, SettingsStore.DEFAULT_VIBRATION_STRENGTH)
        assertEquals("digits", SettingsStore.DEFAULT_SYMBOL_HINTS)
        assertEquals(
            listOf("floating", "clipboard", "quickedit", "emoji", "voice"),
            SettingsStore.defaultPins,
        )
        assertEquals(
            listOf(
                "ai", "translate", "clipboard", "phrases", "voice", "quickedit", "emoji", "layout",
                "floating", "split", "theme", "script", "input_mode", "dictionary", "english_dictionary", "settings",
            ),
            SettingsStore.availablePins,
        )
        assertEquals(SettingsStore.availablePins.size, SettingsStore.availablePins.toSet().size)
    }
}
