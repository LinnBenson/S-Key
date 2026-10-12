package io.secure.keyboard

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.test.InstrumentationTestCase
import android.view.inputmethod.InputMethod
import android.view.inputmethod.InputMethodInfo

/** Read-only installed identity and artwork checks. No activities, settings, or user data change. */
@Suppress("DEPRECATION")
class BrandingTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private val packageManager get() = context.packageManager

    fun testApplicationAndLauncherUseSKeyIdentity() {
        assertEquals("io.secure.keyboard", context.packageName)
        val application = packageManager.getApplicationInfo(context.packageName, 0)
        assertEquals("S Key", packageManager.getApplicationLabel(application).toString())

        val launcher = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
        val resolved = requireNotNull(packageManager.resolveActivity(launcher, 0)) {
            "S Key must have a resolvable launcher activity"
        }
        assertEquals(context.packageName, resolved.activityInfo.packageName)
        assertEquals(MainActivity::class.java.name, resolved.activityInfo.name)
        assertEquals("S Key", resolved.loadLabel(packageManager).toString())
    }

    fun testInputMethodLabelAndSettingsResolveInSKeyPackage() {
        val resolved = requireNotNull(packageManager.resolveService(
            Intent(InputMethod.SERVICE_INTERFACE).setPackage(context.packageName),
            PackageManager.GET_META_DATA
        )) { "S Key must register an input-method service" }
        val service = resolved.serviceInfo
        assertEquals(context.packageName, service.packageName)
        assertEquals(OfflineKeyboardService::class.java.name, service.name)
        assertEquals("android.permission.BIND_INPUT_METHOD", service.permission)
        assertEquals("S Key", service.loadLabel(packageManager).toString())

        // Parse the installed IME metadata, so a stale fully qualified settings name is caught.
        val inputMethod = InputMethodInfo(context, resolved)
        assertEquals("S Key", inputMethod.loadLabel(packageManager).toString())
        assertEquals(SettingsActivity::class.java.name, inputMethod.settingsActivity)
        val settings = ComponentName(context.packageName, requireNotNull(inputMethod.settingsActivity))
        val settingsInfo = packageManager.getActivityInfo(settings, 0)
        assertEquals(context.packageName, settingsInfo.packageName)
        assertEquals(SettingsActivity::class.java.name, settingsInfo.name)
        assertTrue("IME settings component must be enabled", settingsInfo.enabled)
        val resolvedSettings = requireNotNull(packageManager.resolveActivity(
            Intent().setComponent(settings), 0
        )) { "The declared IME settings component must resolve" }
        assertEquals(settings, ComponentName(resolvedSettings.activityInfo.packageName,
            resolvedSettings.activityInfo.name))
    }

    fun testLauncherAndRoundAdaptiveIconsRenderOnSupportedAndroidVersions() {
        // minSdk is 26. Render actual drawables rather than assuming a particular launcher mask.
        val icons = listOf(
            "application" to packageManager.getApplicationIcon(context.packageName),
            "launcher" to requireNotNull(context.getDrawable(R.mipmap.ic_launcher)),
            "round launcher" to requireNotNull(context.getDrawable(R.mipmap.ic_launcher_round))
        )
        for ((name, drawable) in icons) {
            assertTrue("$name must load as an adaptive icon", drawable is AdaptiveIconDrawable)
            val adaptive = drawable as AdaptiveIconDrawable
            assertNotNull("$name must have a background", adaptive.background)
            assertNotNull("$name must have a foreground", adaptive.foreground)
            withRenderedPixels(adaptive) { pixels ->
                // Broad palette checks catch blank/missing layers while allowing different masks,
                // rasterization, density, and antialiased edges across Android versions.
                assertTrue("$name must show the dark blue background", pixels.count {
                    Color.alpha(it) > 200 && Color.red(it) < 60 &&
                        Color.green(it) in 20..90 && Color.blue(it) in 50..130
                } > pixels.size / 20)
                assertTrue("$name must show the light keycap", pixels.count {
                    Color.alpha(it) > 200 && Color.red(it) > 220 &&
                        Color.green(it) > 220 && Color.blue(it) > 220
                } > pixels.size / 100)
                assertTrue("$name must show the blue S mark", pixels.count {
                    Color.alpha(it) > 200 && Color.red(it) < 90 &&
                        Color.green(it) in 60..160 && Color.blue(it) > 180
                } > pixels.size / 1000)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                val monochrome = requireNotNull(adaptive.monochrome) {
                    "$name must provide Android 13 themed-icon artwork"
                }
                withRenderedPixels(monochrome) { pixels ->
                    assertTrue("$name monochrome artwork must be visible",
                        pixels.any { Color.alpha(it) > 200 })
                    assertTrue("$name monochrome artwork must preserve transparent space",
                        pixels.any { Color.alpha(it) == 0 })
                }
            }
        }
    }

    private fun withRenderedPixels(drawable: Drawable, check: (IntArray) -> Unit) {
        val size = 216
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val oldBounds = android.graphics.Rect(drawable.bounds)
        try {
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(bitmap))
            val pixels = IntArray(size * size)
            bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
            check(pixels)
        } finally {
            drawable.bounds = oldBounds
            bitmap.recycle()
        }
    }
}
