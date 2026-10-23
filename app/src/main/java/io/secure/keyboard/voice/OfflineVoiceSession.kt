package io.secure.keyboard.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * One-shot microphone session. No disk audio, SpeechRecognizer, logging, or text persistence.
 * Only endpoint-finalized segments may be committed; previews are display-only. The owner must
 * validate the captured editor and cancel on editor/window/selection changes.
 */
class OfflineVoiceSession(context: Context, private val listener: Listener) {
    enum class State {
        IDLE,
        LOADING,
        LISTENING,
        PROCESSING,
    }

    interface Listener {
        fun onLevel(level: Float) {}

        fun onState(state: State)

        fun onPreview(text: String, isFinal: Boolean)

        /** Unique, increasing identity within this begin(); identical spoken phrases are distinct. */
        fun onSegment(segmentId: Long, text: String) {}

        fun onError(message: String)
    }

    private val context = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0)
    private val captureEnvironment = VoiceCaptureEnvironment(context)
    private val microphoneGate = MicrophoneLifecycleGate()
    @Volatile private var recording = false
    @Volatile private var busy = false
    @Volatile private var released = false
    @Volatile private var activeRecorder: AudioRecord? = null
    // Main-thread-only, bounded restart request while the prior worker releases its native state.
    private val levels = LatestVoiceLevel({ main.postDelayed(it, 50L) }, { main.removeCallbacks(it) }) { id, level ->
        if (!released && generation.get() == id && recording) listener.onLevel(level)
    }
    private var pendingBegin = false
    private var limitStop: Runnable? = null

    /** Call public lifecycle methods on the main thread. */
    fun begin() {
        if (released) return
        if (busy) {
            if (!recording) {
                // cancel()/stop() can return before native cleanup finishes. Keep one new start,
                // suppress the old generation, and let cleanup start it on this same session.
                generation.incrementAndGet()
                pendingBegin = true
                listener.onPreview("", false)
                listener.onState(State.LOADING)
            }
            return
        }
        if (
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            listener.onError("请在语音设置中允许麦克风权限")
            listener.onState(State.IDLE)
            return
        }
        val id = generation.incrementAndGet()
        recording = true
        busy = true
        listener.onPreview("", false)
        listener.onState(State.LOADING)
        worker.execute { capture(id) }
    }

    fun stop() {
        levels.clear()
        // A held gesture can be released while begin() waits for an older canceled worker.
        // That queued begin belongs to the lifted finger and must never start afterward.
        val stoppedPendingBegin = pendingBegin
        pendingBegin = false
        // Make the stop flag and AudioRecord.stop atomic with capture's check/start. A lifted
        // hold must never stop an initialized recorder only for the worker to start it afterward.
        microphoneGate.stop {
            recording = false
            try {
                activeRecorder?.stop()
            } catch (_: Exception) {}
            captureEnvironment.close()
        }
        limitStop?.let { main.removeCallbacks(it) }
        limitStop = null
        if (stoppedPendingBegin && !released) listener.onState(State.IDLE)
    }

    fun cancel() {
        pendingBegin = false
        generation.incrementAndGet()
        stop()
        if (!released) {
            listener.onPreview("", false)
            listener.onState(State.IDLE)
        }
    }

    fun release() {
        if (released) return
        cancel()
        released = true
        levels.close()
        worker.shutdown() // Running worker always releases native stream, recorder and recognizer.
    }

    private fun emit(id: Long, action: () -> Unit) {
        main.post { if (!released && generation.get() == id) action() }
    }

    private fun capture(id: Long) {
        var recognizer: OnlineRecognizer? = null
        var stream: OnlineStream? = null
        var recorder: AudioRecord? = null
        val pcm = ShortArray(1600)
        var floats: FloatArray? = null
        try {
            // Cancel before disk hashing, and between chunks, rather than finishing a large
            // obsolete read after the user has released the key or changed editors.
            if (generation.get() != id || !recording) return
            val repository = ModelRepository(context)
            if (!repository.verifyInstalled { generation.get() == id && recording }) return
            if (generation.get() != id || !recording) return
            val engine =
                OnlineRecognizer(
                    config =
                        OnlineRecognizerConfig(
                            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                            modelConfig =
                                OnlineModelConfig(
                                    zipformer2Ctc =
                                        OnlineZipformer2CtcModelConfig(
                                            model = repository.modelFile.absolutePath
                                        ),
                                    tokens = repository.tokensFile.absolutePath,
                                    numThreads = 2,
                                    provider = "cpu",
                                    debug = false,
                                ),
                            endpointConfig = endpointConfig(),
                            enableEndpoint = true,
                            decodingMethod = "greedy_search",
                        )
                )
            recognizer = engine
            val input = engine.createStream()
            stream = input
            if (generation.get() != id || !recording) return
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                throw SecurityException("麦克风权限已撤销")
            val mic = MicrophoneCapture.start(context, captureEnvironment, microphoneGate,
                { generation.get() == id && recording }) { activeRecorder = it } ?: return
            recorder = mic
            val status = MicrophoneStatus(mic)
            val signal = CaptureSignalHealth()
            emit(id) { if (recording) listener.onState(State.LISTENING) }
            val deadline = SystemClock.elapsedRealtime() + 60_000
            emit(id) {
                if (recording) {
                    limitStop = Runnable { if (generation.get() == id) stop() }.also {
                        main.postDelayed(it, 60_000)
                    }
                }
            }
            val progress = AudioReadProgress(SystemClock.elapsedRealtime())
            var samples = 0
            var previous = ""
            var segmentId = 0L
            while (recording && generation.get() == id && samples < 16000 * 60 &&
                SystemClock.elapsedRealtime() < deadline) {
                status.checkAvailable()
                captureEnvironment.checkAvailable()
                val count = mic.read(pcm, 0, pcm.size, AudioRecord.READ_NON_BLOCKING)
                if (count <= 0 && !recording) break
                check(count >= 0) { "麦克风读取失败" }
                if (generation.get() != id) return
                check(progress.accept(count, SystemClock.elapsedRealtime())) { "麦克风未返回录音数据，请关闭语音后重试" }
                if (count == 0) { Thread.sleep(10); continue }
                check(signal.accept(pcm, 0, count)) { "麦克风录音数据异常，请重试" }
                val level = VoiceLevel.measure(pcm, 0, count)
                levels.offer(id, level)
                samples += count
                val chunk = FloatArray(count) { pcm[it] / 32768f }
                floats = chunk
                input.acceptWaveform(chunk, 16000)
                chunk.fill(0f)
                floats = null
                pcm.fill(0)
                while (engine.isReady(input)) engine.decode(input)
                val result = engine.getResult(input).text.trim()
                if (result != previous) {
                    previous = result
                    emit(id) { listener.onPreview(result, false) }
                }
                if (engine.isEndpoint(input)) {
                    // sherpa 1.13.8 CTC reset clears its result and decoding state. Never resend
                    // the full session text, and never use text equality to deduplicate speech.
                    engine.reset(input)
                    val finalizedId = segmentId++
                    if (result.isNotEmpty()) emit(id) { listener.onSegment(finalizedId, result) }
                    previous = ""
                    emit(id) { listener.onPreview("", false) }
                }
                // Native decoding time is not a microphone stall. Preserve final positive reads
                // after stop(), then let the existing final flush finish the utterance.
                progress.accept(count, SystemClock.elapsedRealtime())
            }
            status.checkAvailable()
            microphoneGate.stop {
                recording = false
                try {
                    mic.stop()
                } catch (_: Exception) {}
                captureEnvironment.close()
            }
            if (generation.get() != id) return
            check(signal.hasVariation) { "未采集到有效声音，请检查麦克风后重试" }
            emit(id) { listener.onState(State.PROCESSING) }
            // Flush final streaming chunk, including the model's right-context lookahead.
            input.acceptWaveform(FloatArray(8000), 16000)
            input.inputFinished()
            while (engine.isReady(input)) engine.decode(input)
            val result = engine.getResult(input).text.trim()
            if (result.isNotEmpty()) {
                val finalizedId = segmentId
                emit(id) { listener.onSegment(finalizedId, result) }
            }
            emit(id) { listener.onPreview(result, true) }
        } catch (e: SecurityException) {
            emit(id) { listener.onError("麦克风权限不可用，请在语音设置中重新允许") }
        } catch (e: Exception) {
            emit(id) { listener.onError(e.message ?: "离线识别失败，请重试") }
        } catch (e: LinkageError) {
            emit(id) { listener.onError("此设备无法加载离线识别引擎") }
        } finally {
            levels.clear()
            pcm.fill(0)
            floats?.fill(0f)
            microphoneGate.stop {
                recording = false
                try {
                    recorder?.stop()
                } catch (_: Exception) {}
                captureEnvironment.close()
                // Clear under the same gate before release, so main-thread stop cannot race
                // native release through a recorder reference captured just before cleanup.
                activeRecorder = null
            }
            var cleanupFailed = false
            // Native releases are independent: a failure in one must never skip the others.
            fun releaseSafely(action: () -> Unit) {
                try {
                    action()
                } catch (_: Throwable) {
                    cleanupFailed = true
                }
            }
            releaseSafely { recorder?.release() }
            releaseSafely { stream?.release() }
            releaseSafely { recognizer?.release() }
            main.post {
                busy = false
                if (!released) {
                    limitStop?.let { main.removeCallbacks(it) }
                    limitStop = null
                    val restart = pendingBegin
                    pendingBegin = false
                    if (cleanupFailed && (restart || generation.get() == id)) {
                        listener.onError("离线识别资源释放失败，请重试")
                        listener.onState(State.IDLE)
                    } else if (restart) {
                        begin()
                    } else if (generation.get() == id) {
                        listener.onState(State.IDLE)
                    }
                }
            }
        }
    }

    companion object {
        /** Explicit upstream defaults, shared with native endpoint regression tests. */
        internal fun endpointConfig() = EndpointConfig(
            rule1 = EndpointRule(false, 2.4f, 0.0f),
            rule2 = EndpointRule(true, 1.4f, 0.0f),
            rule3 = EndpointRule(false, 0.0f, 20.0f),
        )
    }
}
