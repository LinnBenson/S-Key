package io.secure.keyboard.settings

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import io.secure.keyboard.SettingsActivity
import io.secure.keyboard.data.SettingsStore
import io.secure.keyboard.ui.KeyboardThemeEditor

/** Real editor/dialog recreation tests use an isolated SettingsStore, never live preferences. */
@Suppress("DEPRECATION")
class KeyboardThemeEditorTest : InstrumentationTestCase() {
    private lateinit var config: SettingsStore
    private lateinit var prefs: SharedPreferences
    private lateinit var application: Application
    @Volatile private var activity: SettingsActivity? = null

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(current: Activity, state: Bundle?) {
            if (current is SettingsActivity) SettingsActivity::class.java.getDeclaredField("config").apply { isAccessible = true }.set(current, config)
        }
        override fun onActivityStarted(current: Activity) { if (current is SettingsActivity) activity = current }
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    override fun setUp() {
        super.setUp()
        val sandbox = object : ContextWrapper(instrumentation.targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = baseContext.getSharedPreferences("hsv_editor_test_$name", mode)
        }
        prefs = sandbox.getSharedPreferences("settings", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().clear().commit())
        config = SettingsStore(sandbox)
        config.theme = "dark"
        config.keySound = true
        application = instrumentation.targetContext.applicationContext as Application
        instrumentation.runOnMainSync { application.registerActivityLifecycleCallbacks(lifecycle) }
    }

    override fun tearDown() {
        try {
            instrumentation.runOnMainSync {
                activity?.finish()
                application.unregisterActivityLifecycleCallbacks(lifecycle)
            }
            instrumentation.waitForIdleSync()
            assertTrue(prefs.edit().clear().commit())
        } finally { super.tearDown() }
    }

    fun testPreviewTapSelectsRoleButCancelledScrollDoesNotOpenEditor() {
        open(false)
        instrumentation.runOnMainSync {
            val preview = find<io.secure.keyboard.ui.PalettePreview>(activity!!.window.decorView, "theme_interactive_preview")
            fun touch(action: Int, x: Float, y: Float) {
                val event = MotionEvent.obtain(0, 1, action, x, y, 0)
                try { preview.onTouchEvent(event) } finally { event.recycle() }
            }
            touch(MotionEvent.ACTION_DOWN, 10f, 10f)
            touch(MotionEvent.ACTION_CANCEL, 10f, 10f)
            touch(MotionEvent.ACTION_UP, 10f, 10f)
            assertNull(currentDialog())
            touch(MotionEvent.ACTION_DOWN, 10f, 10f)
            touch(MotionEvent.ACTION_MOVE, 100f, 100f)
            touch(MotionEvent.ACTION_UP, 10f, 10f)
            assertNull(currentDialog())
            touch(MotionEvent.ACTION_DOWN, 10f, 10f)
            touch(MotionEvent.ACTION_UP, 10f, 10f)
            assertNotNull(currentDialog())
            assertEquals("toolbarIcon", preview.roleAt(10f, 10f))
        }
    }

