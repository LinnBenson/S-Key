package io.secure.keyboard.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.test.AndroidTestCase

/** Uses a dedicated preferences file; never clears or modifies the actual app settings. */
@Suppress("DEPRECATION")
class SettingsDefaultsPersistenceTest : AndroidTestCase() {
    private lateinit var sandbox: Context
    private lateinit var prefs: SharedPreferences

    override fun setUp() {
        super.setUp()
        sandbox = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                baseContext.getSharedPreferences("defaults_persistence_test_$name", mode)
        }
        prefs = sandbox.getSharedPreferences("settings", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().clear().commit())
    }

    override fun tearDown() {
        try {
            assertTrue(prefs.edit().clear().commit())
        } finally {
            super.tearDown()
        }
    }

    fun testFreshSettingsUseRequestedDefaultsWithoutWritingPreferences() {
        val settings = SettingsStore(sandbox)
        assertTrue(settings.lastChineseLanguage)
        assertFalse(settings.keySound)
        assertEquals(SettingsStore.LayoutProfile(heightDp = 285), settings.profile(true, false))
        assertEquals(SettingsStore.LayoutProfile(heightDp = 210), settings.profile(true, true))
        assertEquals(
            SettingsStore.LayoutProfile(heightDp = 270, sideGapDp = 20),
            settings.profile(false, false),
        )
        assertEquals(
            SettingsStore.LayoutProfile(heightDp = 240, sideGapDp = 120),
            settings.profile(false, true),
        )
        assertEquals(10, settings.vibrationStrength)
        assertEquals("digits", settings.symbolHints)
        assertTrue(settings.longPressSymbols)
        assertEquals(
            listOf("floating", "clipboard", "quickedit", "emoji", "voice"),
            settings.toolbarPins,
        )
        assertTrue(prefs.all.isEmpty())
    }

    fun testOnlyExplicitLanguageChoiceIsSavedAndReopened() {
        val settings = SettingsStore(sandbox)
        assertTrue(settings.lastChineseLanguage)
        assertFalse(prefs.contains("lastChineseLanguage"))
        for (chinese in listOf(false, true, false)) {
            settings.lastChineseLanguage = chinese
            assertEquals(chinese, SettingsStore(sandbox).lastChineseLanguage)
            assertEquals(mapOf("lastChineseLanguage" to chinese), prefs.all)
        }
    }

    fun testKeySoundDefaultsOffAndExplicitChoicesSurviveReopening() {
        val settings = SettingsStore(sandbox)
        assertFalse(settings.keySound)
        assertFalse("Reading the default does not write a preference", prefs.contains("keySound"))
        for (enabled in listOf(true, false)) {
            settings.keySound = enabled
            assertEquals(enabled, SettingsStore(sandbox).keySound)
            assertEquals(mapOf("keySound" to enabled), prefs.all)
        }
    }

    fun testInvalidSymbolHintsFallBackToDigitsWithoutChangingLongPressOrSavedChoices() {
        val settings = SettingsStore(sandbox)
        settings.longPressSymbols = false
        assertTrue(prefs.edit().putString("symbolHints", "unknown-mode").commit())
        val before = prefs.all.toMap()
        assertEquals("digits", settings.symbolHints)
        assertFalse(settings.longPressSymbols)
        assertEquals("Invalid read does not migrate existing preferences", before, prefs.all)
        settings.symbolHints = "unknown-mode"
        assertEquals("digits", SettingsStore(sandbox).symbolHints)
        for (mode in listOf("all", "digits", "hidden")) {
            settings.symbolHints = mode
            val saved = prefs.all.toMap()
            assertEquals(mode, SettingsStore(sandbox).symbolHints)
            assertFalse(SettingsStore(sandbox).longPressSymbols)
            assertEquals(saved, prefs.all)
        }
    }

    fun testEverySavedProfileAndStrengthSurviveReopeningUnchanged() {
        val settings = SettingsStore(sandbox)
        val customPins = listOf("phrases", "voice", "floating", "clipboard", "settings")
        settings.toolbarPins = customPins
        val profiles = listOf(true to false, true to true, false to false, false to true)
        val expected = profiles.mapIndexed { index, _ ->
            SettingsStore.LayoutProfile(
                heightDp = 310 + index,
                sideGapDp = 31 + index,
                bottomGapDp = 11 + index,
                floating = true,
                floatingXDp = 50 + index,
                floatingYDp = 60 + index,
                floatingWidthDp = 350 + index,
                floatingHeightDp = 320 + index,
                split = true,
                splitGapDp = 70 + index,
            )
        }
        profiles.forEachIndexed { index, (outer, landscape) ->
            settings.saveProfile(outer, landscape, expected[index])
        }
        for (strength in listOf(1, 10, 15, 67, 100)) {
            settings.vibrationStrength = strength
            val before = prefs.all.toMap()
            val reopened = SettingsStore(sandbox)
            profiles.forEachIndexed { index, (outer, landscape) ->
                assertEquals(expected[index], reopened.profile(outer, landscape))
            }
            assertEquals(strength, reopened.vibrationStrength)
            assertEquals(customPins, reopened.toolbarPins)
            assertEquals(before, prefs.all)
        }
        for (mode in listOf("all", "digits", "hidden")) {
            settings.symbolHints = mode
            val before = prefs.all.toMap()
            val reopened = SettingsStore(sandbox)
            assertEquals(mode, reopened.symbolHints)
            assertTrue(reopened.longPressSymbols)
            assertEquals(before, prefs.all)
        }
    }

    fun testNewLightDefaultsPreserveSavedPalettesAndResetOnlyLight() {
        val settings = SettingsStore(sandbox)
        assertEquals("#EFEFF1", settings.themeTokens(false).keyboardBackground)
        assertEquals("#D0D2D9", settings.themeTokens(false).specialKeyBackground)
        assertEquals("#CFD8DB", settings.themeTokens(false).divider)
        assertEquals("#2B2B2B", settings.themeTokens(false).accent)
        assertTrue("Reading new defaults must not write preferences", prefs.all.isEmpty())

        // Include the former light defaults as explicitly saved choices; never migrate them.
        val savedLight = settings.themeTokens(false).copy(
            keyboardBackground = "#EDF3F8",
            specialKeyBackground = "#DFE2E9",
            divider = "#DCE8F1",
            candidateSelected = "#087CF0",
            accent = "#087CF0",
            preedit = "#087CF0",
            selectedKeyBackground = "#087CF0",
            text = "#112233",
        )
        val savedDark = settings.themeTokens(true).copy(
            keyboardBackground = "#123456", accent = "#ABCDEF", hint = "#A1B2C3",
        )
        settings.saveThemeTokens(false, savedLight)
        settings.saveThemeTokens(true, savedDark)
        val before = prefs.all.toMap()
        val reopened = SettingsStore(sandbox)
        assertEquals(savedLight, reopened.themeTokens(false))
        assertEquals(savedDark, reopened.themeTokens(true))
        assertEquals(before, prefs.all)

        reopened.resetThemeTokens(false)
        assertEquals(SettingsStore.defaultThemeTokens(false), SettingsStore(sandbox).themeTokens(false))
        assertEquals(savedDark, SettingsStore(sandbox).themeTokens(true))
        assertEquals(before["themeTokens_dark"], prefs.getString("themeTokens_dark", null))
    }

    fun testExplicitOldDefaultsAndZeroGapsAreNotReplaced() {
        val json = """{"heightDp":260,"sideGapDp":0,"bottomGapDp":0}"""
        assertTrue(
            prefs.edit()
                .putString("profile_outer_portrait", json)
                .putString("profile_inner_portrait", json)
                .putString("profile_inner_landscape", json)
                .putInt("vibrationStrength", 15)
                .putString("symbolHints", "all")
                .putBoolean("longPressSymbols", false)
                .putString("toolbarPins", "clipboard,phrases,voice,quickedit,emoji")
                .commit(),
        )
        val before = prefs.all.toMap()
        val settings = SettingsStore(sandbox)
        for ((outer, landscape) in listOf(true to false, false to false, false to true)) {
            assertEquals(SettingsStore.LayoutProfile(heightDp = 260), settings.profile(outer, landscape))
        }
        assertEquals(15, settings.vibrationStrength)
        assertEquals("all", settings.symbolHints)
        assertFalse(settings.longPressSymbols)
        assertEquals(
            listOf("clipboard", "phrases", "voice", "quickedit", "emoji"),
            settings.toolbarPins,
        )
        assertEquals(before, prefs.all)
        assertTrue(prefs.edit().remove("toolbarPins").commit())
        val afterReset = prefs.all.toMap()
        assertEquals(
            listOf("floating", "clipboard", "quickedit", "emoji", "voice"),
            SettingsStore(sandbox).toolbarPins,
        )
        assertEquals(afterReset, prefs.all)
    }
}
