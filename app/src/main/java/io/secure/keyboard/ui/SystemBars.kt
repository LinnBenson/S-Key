package io.secure.keyboard.ui

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsetsController

/** Match navigation/status icon contrast to the visible app/IME theme. */
@Suppress("DEPRECATION")
fun styleSystemBars(window: Window, dark: Boolean, background: Int) {
    window.navigationBarColor = background
    if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
    val legacyFlags =
        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    window.decorView.systemUiVisibility =
        if (dark) window.decorView.systemUiVisibility and legacyFlags.inv()
        else window.decorView.systemUiVisibility or legacyFlags
    if (Build.VERSION.SDK_INT >= 30) {
        val flags =
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.decorView.post {
            window.insetsController?.setSystemBarsAppearance(if (dark) 0 else flags, flags)
        }
    } else {
        val flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.decorView.systemUiVisibility =
            if (dark) window.decorView.systemUiVisibility and flags.inv()
            else window.decorView.systemUiVisibility or flags
    }
}

fun android.app.Activity.applyKeyboardActivityTheme(): Boolean {
    val choice = io.secure.keyboard.data.SettingsStore(this).theme
    val dark =
        choice == "dark" ||
            (choice == "system" &&
                resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES)
    setTheme(
        if (dark) android.R.style.Theme_Material_NoActionBar
        else android.R.style.Theme_Material_Light_NoActionBar
    )
    return dark
}
