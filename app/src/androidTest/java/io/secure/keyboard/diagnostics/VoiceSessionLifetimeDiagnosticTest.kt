package io.secure.keyboard.diagnostics

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.test.InstrumentationTestCase
import io.secure.keyboard.voice.ModelRepository
import io.secure.keyboard.voice.OfflineVoiceSession
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.concurrent.ExecutorService

/**
 * Real production OfflineVoiceSession + AudioRecord, inside an explicitly instrumented service
 * fixture. Fixture files must already exist locally, match pinned hashes and never ship in APKs.
 * busy=false is posted AFTER all cleanup attempts; it is not an instantaneous cancel-UI promise.
 * This does not add JNI live-handle counters and does not claim a physical-device audio result.
 */
@Suppress("DEPRECATION")
class VoiceSessionLifetimeDiagnosticTest : InstrumentationTestCase() {
    private lateinit var fixture: LifetimeServiceFixture
    private lateinit var session: OfflineVoiceSession
    private lateinit var probe: LifetimeMemoryProbe
    @Volatile private var state = OfflineVoiceSession.State.IDLE
    @Volatile private var errors = 0
    @Volatile private var previews = 0
    @Volatile private var segments = 0
    @Volatile private var beginAttempts = 0
    private val stateEvents = mutableListOf<JSONObject>()

    override fun setUp() {
        super.setUp()
        check(instrumentation.targetContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "Grant only the disposable emulator microphone permission before this diagnostic" }
        fixture = LifetimeServiceFixture(instrumentation).also { it.open() }
        val source = File(instrumentation.targetContext.filesDir, "voice-fixtures")
        val destination = File(fixture.sandbox.noBackupFilesDir, "mandarin-voice-v1").apply { mkdirs() }
        for (name in listOf("model.int8.onnx", "tokens.txt")) {
            check(File(source, name).isFile) { "Push pinned existing voice fixtures first; no download/bundling in this test" }
            File(source, name).copyTo(File(destination, name), overwrite = false)
        }
        File(destination, "verified").writeText(ModelRepository.MODEL_ID)
        ModelRepository(fixture.sandbox).verifyInstalled() // Size and SHA-256 for both pinned files.
        probe = LifetimeMemoryProbe(instrumentation.targetContext, "voice-session")
        instrumentation.checkedMain {
            session = OfflineVoiceSession(fixture.sandbox, object : OfflineVoiceSession.Listener {
                override fun onState(value: OfflineVoiceSession.State) {
                    state = value
                    synchronized(stateEvents) {
                        stateEvents.add(JSONObject().put("state", value.name)
                            .put("elapsedMillis", SystemClock.elapsedRealtime()).put("beginAttempts", beginAttempts))
                    }
                }
                override fun onPreview(text: String, isFinal: Boolean) { previews++ }
                override fun onSegment(segmentId: Long, text: String) { segments++ }
                override fun onError(message: String) { errors++ } // Never persist speech/error payloads.
            })
            fixture.set("voice", session)
        }
    }

