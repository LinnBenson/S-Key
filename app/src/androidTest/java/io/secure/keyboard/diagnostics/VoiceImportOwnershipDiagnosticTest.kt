package io.secure.keyboard.diagnostics

import kotlin.concurrent.withLock
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.test.InstrumentationTestCase
import io.secure.keyboard.VoiceSetupActivity
import io.secure.keyboard.voice.ModelRepository
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.io.File
import java.security.MessageDigest

/**
 * Deterministic IMPORT_LOCK gate. No provider is contacted while held. The deliberately unknown
 * content authority fails before staging/write when released (or an installed verified model
 * returns unchanged). This proves in-flight Activity ownership, NOT SAF stream cancellation.
 * Baseline invariant violations are reported as observations so B can be compared to C.
 */
@Suppress("DEPRECATION")
class VoiceImportOwnershipDiagnosticTest : InstrumentationTestCase() {
    private lateinit var app: Application
    private lateinit var probe: LifetimeMemoryProbe
    @Volatile private var current = WeakReference<VoiceSetupActivity>(null)
    private val destroyed = mutableListOf<WeakReference<*>>()
    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) { if (activity is VoiceSetupActivity) current = WeakReference(activity) }
        override fun onActivityResumed(activity: Activity) { if (activity is VoiceSetupActivity) current = WeakReference(activity) }
        override fun onActivityDestroyed(activity: Activity) {
            if (activity is VoiceSetupActivity) {
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
        app = instrumentation.targetContext.applicationContext as Application
        probe = LifetimeMemoryProbe(instrumentation.targetContext, "voice-import")
        instrumentation.checkedMain { app.registerActivityLifecycleCallbacks(lifecycle) }
    }

    override fun tearDown() {
        try {
            probe.stop()
            finishCurrent()
            instrumentation.checkedMain { app.unregisterActivityLifecycleCallbacks(lifecycle) }
        } finally { super.tearDown() }
    }

    fun testTenRecreationsDuringGatedImportNaturalOwnershipObservation() = runGate(false)
    fun testTenRecreationsDuringGatedImportForcedGcAttributionOnly() = runGate(true)

    private fun runGate(forcedGc: Boolean) {
        val lock = ModelRepository::class.java.getDeclaredField("IMPORT_LOCK").apply { isAccessible = true }.get(null)
        val installedBefore = ModelRepository(instrumentation.targetContext).isInstalled()
        val modelHashesBefore = modelHashes()
        probe.stage = "before_import"
        probe.sample("before_gate", forcedGc = forcedGc)
        if (!forcedGc) probe.start()
        try {
            (lock as java.util.concurrent.locks.ReentrantLock).withLock {
                open()
                requestImport()
                waitUntil("Import reaches controlled lock") { pendingImports() >= 1 }
                repeat(10) { index ->
                    probe.cycle = index + 1
                    recreateOnly()
                    requestImport() // Must attach/decline duplicates once the shared owner exists.
                }
                finishCurrent()
                open()
                requestImport() // Explicit leave/reopen duplicate attempt.
                finishCurrent()
                probe.stage = "gated_all_activities_exited"
                probe.sample("gated_owner_counts", extra = ownership(), forcedGc = forcedGc)
                if (forcedGc) probe.observeWeakReferences("gated_destroyed_activity_collectibility", destroyed, true)
                else probe.recover(checkpoint = true) { ownership() }
                // Leave synchronized block even on assertion/exception: never strand import workers.
            }
            waitUntil("All imports leave lock and finish", 60_000) { pendingImports() == 0 }
            probe.stage = "released_all_activities_exited"
            if (forcedGc) probe.observeWeakReferences("released_destroyed_activity_collectibility", destroyed, true)
            else probe.recover(final = true) { ownership() }
            assertEquals("Diagnostic must not install/delete a real model", installedBefore, ModelRepository(instrumentation.targetContext).isInstalled())
            assertEquals("Installed model, tokens and verification marker bytes must remain exact", modelHashesBefore, modelHashes())
        } finally { probe.stop() }
    }

    private fun ownership(): JSONObject {
        val data = JSONObject().put("gate", "ModelRepository.IMPORT_LOCK")
            .put("providerReadTested", false).put("importsBlockedOrRunning", pendingImports())
            .put("duplicateTransferInvariant", pendingImports() <= 1)
            .put("destroyedActivities", destroyed.size)
            .put("destroyedActivitiesStillReachable", destroyed.count { it.get() != null })
        instrumentation.checkedMain {
            try {
                val transfer = Class.forName("io.secure.keyboard.voice.ModelImportTransfer")
                val coordinator = transfer.getDeclaredField("coordinator").apply { isAccessible = true }.get(null)
                val observer = coordinator.javaClass.getDeclaredField("observer").apply { isAccessible = true }.get(coordinator)
                val activity = current.get()
                val expected = activity?.let { io.secure.keyboard.SettingsActivity::class.java.getDeclaredField("voiceObserver").apply { isAccessible = true }.get(it) }
                data.put("appOwnedObserverAvailable", true)
                    .put("observerPresent", observer != null)
                    .put("observerMatchesCurrentActivityInvariant", observer === expected)
                assertSame("Transfer must own only the started Activity observer, or null after exit", expected, observer)
            } catch (_: ClassNotFoundException) {
                data.put("appOwnedObserverAvailable", false).put("baselineOwnership", "Activity-captured worker")
            }
        }
        return data
    }

    private fun modelHashes(): Map<String, String> {
        val repository = ModelRepository(instrumentation.targetContext)
        val files = listOf(repository.modelFile, repository.tokensFile, File(repository.modelFile.parentFile, "verified"))
        return files.filter { it.exists() }.associate { file ->
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65_536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            file.name to "${file.length()}:" + digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }

    private fun pendingImports(): Int = Thread.getAllStackTraces().values.count { frames ->
        frames.any { it.className == ModelRepository::class.java.name && it.methodName == "importTree" }
    }

    private fun open() {
        instrumentation.startActivitySync(Intent(instrumentation.targetContext, VoiceSetupActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.waitForIdleSync()
        check(current.get() != null)
    }

    private fun requestImport() {
        instrumentation.checkedMain {
            io.secure.keyboard.voice.ModelImportTransfer.start(instrumentation.targetContext,
                Uri.parse("content://io.secure.keyboard.diagnostic.unregistered/tree/gated"))
        }
    }

    private fun recreateOnly() {
        val oldIdentity = current.get()?.let { System.identityHashCode(it) }
        instrumentation.checkedMain { requireNotNull(current.get()).recreate() }
        waitUntil("Activity.recreate completed") { current.get()?.let { System.identityHashCode(it) != oldIdentity } == true }
    }

    private fun finishCurrent() {
        instrumentation.checkedMain { current.get()?.finish() }
        waitUntil("VoiceSetupActivity destroyed") { current.get() == null }
        instrumentation.waitForIdleSync()
    }

    private fun waitUntil(message: String, millis: Long = 15_000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + millis
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            if (predicate()) return
            SystemClock.sleep(50)
        }
        fail(message)
    }
}
