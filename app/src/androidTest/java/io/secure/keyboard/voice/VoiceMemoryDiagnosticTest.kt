package io.secure.keyboard.voice

import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.test.AndroidTestCase
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Diagnostic only. Run this method ALONE in a fresh instrumented process with pushed fixtures.
 * Never calls GC or sets a portable memory threshold. Includes instrumentation/framework overhead;
 * it is not an idle keyboard measurement or a substitute for the user's physical Samsung device.
 * Snapshot parsing/logging itself allocates temporary Java objects; automatic GC is allowed and
 * counted. File-backed library PSS excludes anonymous model weights and allocator arenas.
 *
 * Output: files/voice-memory-diagnostic.jsonl and logcat tag VoiceMemory. MemoryInfo/smaps kB are
 * reported as KiB; Runtime/native allocator measurements are bytes, not resident-memory estimates.
 */
@Suppress("DEPRECATION")
class VoiceMemoryDiagnosticTest : AndroidTestCase() {
    private val libraryNames =
        listOf(
            "libonnxruntime.so",
            "libsherpa-onnx-jni.so",
            "libsherpa-onnx-c-api.so",
            "libsherpa-onnx-cxx-api.so",
        )
    private val header = Regex("^[0-9a-fA-F]+-[0-9a-fA-F]+\\s+.*")
    private val report
        get() = File(context.filesDir, "voice-memory-diagnostic.jsonl")

    private val fixtureDir
        get() = File(context.filesDir, "voice-fixtures")

    fun testColdLoadedDecodedReleasedMemoryWithoutForcedGc() {
        report.writeText("")
        val cold = sample("cold_before_fixture_read_and_jni", 0)
        assertTrue("Cold JNI baseline unavailable: run this method alone in a fresh process", cold)
        verifyFile("model.int8.onnx", ModelRepository.MODEL_SIZE, ModelRepository.MODEL_SHA)
        verifyFile("tokens.txt", ModelRepository.TOKENS_SIZE, ModelRepository.TOKENS_SHA)
        verifyFile(
            "0.wav",
            null,
            "668bf8df51a10027b84d5d8816a1ce11ae93545538dc05cfe2aa6811d399c250",
        )
        val audio = readWave(File(fixtureDir, "0.wav"))
        sample("fixtures_ready_before_jni", 0)
        try {
            repeat(3) { index ->
                val cycle = index + 1
                sample("before_create", cycle)
                runNativeCycle(audio, cycle)
                // runNativeCycle returned: no test-stack references to recognizer/stream remain.
                sample("released_immediate", cycle)
                Thread.sleep(2000)
                sample("released_after_2s", cycle)
                Thread.sleep(8000)
                sample("released_after_10s", cycle)
            }
        } finally {
            audio.fill(0f)
        }
        sample("all_cycles_done_no_forced_gc", 3)
    }

