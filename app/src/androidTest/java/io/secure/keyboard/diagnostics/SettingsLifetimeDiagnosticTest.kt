package io.secure.keyboard.diagnostics

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.Parcel
import android.os.Debug
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import io.secure.keyboard.SettingsActivity
import io.secure.keyboard.data.ContentStore
import io.secure.keyboard.data.SettingsStore
import io.secure.keyboard.settings.ColorPickerView
import io.secure.keyboard.ui.KeyboardThemeEditor
import org.json.JSONObject
import java.lang.ref.WeakReference

/** Actual orientation changes and natural PSS are distinct from the explicit-GC attribution test. */
@Suppress("DEPRECATION")
class SettingsLifetimeDiagnosticTest : InstrumentationTestCase() {
    private lateinit var sandbox: LifetimeSandbox
    private lateinit var config: SettingsStore
    private lateinit var content: ContentStore
    private lateinit var app: Application
    private lateinit var probe: LifetimeMemoryProbe
    @Volatile private var current = WeakReference<SettingsActivity>(null)
    private val destroyed = mutableListOf<WeakReference<*>>()
    private val oldDialogs = mutableListOf<WeakReference<*>>()
    private val oldPickers = mutableListOf<WeakReference<*>>()
    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, state: Bundle?) {
            if (activity is SettingsActivity) {
                field(activity, "config", config)
                field(activity, "content", content)
            }
        }
        override fun onActivityStarted(activity: Activity) { if (activity is SettingsActivity) current = WeakReference(activity) }
        override fun onActivityResumed(activity: Activity) { if (activity is SettingsActivity) current = WeakReference(activity) }
        override fun onActivityDestroyed(activity: Activity) {
            if (activity is SettingsActivity) {
                destroyed.add(WeakReference(activity))
                if (current.get() === activity) current.clear()
            }
        }
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    }

    override fun setUp() {
        super.setUp()
        sandbox = LifetimeSandbox(instrumentation.targetContext)
        check(!sandbox.root.exists()) { "Preserve incomplete diagnostic fixture before rerun" }
        sandbox.root.mkdirs()
        config = SettingsStore(sandbox)
        content = ContentStore(sandbox)
        app = instrumentation.targetContext.applicationContext as Application
        probe = LifetimeMemoryProbe(instrumentation.targetContext, "settings")
        instrumentation.checkedMain { app.registerActivityLifecycleCallbacks(lifecycle) }
    }

    override fun tearDown() {
        try {
            probe.stop()
            finishCurrent()
            instrumentation.checkedMain { app.unregisterActivityLifecycleCallbacks(lifecycle) }
            current.clear()
            sandbox.close()
        } finally { super.tearDown() }
    }

    fun testSinglePickerRotationCalibrationNoForcedGc() {
        val started = SystemClock.elapsedRealtime()
        open(false)
        openPicker()
        rotateActual()
        closePicker(1)
        finishCurrent()
        probe.recover(checkpoint = true)
        probe.sample("calibration_complete", extra = JSONObject().put("calibrationOnly", true)
            .put("pickerRotationCycleMillisIncluding10sCheckpointRecovery", SystemClock.elapsedRealtime() - started))
    }

    fun testThirtyPickerCyclesTwentyActualOrientationChangesNoForcedGc() {
        probe.sample("cold_before_settings")
        probe.start()
        repeat(30) { index ->
            probe.cycle = index + 1
            probe.stage = "settings_cycle"
            open(index % 2 != 0)
            val before = sandbox.getSharedPreferences("settings", 0).all.toMap()
            if (index < 10) {
                openPicker()
                rotateActual() // Ten physical portrait/landscape configuration transitions, picker open.
                closePicker(index % 3)
            } else {
                if (index < 20) rotateActual() // Ten transitions before opening the picker.
                openPicker()
                closePicker(index % 3)
            }
            if (index % 3 != 0) assertEquals("Cancel/Back must not save", before, sandbox.getSharedPreferences("settings", 0).all)
            finishCurrent()
            probe.stage = "settings_exited_cycle_${index + 1}"
            probe.recover(checkpoint = index in listOf(0, 14, 29), final = index == 29) { ownershipCounts() }
        }
        probe.stop()
        probe.observeWeakReferences("natural_destroyed_activities", destroyed, false)
        probe.observeWeakReferences("natural_closed_dialogs", oldDialogs, false)
        probe.observeWeakReferences("natural_detached_pickers", oldPickers, false)
    }

    fun testDestroyedActivityDialogAndPickerCollectibilityForcedGcDiagnosticOnly() {
        repeat(3) {
            open(it % 2 != 0)
            openPicker()
            recreateOnly() // Explicitly NOT an orientation transition.
            closePicker(1)
            finishCurrent()
        }
        probe.observeWeakReferences("forced_gc_destroyed_activity_collectibility", destroyed, true)
        probe.observeWeakReferences("forced_gc_dialog_collectibility", oldDialogs, true)
        probe.observeWeakReferences("forced_gc_picker_collectibility", oldPickers, true)
        assertTrue("Destroyed Activities remain reachable; inspect owners before claiming a leak", destroyed.all { it.get() == null })
        assertTrue("Dismissed dialogs remain reachable", oldDialogs.all { it.get() == null })
        assertTrue("Detached picker views remain reachable", oldPickers.all { it.get() == null })
    }

    /** Large state is marshaled only in this process, then dismissed before any lifecycle transition. */
    fun testLargeDraftLocalSavedStateParcelNoBinderTransaction() {
        openRoute("phrases")
        for (length in listOf(64, 16_384, 600_000)) {
            measurePhraseDraftLocally(length)
        }
        finishCurrent()
    }

    private fun measurePhraseDraftLocally(length: Int) {
        // Contains supplementary and combining Unicode without logging or persisting its payload.
        val text = buildString(length) {
            while (this.length + 6 <= length) append("𠀀e\u0301中x")
            while (this.length < length) append('x')
        }
        instrumentation.checkedMain {
            val activity = requireNotNull(current.get())
            SettingsActivity::class.java.getDeclaredMethod("editPhrase", String::class.java, Bundle::class.java)
                .apply { isAccessible = true }.invoke(activity, null, null)
            val fields = SettingsActivity::class.java.getDeclaredField("draftFields").apply { isAccessible = true }
                .get(activity) as List<*>
            val input = fields.single() as EditText
            try {
                input.setText(text)
                input.setSelection(0, input.length())
                val beforeJava = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
                val beforeNative = Debug.getNativeHeapAllocatedSize()
                val started = SystemClock.elapsedRealtime()
                val saved = Bundle()
                instrumentation.callActivityOnSaveInstanceState(activity, saved)
                val parcel = Parcel.obtain()
                val size = try { parcel.writeBundle(saved); parcel.dataSize() } finally { parcel.recycle() }
                probe.sample("local_saved_state_parcel", extra = JSONObject().put("draftUtf16CodeUnits", length)
                    .put("completeActivityStateParcelBytes", size).put("mainThreadMarshalMillis", SystemClock.elapsedRealtime() - started)
                    .put("javaUsedDeltaBytes", Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() } - beforeJava)
                    .put("nativeAllocatedDeltaBytes", Debug.getNativeHeapAllocatedSize() - beforeNative)
                    .put("binderTransactionAttempted", false).put("actualRecreationAttempted", false)
                    .put("processDeathSimulated", false).put("smallStateBudgetDiagnostic64KiB", size <= 65_536))
                assertEquals("Measurement must never alter or truncate the open draft", text, input.text.toString())
            } finally {
                // Never send the synthetic oversized Bundle through real Activity state transport.
                input.setText("")
                SettingsActivity::class.java.getDeclaredMethod("closeDialogs").apply { isAccessible = true }.invoke(activity)
            }
        }
    }

    private fun openRoute(route: String) {
        instrumentation.startActivitySync(Intent(instrumentation.targetContext, SettingsActivity::class.java)
            .putExtra("page", route).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.waitForIdleSync()
    }

    private fun ownershipCounts() = JSONObject().put("destroyedActivities", destroyed.size)
        .put("destroyedActivitiesStillReachable", destroyed.count { it.get() != null })
        .put("closedDialogsStillReachable", oldDialogs.count { it.get() != null })
        .put("detachedPickersStillReachable", oldPickers.count { it.get() != null })

    private fun open(dark: Boolean) {
        instrumentation.startActivitySync(Intent(instrumentation.targetContext, SettingsActivity::class.java)
            .putExtra("page", if (dark) "theme_dark" else "theme_light")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.waitForIdleSync()
        check(current.get() != null)
    }

    private fun openPicker() {
        instrumentation.checkedMain {
            val activity = requireNotNull(current.get())
            val button = descendants(activity.window.decorView).filterIsInstance<Button>().single { it.tag == "theme_token_accent" }
            check(button.performClick())
            val dialog = requireNotNull(dialog(activity))
            oldDialogs.add(WeakReference(dialog))
            val plane = descendants(dialog.window!!.decorView).filterIsInstance<ColorPickerView>().single { it.tag == "color_sv" }
            oldPickers.add(WeakReference(plane))
            val event = MotionEvent.obtain(0, 1, MotionEvent.ACTION_DOWN, plane.width * .6f, plane.height * .4f, 0)
            try { plane.onTouchEvent(event) } finally { event.recycle() }
            descendants(dialog.window!!.decorView).filterIsInstance<EditText>().single { it.tag == "color_hex" }.setText("#223344")
        }
        instrumentation.waitForIdleSync()
    }

    private fun closePicker(mode: Int) {
        var closingPlane: ColorPickerView? = null
        instrumentation.checkedMain {
            val activity = requireNotNull(current.get())
            val dialog = requireNotNull(dialog(activity))
            val plane = descendants(dialog.window!!.decorView).filterIsInstance<ColorPickerView>().single { it.tag == "color_sv" }
            oldDialogs.add(WeakReference(dialog))
            oldPickers.add(WeakReference(plane))
            closingPlane = plane
            when (mode) {
                0 -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                1 -> dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                else -> dialog.onBackPressed()
            }
        }
        // Android queues OnDismiss on main. Inspect ownership after that callback can run,
        // not inside the same event that requested dismissal. Keep both original invariants.
        try {
            instrumentation.waitForIdleSync()
            instrumentation.checkedMain {
                assertNull("Dismiss must detach view callback", requireNotNull(closingPlane).onChange)
                assertNull(dialog(requireNotNull(current.get())))
            }
        } finally {
            closingPlane = null
        }
    }

    private fun rotateActual() {
        var target = Configuration.ORIENTATION_UNDEFINED
        var old = WeakReference<SettingsActivity>(null)
        instrumentation.checkedMain {
            val activity = requireNotNull(current.get())
            old = WeakReference(activity)
            target = if (activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
            activity.requestedOrientation = if (target == Configuration.ORIENTATION_LANDSCAPE) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        waitUntil("Actual orientation and new Activity") { current.get()?.let { it !== old.get() && it.resources.configuration.orientation == target } == true }
    }

    private fun recreateOnly() {
        val previousIdentity = current.get()?.let { System.identityHashCode(it) }
        instrumentation.checkedMain { requireNotNull(current.get()).recreate() }
        waitUntil("Activity.recreate completion") { current.get()?.let { System.identityHashCode(it) != previousIdentity && dialog(it) != null } == true }
    }

    private fun finishCurrent() {
        instrumentation.checkedMain { current.get()?.finish() }
        waitUntil("Settings Activity destruction") { current.get() == null }
        instrumentation.waitForIdleSync()
    }

    private fun waitUntil(description: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            var done = false
            instrumentation.checkedMain { done = predicate() }
            if (done) return
            SystemClock.sleep(50)
        }
        fail(description)
    }

    private fun dialog(activity: SettingsActivity): AlertDialog? {
        val editor = SettingsActivity::class.java.getDeclaredField("editor").apply { isAccessible = true }.get(activity) as KeyboardThemeEditor
        return KeyboardThemeEditor::class.java.getDeclaredField("activeDialog").apply { isAccessible = true }.get(editor) as AlertDialog?
    }
    private fun field(activity: SettingsActivity, name: String, value: Any) = SettingsActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }
}
