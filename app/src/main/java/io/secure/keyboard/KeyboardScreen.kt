package io.secure.keyboard

import android.app.Activity
import io.secure.keyboard.settings.SettingsScreen

/** Source-compatible bridge for companion callers; the old dark argument is intentionally ignored. */
internal class KeyboardScreen(activity: Activity, @Suppress("UNUSED_PARAMETER") dark: Boolean = false) :
    SettingsScreen(activity)