    override fun tearDown() {
        try {
            if (::probe.isInitialized) probe.stop()
            if (::session.isInitialized) {
                instrumentation.checkedMain { session.release() }
                fixture.await("Native worker cleanup after release", 60_000) { !sessionField<Boolean>("busy") }
                check(sessionField<ExecutorService>("worker").awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
            }
            if (::fixture.isInitialized) fixture.close()
        } finally { super.tearDown() }
    }

    fun testSingleNativeSessionCalibrationNoForcedGc() {
        val started = SystemClock.elapsedRealtime()
        start()
        fixture.await("Calibration microphone listening") { state == OfflineVoiceSession.State.LISTENING || errors != 0 }
        assertEquals(0, errors)
        SystemClock.sleep(2000)
        val stopRequested = SystemClock.elapsedRealtime()
        instrumentation.checkedMain { session.stop() }
        fixture.await("Calibration cleanup") { !sessionField<Boolean>("busy") }
        val cleaned = SystemClock.elapsedRealtime()
        assertEquals(0, errors)
        assertNull(sessionField<Any?>("activeRecorder"))
        probe.recover(checkpoint = true, operationEndMillis = cleaned)
        probe.sample("calibration_complete", extra = resourceState().put("calibrationOnly", true)
            .put("nativeSessionCycleMillisIncluding2sListeningAnd10sCheckpointRecovery", SystemClock.elapsedRealtime() - started)
            .put("stopToCleanupMillis", cleaned - stopRequested))
    }

    fun testTwelveStopCancelAndEditorLifecycleCyclesNoForcedGc() {
        probe.sample("before_first_jni", extra = resourceState())
        probe.start()
        try {
            repeat(12) { index ->
                probe.cycle = index + 1
                probe.stage = when (index / 3) { 0 -> "normal_stop"; 1 -> "loading_cancel"; 2 -> "listening_cancel"; else -> "editor_lifecycle_cancel" }
                start()
                if (index / 3 != 1) {
                    fixture.await("Real AudioRecord reaches LISTENING") { state == OfflineVoiceSession.State.LISTENING || errors != 0 }
                    assertEquals("Native init/microphone error", 0, errors)
                    SystemClock.sleep(2000)
                }
                val stopRequested = SystemClock.elapsedRealtime()
                instrumentation.checkedMain {
                    when (index) {
                        in 0..2 -> session.stop()
                        in 3..5 -> {
                            session.cancel()
                            if (index == 5) {
                                // Held release while restart is queued must cancel the pending begin.
                                beginAttempts++; session.begin(); session.stop()
                            }
                        }
                        in 6..8 -> session.cancel()
                        9 -> fixture.service.onFinishInputView(false)
                        10 -> fixture.replaceHost()
                        11 -> fixture.service.onFinishInput()
                    }
                }
                val uiReturn = SystemClock.elapsedRealtime()
                if (index == 8) {
                    // Successful cancel→immediate restart, still one labeled repeat-cycle.
                    start()
                    fixture.await("Immediate restart reaches LISTENING") { state == OfflineVoiceSession.State.LISTENING || errors != 0 }
                    assertEquals(0, errors)
                    instrumentation.checkedMain { session.cancel() }
                }
                fixture.await("Actual worker cleanup within 60 seconds") { !sessionField<Boolean>("busy") }
                val cleaned = SystemClock.elapsedRealtime()
                assertNull("No active AudioRecord after cleanup", sessionField<Any?>("activeRecorder"))
                assertFalse(sessionField<Boolean>("recording"))
                assertFalse(sessionField<Boolean>("pendingBegin"))
                assertEquals("Cleanup/init errors are failures", 0, errors)
                probe.sample("cleanup_completed", extra = resourceState().put("cancelUiReturnMillis", uiReturn - stopRequested)
                    .put("cleanupAfterRequestMillis", cleaned - stopRequested).put("nativeHandleCounterAvailable", false)
                    .put("cleanupEvidence", "Production finally completed all release attempts before busy=false; no error callback; AudioRecord field null"))
                probe.recover(checkpoint = index in listOf(0, 5, 11), final = index == 11, operationEndMillis = cleaned) { resourceState() }
            }
        } finally { probe.stop() }
    }

    /** Run ONCE per frozen B/C build, not in every pair, absent a specific repeatability failure. */
    fun testProductionSixtySecondCapOncePerBuildNoForcedGc() {
        probe.stage = "sixty_second_cap"
        probe.start()
        try {
            start()
            fixture.await("Listening") { state == OfflineVoiceSession.State.LISTENING || errors != 0 }
            assertEquals(0, errors)
            val listening = SystemClock.elapsedRealtime()
            fixture.await("Production cap stops and releases native session", 75_000) { !sessionField<Boolean>("busy") }
            val cleaned = SystemClock.elapsedRealtime()
            val duration = cleaned - listening
            assertTrue("Must actually exercise the long session", duration >= 50_000)
            assertTrue("Cap plus cleanup must complete within 75 seconds after LISTENING", duration <= 75_000)
            assertNull(sessionField<Any?>("activeRecorder"))
            assertEquals(0, errors)
            probe.sample("cap_cleanup_complete", extra = resourceState().put("listeningToCleanupMillis", duration))
            probe.recover(final = true, operationEndMillis = cleaned) { resourceState() }
        } finally { probe.stop() }
    }

    private fun start() = instrumentation.checkedMain { beginAttempts++; session.begin() }
    @Suppress("UNCHECKED_CAST") private fun <T> sessionField(name: String): T = OfflineVoiceSession::class.java.getDeclaredField(name).apply { isAccessible = true }.get(session) as T
    private fun resourceState() = JSONObject().put("busy", sessionField<Boolean>("busy"))
        .put("recording", sessionField<Boolean>("recording"))
        .put("activeRecorderPresent", sessionField<Any?>("activeRecorder") != null)
        .put("pendingBegin", sessionField<Boolean>("pendingBegin"))
        .put("state", state.name).put("errors", errors).put("previewCallbackCount", previews)
        .put("segmentCallbackCount", segments).put("beginAttempts", beginAttempts)
        .put("stateEvents", synchronized(stateEvents) { JSONArray(stateEvents.toList()) })
}
