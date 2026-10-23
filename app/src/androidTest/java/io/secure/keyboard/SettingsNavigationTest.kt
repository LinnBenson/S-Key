package io.secure.keyboard

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import io.secure.keyboard.diagnostics.checkedMain
import android.test.InstrumentationTestCase
import java.lang.ref.WeakReference

/** Back must return to the actual entry point, including after Activity recreation. */
@Suppress("DEPRECATION")
class SettingsNavigationTest : InstrumentationTestCase() {
    private var current = WeakReference<SettingsActivity>(null)
    private val observer = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            if (activity is SettingsActivity) current = WeakReference(activity)
        }
        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
    override fun setUp() {
        super.setUp()
        (instrumentation.targetContext.applicationContext as Application).registerActivityLifecycleCallbacks(observer)
        instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        settle()
    }
    override fun tearDown() {
        instrumentation.checkedMain { current.get()?.finish() }
        (instrumentation.targetContext.applicationContext as Application).unregisterActivityLifecycleCallbacks(observer)
        super.tearDown()
    }
    private fun settle() {
        instrumentation.waitForIdleSync()
        val frames = java.util.concurrent.CountDownLatch(1)
        instrumentation.checkedMain {
            current.get()!!.window.decorView.postOnAnimation {
                current.get()?.window?.decorView?.postOnAnimation { frames.countDown() }
            }
        }
        assertTrue("Compose navigation frames", frames.await(5, java.util.concurrent.TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }
    private fun open(route: String) {
        instrumentation.checkedMain {
            SettingsActivity::class.java.getDeclaredMethod("openPage", String::class.java)
                .apply { isAccessible = true }.invoke(current.get()!!, route)
        }
        settle()
    }
    private fun back(expected: String) {
        instrumentation.checkedMain { current.get()!!.onBackPressedDispatcher.onBackPressed() }
        settle()
        instrumentation.checkedMain {
            val route = SettingsActivity::class.java.getDeclaredMethod("getIncomingPage")
                .apply { isAccessible = true }.invoke(current.get()!!)
            assertEquals(expected, route)
        }
    }
    fun testDashboardAndNestedEntryPointsReturnToTheirCaller() {
        open("about"); back("dashboard")
        open("licenses"); back("dashboard")
        open("input"); back("dashboard")
        open("home"); open("api"); open("voice"); back("api"); back("home"); back("dashboard")
    }
    fun testNewExternalIntentClearsOldCallerAndDraft() {
        open("home"); open("phrases")
        instrumentation.checkedMain {
            val activity = current.get()!!
            val model = androidx.lifecycle.ViewModelProvider(activity)[io.secure.keyboard.settings.SettingsModel::class.java]
            model.edit = io.secure.keyboard.settings.ContentEdit("phrases")
            SettingsActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
                .apply { isAccessible = true }.invoke(activity, Intent(activity, MainActivity::class.java).putExtra("page", "about"))
            assertNull(model.edit)
        }
        settle()
        back("home")
    }
    fun testCallerSurvivesRecreation() {
        open("home"); open("about")
        val previous = current.get()!!
        instrumentation.checkedMain { previous.recreate() }
        repeat(40) {
            settle()
            if (current.get() != null && current.get() !== previous) {
                back("home"); back("dashboard"); return
            }
            android.os.SystemClock.sleep(50)
        }
        fail("Activity did not recreate")
    }
}