    fun testDragAndTypedPreviewCancelWithoutAnyPreferenceWrite() {
        open(false)
        val before = prefs.all.toMap()
        instrumentation.runOnMainSync { openToken("keyboardBackground") }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            val plane = find<ColorPickerView>(dialog().window!!.decorView, "color_sv")
            val event = MotionEvent.obtain(0, 1, MotionEvent.ACTION_DOWN, plane.width * .75f, plane.height * .25f, 0)
            try { plane.onTouchEvent(event) } finally { event.recycle() }
            assertEquals(before, prefs.all)
            hex().setText("#123456")
            assertEquals(before, prefs.all)
            dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        }
        instrumentation.waitForIdleSync()
        assertEquals(before, prefs.all)
        instrumentation.runOnMainSync { assertNull(currentDialog()); assertNull(editor().saveDraft()) }
    }

    fun testSaveNormalizesExactlyOneTokenAndDoesNotOverwriteAnotherNewRole() {
        open(false)
        val originalLight = config.themeTokens(false).asMap()
        val originalDark = config.themeTokens(true).asMap()
        instrumentation.runOnMainSync {
            openToken("keyboardBackground")
            hex().setText("#abcdef")
            // An independent update while the dialog is open must survive selected-token Save.
            config.setThemeToken(false, "hint", "#123456")
            dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        instrumentation.waitForIdleSync()
        assertEquals(originalLight + mapOf("keyboardBackground" to "#ABCDEF", "hint" to "#123456"), config.themeTokens(false).asMap())
        assertEquals(originalDark, config.themeTokens(true).asMap())
        assertEquals("dark", config.theme)
        assertTrue(config.keySound)
    }

    fun testInvalidHexSaveKeepsDialogAndLastVisualDraftUntilCorrected() {
        open(true)
        val before = prefs.all.toMap()
        instrumentation.runOnMainSync {
            openToken("text")
            hex().setText("#00ff00")
            hex().setText("#12x")
            dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertNotNull(hex().error)
            assertTrue(dialog().isShowing)
            assertEquals("#12x", hex().text.toString())
            assertEquals("#00FF00", editor().saveDraft()!!.getString("pending"))
            assertEquals(before, prefs.all)
            hex().setText("#abcdef")
            dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        instrumentation.waitForIdleSync()
        assertEquals("#ABCDEF", config.themeTokens(true).text)
    }

    fun testUnchangedSavePreservesExactOriginalDigits() {
        config.setThemeToken(false, "text", "#123457")
        open(false)
        val before = config.themeTokens(false).asMap()
        instrumentation.runOnMainSync {
            openToken("text")
            assertEquals("#123457", hex().text.toString())
            dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        instrumentation.waitForIdleSync()
        assertEquals(before, config.themeTokens(false).asMap())
    }

    fun testActivityRecreationRestoresInvalidDraftAndPrecisionStateWithoutCommitting() {
        open(false)
        val before = prefs.all.toMap()
        val previous = requireNotNull(activity)
        var oldPlane: ColorPickerView? = null
        instrumentation.runOnMainSync {
            openToken("accent")
            val root = dialog().window!!.decorView
            find<Button>(root, "color_precision").performClick()
            hex().setText("#334455")
            hex().setText("#33")
            hex().requestFocus()
            hex().setSelection(1, 3)
            oldPlane = find(root, "color_sv")
            val saved = editor().saveDraft()!!
            assertTrue(saved.getBoolean("expanded"))
            assertPrimitiveDraft(saved)
            previous.recreate()
        }
        awaitRecreation(previous)
        instrumentation.runOnMainSync {
            assertEquals("#33", hex().text.toString())
            val restored = editor().saveDraft()!!
            assertEquals("#334455", restored.getString("pending"))
            assertTrue(restored.getBoolean("expanded"))
            assertEquals(1, hex().selectionStart)
            assertEquals(3, hex().selectionEnd)
            assertNull(oldPlane!!.onChange)
            assertEquals(before, prefs.all)
            dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        }
        assertEquals(before, prefs.all)
    }

    fun testBackAndOutsideCancellationDetachPickerCallbacksWithoutLeavingPage() {
        open(false)
        val before = prefs.all.toMap()
        instrumentation.runOnMainSync {
            openToken("hint")
            hex().setText("#223344")
            val plane = find<ColorPickerView>(dialog().window!!.decorView, "color_sv")
            assertTrue(editor().dismissDialog())
            assertNull(plane.onChange)
            assertFalse(editor().dismissDialog())
            assertNull(currentDialog())
            assertNotNull(find<Button>(activity!!.window.decorView, "theme_token_hint"))
            openToken("hint")
            hex().setText("#556677")
            dialog().cancel() // Same cancellation path as outside/Back dismissal.
        }
        instrumentation.waitForIdleSync()
        assertEquals(before, prefs.all)
        instrumentation.runOnMainSync { assertNull(currentDialog()) }
    }

    fun testNativePrecisionControlsChangeDraftAndRemainLocal() {
        open(false)
        val before = prefs.all.toMap()
        instrumentation.runOnMainSync {
            openToken("text")
            val root = dialog().window!!.decorView
            find<Button>(root, "color_precision").performClick()
            hex().setText("#FF0000")
            val slider = find<SeekBar>(root, "color_hue_exact")
            assertTrue(slider.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                Bundle().apply { putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 120f) }))
            assertEquals("#00FF00", hex().text.toString())
            assertEquals(before, prefs.all)
            assertTrue(slider.minimumHeight >= SettingsPalette.dp(slider.context, 48))
            dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        }
        assertEquals(before, prefs.all)
    }

    fun testResetOnlySelectedPaletteAndNeverKeyboardMode() {
        config.setThemeToken(false, "text", "#123456")
        config.setThemeToken(true, "text", "#ABCDEF")
        open(true)
        val light = config.themeTokens(false).asMap()
        instrumentation.runOnMainSync {
            descendants(activity!!.window.decorView).filterIsInstance<Button>().single { it.text == "恢复深色默认配色" }.performClick()
            dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        instrumentation.waitForIdleSync()
        assertEquals(light, config.themeTokens(false).asMap())
        assertEquals(SettingsStore.defaultThemeTokens(true), config.themeTokens(true))
        assertEquals("dark", config.theme)
        assertTrue(config.keySound)
    }

    private fun open(dark: Boolean) {
        instrumentation.startActivitySync(Intent(instrumentation.targetContext, SettingsActivity::class.java).apply {
            putExtra("page", if (dark) "theme_dark" else "theme_light")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        instrumentation.waitForIdleSync()
    }

    private fun openToken(id: String) {
        assertTrue(find<Button>(activity!!.window.decorView, "theme_token_$id").performClick())
        assertTrue(dialog().isShowing)
    }

    private fun editor(): KeyboardThemeEditor = SettingsActivity::class.java.getDeclaredField("editor")
        .apply { isAccessible = true }.get(requireNotNull(activity)) as KeyboardThemeEditor

    private fun currentDialog(): AlertDialog? = KeyboardThemeEditor::class.java.getDeclaredField("activeDialog")
        .apply { isAccessible = true }.get(editor()) as AlertDialog?

    private fun dialog(): AlertDialog = requireNotNull(currentDialog())
    private fun hex(): EditText = find(dialog().window!!.decorView, "color_hex")

    private inline fun <reified T : View> find(root: View, tag: String): T = descendants(root).filterIsInstance<T>().single { it.tag == tag }

    private fun descendants(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(descendants(root.getChildAt(index)))
    }

    private fun assertPrimitiveDraft(bundle: Bundle) {
        bundle.keySet().forEach { key ->
            val value = bundle.get(key)
            assertTrue("Only primitive saved state: $key", value is String || value is Boolean || value is Int || value is Float)
        }
    }

    private fun awaitRecreation(previous: SettingsActivity) {
        val end = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < end) {
            instrumentation.waitForIdleSync()
            var restored = false
            instrumentation.runOnMainSync { restored = activity !== previous && activity != null && currentDialog() != null }
            if (restored) return
            SystemClock.sleep(50)
        }
        fail("Color picker draft did not restore after Activity recreation")
    }
}