    private fun runNativeCycle(audio: FloatArray, cycle: Int) {
        val engine =
            OnlineRecognizer(
                config =
                    OnlineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                        modelConfig =
                            OnlineModelConfig(
                                zipformer2Ctc =
                                    OnlineZipformer2CtcModelConfig(
                                        File(fixtureDir, "model.int8.onnx").absolutePath
                                    ),
                                tokens = File(fixtureDir, "tokens.txt").absolutePath,
                                numThreads = 2,
                                provider = "cpu",
                                debug = false,
                            ),
                        enableEndpoint = false,
                        decodingMethod = "greedy_search",
                    )
            )
        try {
            sample("recognizer_loaded_no_stream", cycle)
            val stream = engine.createStream()
            try {
                sample("stream_created", cycle)
                var position = 0
                while (position < audio.size) {
                    val chunk = audio.copyOfRange(position, minOf(position + 1600, audio.size))
                    stream.acceptWaveform(chunk, 16000)
                    chunk.fill(0f)
                    position += 1600
                    while (engine.isReady(stream)) engine.decode(stream)
                }
                stream.acceptWaveform(FloatArray(8000), 16000)
                stream.inputFinished()
                while (engine.isReady(stream)) engine.decode(stream)
                assertEquals("对我做了介绍那么我想说的是呢大家如果对我的研究感兴趣呢", engine.getResult(stream).text.trim())
                sample("utterance_decoded_stream_live", cycle)
            } finally {
                stream.release()
            }
            sample("stream_released_recognizer_live", cycle)
        } finally {
            engine.release()
        }
    }

    /**
     * Returns true only when smaps was readable and none of the tracked native libraries mapped.
     */
    private fun sample(stage: String, cycle: Int): Boolean {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        val runtime = Runtime.getRuntime()
        val entry =
            JSONObject()
                .put("stage", stage)
                .put("cycle", cycle)
                .put("pid", Process.myPid())
                .put("uptimeMillis", SystemClock.uptimeMillis())
                .put("sdk", Build.VERSION.SDK_INT)
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("abis", Build.SUPPORTED_ABIS.joinToString(","))
                .put("pssKiB", info.totalPss)
                .put("privateDirtyKiB", info.totalPrivateDirty)
                .put("privateCleanKiB", info.totalPrivateClean)
                .put("nativePssKiB", info.nativePss)
                .put("dalvikPssKiB", info.dalvikPss)
                .put("otherPssKiB", info.otherPss)
                .put("nativeAllocatedBytes", Debug.getNativeHeapAllocatedSize())
                .put("nativeHeapSizeBytes", Debug.getNativeHeapSize())
                .put("nativeHeapFreeBytes", Debug.getNativeHeapFreeSize())
                .put("javaUsedBytes", runtime.totalMemory() - runtime.freeMemory())
                .put("javaCommittedBytes", runtime.totalMemory())
                .put("javaLimitBytes", runtime.maxMemory())
                .put("forcedGcRequested", false)
        val summaries = JSONObject()
        for (name in
            listOf(
                "java-heap",
                "native-heap",
                "code",
                "stack",
                "graphics",
                "private-other",
                "system",
                "total-swap",
            )) {
            summaries.put(name, info.getMemoryStat("summary.$name") ?: JSONObject.NULL)
        }
        entry.put("androidSummaryKiB", summaries)
        entry.put("artGcCount", Debug.getRuntimeStat("art.gc.gc-count") ?: JSONObject.NULL)
        entry.put("artGcTime", Debug.getRuntimeStat("art.gc.gc-time") ?: JSONObject.NULL)
        val libraries = JSONObject()
        val measures = libraryNames.associateWith { mutableMapOf<String, Long>() }
        var smapsError: String? = null
        try {
            var active: String? = null
            File("/proc/self/smaps").forEachLine { line ->
                if (header.matches(line)) {
                    active = libraryNames.firstOrNull { line.contains(it) }
                    active?.let { name ->
                        val values = measures.getValue(name)
                        values["mappings"] = (values["mappings"] ?: 0L) + 1
                    }
                } else {
                    val name = active
                    if (name != null) {
                        val key = line.substringBefore(':')
                        if (key in listOf("Size", "Rss", "Pss", "Private_Clean", "Private_Dirty")) {
                            val value =
                                line.substringAfter(':').trim().substringBefore(' ').toLongOrNull()
                                    ?: 0L
                            val values = measures.getValue(name)
                            values[key] = (values[key] ?: 0L) + value
                        }
                    }
                }
            }
        } catch (error: Exception) {
            smapsError = error.javaClass.simpleName
        }
        for ((name, values) in measures) {
            libraries.put(
                name,
                JSONObject()
                    .put("mappingCount", values["mappings"] ?: 0L)
                    .put("virtualSizeKiB", values["Size"] ?: 0L)
                    .put("rssKiB", values["Rss"] ?: 0L)
                    .put("pssKiB", values["Pss"] ?: 0L)
                    .put("privateCleanKiB", values["Private_Clean"] ?: 0L)
                    .put("privateDirtyKiB", values["Private_Dirty"] ?: 0L),
            )
        }
        entry.put("fileBackedVoiceLibraries", libraries)
        entry.put("smapsReadError", smapsError ?: JSONObject.NULL)
        val line = entry.toString()
        report.appendText("$line\n")
        Log.i("VoiceMemory", line)
        return smapsError == null && measures.values.all { (it["mappings"] ?: 0L) == 0L }
    }

    private fun verifyFile(name: String, size: Long?, hash: String) {
        val file = File(fixtureDir, name)
        assertTrue("Push official test fixtures first: $name", file.isFile)
        if (size != null) assertEquals(size.toLong(), file.length())
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(65536)
            while (true) {
                val n = input.read(bytes)
                if (n < 0) break
                digest.update(bytes, 0, n)
            }
        }
        assertEquals(hash, digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
    }

    private fun readWave(file: File): FloatArray {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        var position = 12
        var validFormat = false
        while (position + 8 <= bytes.size) {
            val name = String(bytes, position, 4, Charsets.US_ASCII)
            val size = buffer.getInt(position + 4)
            assertTrue(size >= 0 && position.toLong() + 8 + size <= bytes.size)
            val start = position + 8
            if (name == "fmt ") {
                assertTrue(size >= 16)
                assertEquals(1, buffer.getShort(start).toInt())
                assertEquals(1, buffer.getShort(start + 2).toInt())
                assertEquals(16000, buffer.getInt(start + 4))
                assertEquals(16, buffer.getShort(start + 14).toInt())
                validFormat = true
            }
            if (name == "data") {
                assertTrue(validFormat)
                return FloatArray(size / 2) { buffer.getShort(start + it * 2) / 32768f }
            }
            position = start + size + (size and 1)
        }
        throw AssertionError("WAV data not found")
    }
}
