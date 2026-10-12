package io.secure.keyboard

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.widget.Button
import android.widget.TextView
import io.secure.keyboard.voice.ModelImportCoordinator
import io.secure.keyboard.voice.ModelImportTransfer
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Actual Activity lifecycle + the production process owner/worker/main dispatcher, using gated
 * synthetic work only. No provider/model/audio/permissions/settings are changed. Run serially in
 * an instrumentation process after C is built. The separate IMPORT_LOCK diagnostic exercises the
 * real repository before/after and attributes heap ownership; these tests are exact UI invariants.
 */
@Suppress("DEPRECATION")
class VoiceImportLifecycleTest : InstrumentationTestCase() {
    private lateinit var app: Application
    private lateinit var owner: ModelImportCoordinator
    @Volatile private var current = WeakReference<VoiceSetupActivity>(null)
    @Volatile private var cover = WeakReference<MainActivity>(null)
    private val destroyed = mutableListOf<WeakReference<VoiceSetupActivity>>()
    private var release: CountDownLatch? = null
    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) {
            if (activity is VoiceSetupActivity) current = WeakReference(activity)
            if (activity is MainActivity) cover = WeakReference(activity)
        }
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) {
            if (activity is VoiceSetupActivity) {
                destroyed.add(WeakReference(activity))
                if (current.get() === activity) current.clear()
            }
            if (cover.get() === activity) cover.clear()
        }
    }

    override fun setUp() {
        super.setUp()
        app = instrumentation.targetContext.applicationContext as Application
        onMainChecked {
            // Initialize on main, then inspect existing private state; no production test hook.
            check(ModelImportTransfer.state !== ModelImportCoordinator.State.Running)
            owner = ModelImportTransfer::class.java.getDeclaredField("coordinator")
                .apply { isAccessible = true }.get(null) as ModelImportCoordinator
            assertNull(observer())
            app.registerActivityLifecycleCallbacks(lifecycle)
        }
    }

    override fun tearDown() {
        try {
            release?.countDown()
            await("Synthetic worker completes") { owner.state !== ModelImportCoordinator.State.Running }
            onMainChecked { cover.get()?.finish(); current.get()?.finish() }
            await("Voice screen exits") { current.get() == null }
            onMainChecked {
                assertNull(observer())
                app.unregisterActivityLifecycleCallbacks(lifecycle)
            }
        } finally { super.tearDown() }
    }

    fun testTenRecreationsBackReopenAndDuplicateResultsShareOneTransfer() {
        open()
        startGate()
        assertRunning()
        repeat(10) {
            val previousIdentity = current.get()?.let { System.identityHashCode(it) }
            onMainChecked { requireNotNull(current.get()).recreate() }
            await("Rotation replacement starts") {
                current.get()?.let { System.identityHashCode(it) != previousIdentity } == true
            }
            assertRunning()
            requestDuplicateResult()
            assertRunning()
        }
        assertEquals(10, destroyed.size)
        back()
        onMainChecked { assertNull("Back releases observer immediately", observer()) }
        open()
        assertRunning()
        requestDuplicateResult()
        assertRunning()
        release!!.countDown()
        await("One transfer finishes") { owner.state === ModelImportCoordinator.State.Success }
        onMainChecked {
            assertTrue(importButton().isEnabled)
            assertTrue(status().text.startsWith("模型："))
            assertSame(currentObserver(), observer())
        }
        back()
        open()
        onMainChecked {
            assertSame(ModelImportCoordinator.State.Success, owner.state)
            assertTrue(importButton().isEnabled)
            assertTrue(status().text.startsWith("模型："))
        }
    }

    fun testOnStopDetachesWithoutDestroyAndOnStartReattachesProgress() {
        open()
        startGate()
        onMainChecked {
            requireNotNull(current.get()).startActivity(Intent(instrumentation.targetContext, MainActivity::class.java))
        }
        await("Covering Activity starts and voice stops") { cover.get() != null && observer() == null }
        onMainChecked {
            assertFalse(requireNotNull(current.get()).isDestroyed)
            assertSame(ModelImportCoordinator.State.Running, owner.state)
            cover.get()?.finish()
        }
        await("Voice restarts and reattaches") { cover.get() == null && observer() === currentObserver() }
        assertRunning()
    }

    fun testFailureAfterBackSurvivesReopenAndRetrySucceeds() {
        open()
        startGate("controlled import error")
        back()
        onMainChecked { assertNull(observer()) }
        release!!.countDown()
        await("Failure is kept without an Activity") { owner.state is ModelImportCoordinator.State.Failure }
        onMainChecked { assertNull(observer()) }
        open()
        onMainChecked {
            assertEquals("controlled import error", status().text.toString())
            assertTrue(importButton().isEnabled)
        }
        startGate()
        assertRunning()
        release!!.countDown()
        await("Retry succeeds") { owner.state === ModelImportCoordinator.State.Success }
        onMainChecked {
            assertTrue(importButton().isEnabled)
            assertTrue(status().text.startsWith("模型："))
        }
    }

    private fun startGate(failure: String? = null) {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        release = gate
        val work = gatedWork(entered, gate, failure)
        onMainChecked { assertTrue(owner.start(work)) }
        assertTrue("Worker begins", entered.await(5, TimeUnit.SECONDS))
    }

    private fun requestDuplicateResult() {
        onMainChecked {
            assertFalse("Import action stays disabled", importButton().isEnabled)
            val callback = VoiceSetupActivity::class.java.getDeclaredMethod("onActivityResult",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java)
                .apply { isAccessible = true }
            callback.invoke(requireNotNull(current.get()), 101, Activity.RESULT_OK,
                Intent().setData(Uri.parse("content://io.secure.keyboard.diagnostic.unregistered/tree/duplicate")))
            assertFalse("There is still only one pending transfer", owner.start { error("duplicate executed") })
        }
    }

    private fun assertRunning() = onMainChecked {
        assertSame(ModelImportCoordinator.State.Running, owner.state)
        assertFalse(importButton().isEnabled)
        assertEquals("正在复制和校验模型…", status().text.toString())
        assertSame("Only current started Activity is observed", currentObserver(), observer())
        for (reference in destroyed) reference.get()?.let {
            val previous = VoiceSetupActivity::class.java.getDeclaredField("importObserver")
                .apply { isAccessible = true }.get(it)
            assertNotSame("A destroyed Activity observer is never retained", previous, observer())
        }
    }

    private fun open() {
        instrumentation.startActivitySync(Intent(instrumentation.targetContext, VoiceSetupActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await("Voice screen starts") { current.get() != null && observer() === currentObserver() }
    }

    private fun back() {
        onMainChecked { requireNotNull(current.get()).onBackPressed() }
        await("Back permanently destroys voice screen") { current.get() == null }
    }

    private fun observer(): Any? = ModelImportCoordinator::class.java.getDeclaredField("observer")
        .apply { isAccessible = true }.get(owner)
    private fun currentObserver(): Any? = current.get()?.let {
        VoiceSetupActivity::class.java.getDeclaredField("importObserver").apply { isAccessible = true }.get(it)
    }
    private fun importButton(): Button = requireNotNull(current.get()).window.decorView.findViewWithTag("voice_import_model")
    private fun status(): TextView = requireNotNull(current.get()).window.decorView.findViewWithTag("voice_status")

    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            var ready = false
            onMainChecked { ready = predicate() }
            if (ready) return
            SystemClock.sleep(25)
        }
        fail(message)
    }

    companion object {
        // App-owned worker receives latches and one message only, never test/Activity/View state.
        private fun gatedWork(entered: CountDownLatch, gate: CountDownLatch, failure: String?): () -> Unit = {
            entered.countDown()
            check(gate.await(60, TimeUnit.SECONDS)) { "Synthetic gate timed out" }
            if (failure != null) throw IOException(failure)
        }
    }
    /** Keep diagnostic assertion failures on JUnit's thread instead of crashing Android main. */
    private fun onMainChecked(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try { block() } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }

}
