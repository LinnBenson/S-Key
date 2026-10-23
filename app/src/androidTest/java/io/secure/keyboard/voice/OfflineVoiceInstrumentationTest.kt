package io.secure.keyboard.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.test.AndroidTestCase
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Emulator/device tests; fixtures are pushed separately, never packaged in production assets. */
@Suppress("DEPRECATION")
class OfflineVoiceInstrumentationTest : AndroidTestCase() {
    private val fixtureDir
        get() = File(context.filesDir, "voice-fixtures")

    private fun checkFixtures() {
        // Optional local test-only asset source; production APKs never contain these fixtures.
        if (!File(fixtureDir, "model.int8.onnx").exists()) {
            val assets = context.createPackageContext(context.packageName + ".test", 0).assets
            if (assets.list("")?.contains("model.int8.onnx") == true) {
                check(fixtureDir.isDirectory || fixtureDir.mkdirs())
                for (name in listOf("model.int8.onnx", "tokens.txt", "0.wav"))
                    assets.open(name).use { input -> File(fixtureDir, name).outputStream().use { input.copyTo(it) } }
            }
        }
        for ((name, sha) in
            listOf(
                "model.int8.onnx" to ModelRepository.MODEL_SHA,
                "tokens.txt" to ModelRepository.TOKENS_SHA,
            )) {
            val file = File(fixtureDir, name)
            assertTrue("Push fixtures first: ${file.absolutePath}", file.isFile)
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val bytes = ByteArray(65536)
                while (true) {
                    val count = input.read(bytes)
                    if (count < 0) break
                    digest.update(bytes, 0, count)
                }
            }
            assertEquals(
                sha,
                digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) },
            )
        }
    }

    private fun recognizer(endpoints: Boolean = false) =
        OnlineRecognizer(
            config =
                OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                    modelConfig =
                        OnlineModelConfig(
                            zipformer2Ctc =
                                OnlineZipformer2CtcModelConfig(
                                    model = File(fixtureDir, "model.int8.onnx").absolutePath
                                ),
                            tokens = File(fixtureDir, "tokens.txt").absolutePath,
                            numThreads = 2,
                            provider = "cpu",
                            debug = false,
                        ),
                    endpointConfig = OfflineVoiceSession.endpointConfig(),
                    enableEndpoint = endpoints,
                    decodingMethod = "greedy_search",
                )
        )

    /** Synthetic/public fixtures only: measure validation and native creation separately. */
    fun testStartupTimingWithPublicFixtures() {
        val validation = mutableListOf<Long>()
        val creation = mutableListOf<Long>()
        val streamCreation = mutableListOf<Long>()
        repeat(5) {
            var start = System.nanoTime()
            checkFixtures()
            validation.add((System.nanoTime() - start) / 1_000_000)
            start = System.nanoTime()
            val engine = recognizer(true)
            creation.add((System.nanoTime() - start) / 1_000_000)
            try {
                start = System.nanoTime()
                val stream = engine.createStream()
                streamCreation.add((System.nanoTime() - start) / 1_000_000)
                stream.release()
            } finally { engine.release() }
        }
        android.util.Log.i("SKeyStartupBenchmark", "validationMs=$validation createMs=$creation streamMs=$streamCreation")
    }

    /** Packaging-only regression: one known utterance, no repeated lifecycle or silence work. */
    fun testNativePackagingSingleUtterance() {
        checkFixtures()
        val libraryDir = File(context.applicationInfo.nativeLibraryDir)
        for (name in listOf("libsherpa-onnx-jni.so", "libonnxruntime.so")) {
            assertTrue("Legacy JNI packaging must extract $name", File(libraryDir, name).isFile)
        }
        val fixture = File(fixtureDir, "0.wav")
        val wavDigest =
            MessageDigest.getInstance("SHA-256").digest(fixture.readBytes()).joinToString("") {
                "%02x".format(it.toInt() and 255)
            }
        assertEquals("668bf8df51a10027b84d5d8816a1ce11ae93545538dc05cfe2aa6811d399c250", wavDigest)
        val audio = readPcm16Wav(fixture)
        val engine = recognizer()
        try {
            val stream = engine.createStream()
            try {
                var position = 0
                while (position < audio.size) {
                    stream.acceptWaveform(
                        audio.copyOfRange(position, minOf(position + 1600, audio.size)),
                        16000,
                    )
                    position += 1600
                    while (engine.isReady(stream)) engine.decode(stream)
                }
                stream.acceptWaveform(FloatArray(8000), 16000)
                stream.inputFinished()
                while (engine.isReady(stream)) engine.decode(stream)
                assertEquals("对我做了介绍那么我想说的是呢大家如果对我的研究感兴趣呢", engine.getResult(stream).text.trim())
            } finally {
                stream.release()
            }
        } finally {
            engine.release()
            audio.fill(0f)
        }
    }

    fun testNativeMandarinInferenceAndFreshSilence() {
        checkFixtures()
        val audio = readPcm16Wav(File(fixtureDir, "0.wav"))
        val heaps = mutableListOf<Long>()
        repeat(3) {
            val engine = recognizer()
            try {
                val stream = engine.createStream()
                try {
                    var position = 0
                    var nonemptyInterim = 0
                    while (position < audio.size) {
                        stream.acceptWaveform(
                            audio.copyOfRange(position, minOf(position + 1600, audio.size)),
                            16000,
                        )
                        position += 1600
                        while (engine.isReady(stream)) engine.decode(stream)
                        if (engine.getResult(stream).text.isNotBlank()) nonemptyInterim++
                    }
                    stream.acceptWaveform(FloatArray(8000), 16000)
                    stream.inputFinished()
                    while (engine.isReady(stream)) engine.decode(stream)
                    assertTrue("Expected streaming interim text", nonemptyInterim > 0)
                    assertEquals(
                        "对我做了介绍那么我想说的是呢大家如果对我的研究感兴趣呢",
                        engine.getResult(stream).text.trim(),
                    )
                } finally {
                    stream.release()
                }
                val silent = engine.createStream()
                try {
                    silent.acceptWaveform(FloatArray(32000), 16000)
                    silent.inputFinished()
                    while (engine.isReady(silent)) engine.decode(silent)
                    assertEquals(
                        "New silent stream must not reuse prior transcript",
                        "",
                        engine.getResult(silent).text.trim(),
                    )
                } finally {
                    silent.release()
                }
            } finally {
                engine.release()
            }
            System.gc()
            heaps.add(Debug.getNativeHeapAllocatedSize())
        }
        audio.fill(0f)
        // Diagnostic, not a universal leak threshold: libc/ONNX allocators may retain arenas.
        println("Voice native heap bytes after 3 complete release cycles: $heaps")
    }

    /** Real CTC endpoints/reset, followed by a final flush, must preserve repeated speech. */
    fun testEndpointSegmentsAndFinalFlushDoNotRepeatOrDropUtterances() {
        checkFixtures()
        val audio = readPcm16Wav(File(fixtureDir, "0.wav"))
        val engine = recognizer(endpoints = true)
        val stream = engine.createStream()
        val segments = mutableListOf<Pair<Long, String>>()
        var segmentId = 0L
        try {
            fun feed(samples: FloatArray) {
                var position = 0
                while (position < samples.size) {
                    val chunk = samples.copyOfRange(position, minOf(position + 1600, samples.size))
                    stream.acceptWaveform(chunk, 16000)
                    chunk.fill(0f)
                    position += 1600
                    while (engine.isReady(stream)) engine.decode(stream)
                    if (engine.isEndpoint(stream)) {
                        val text = engine.getResult(stream).text.trim()
                        engine.reset(stream)
                        if (text.isNotEmpty()) segments.add(segmentId to text)
                        segmentId++
                        assertEquals("Reset must remove the finalized CTC result", "",
                            engine.getResult(stream).text.trim())
                    }
                }
            }
            feed(audio)
            feed(FloatArray(48000))
            assertTrue("Speech followed by silence must produce an automatic endpoint", segments.isNotEmpty())
            val first = segments.joinToString("") { it.second }
            val expected = "对我做了介绍那么我想说的是呢大家如果对我的研究感兴趣呢"
            assertEquals(expected, first)
            val countBeforeSecond = segments.size
            feed(audio)
            // Finish the final partial as production does at its natural 60-second limit.
            stream.acceptWaveform(FloatArray(8000), 16000)
            stream.inputFinished()
            while (engine.isReady(stream)) engine.decode(stream)
            val tail = engine.getResult(stream).text.trim()
            if (tail.isNotEmpty()) segments.add(segmentId to tail)
            assertTrue(segments.size > countBeforeSecond)
            assertEquals(expected + expected, segments.joinToString("") { it.second })
            assertEquals(segments.size, segments.map { it.first }.distinct().size)
            assertEquals(segments.map { it.first }.sorted(), segments.map { it.first })
        } finally {
            stream.release()
            engine.release()
            audio.fill(0f)
        }
    }

    fun testMicrophoneCancelReleaseSuppressesLateCallbacks() {
        checkFixtures()
        assertEquals(
            "Grant RECORD_AUDIO to test target first",
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO),
        )
        val installed = File(context.noBackupFilesDir, "mandarin-voice-v1").apply { mkdirs() }
        for (name in listOf("model.int8.onnx", "tokens.txt")) File(fixtureDir, name)
            .copyTo(File(installed, name), overwrite = true)
        File(installed, "verified").writeText(ModelRepository.MODEL_ID)
        ModelRepository(context).verifyInstalled()
        repeat(3) {
            val listening = CountDownLatch(1)
            val canceled = AtomicBoolean(false)
            val unexpected = AtomicReference<String?>(null)
            lateinit var session: OfflineVoiceSession
            onMain {
                session =
                    OfflineVoiceSession(
                        context,
                        object : OfflineVoiceSession.Listener {
                            override fun onState(state: OfflineVoiceSession.State) {
                                if (state == OfflineVoiceSession.State.LISTENING)
                                    listening.countDown()
                                if (canceled.get() && state != OfflineVoiceSession.State.IDLE)
                                    unexpected.set("Late state: $state")
                            }

                            override fun onPreview(text: String, isFinal: Boolean) {
                                if (canceled.get() && (text.isNotEmpty() || isFinal))
                                    unexpected.set("Late recognition preview")
                            }

                            override fun onSegment(segmentId: Long, text: String) {
                                if (canceled.get()) unexpected.set("Late finalized segment")
                            }

                            override fun onError(message: String) {
                                unexpected.set(message)
                                listening.countDown()
                            }
                        },
                    )
                session.begin()
            }
            val started = listening.await(30, TimeUnit.SECONDS)
            onMain {
                canceled.set(true)
                session.cancel()
                session.release()
                session.release()
            }
            assertTrue("Microphone failed to start within 30 seconds", started)
            Thread.sleep(1000)
            assertNull("Microphone lifecycle or stale callback failure", unexpected.get())
        }
    }

    fun testRapidCancelBeginReusesSessionAndCancelRevokesQueuedStart() {
        checkFixtures()
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO),
        )
        val installed = File(context.noBackupFilesDir, "mandarin-voice-v1").apply { mkdirs() }
        for (name in listOf("model.int8.onnx", "tokens.txt")) {
            File(fixtureDir, name).copyTo(File(installed, name), overwrite = true)
        }
        File(installed, "verified").writeText(ModelRepository.MODEL_ID)
        val listening = AtomicReference(CountDownLatch(1))
        val unexpected = AtomicReference<String?>(null)
        val forbidden = AtomicBoolean(false)
        lateinit var session: OfflineVoiceSession
        onMain {
            session =
                OfflineVoiceSession(
                    context,
                    object : OfflineVoiceSession.Listener {
                        override fun onState(state: OfflineVoiceSession.State) {
                            if (state == OfflineVoiceSession.State.LISTENING) {
                                if (forbidden.get())
                                    unexpected.set("Canceled queued start activated microphone")
                                listening.get().countDown()
                            }
                        }

                        override fun onPreview(text: String, isFinal: Boolean) {
                            if (forbidden.get() && (text.isNotEmpty() || isFinal))
                                unexpected.set("Canceled queued start emitted text")
                        }

                        override fun onSegment(segmentId: Long, text: String) {
                            if (forbidden.get()) unexpected.set("Canceled queued start emitted segment")
                        }

                        override fun onError(message: String) {
                            unexpected.set(message)
                            listening.get().countDown()
                        }
                    },
                )
        }
        try {
            repeat(3) {
                val started = CountDownLatch(1)
                listening.set(started)
                onMain {
                    // All three calls are in one main-thread turn: busy cleanup cannot have run.
                    session.begin()
                    session.cancel()
                    session.begin()
                }
                assertTrue(
                    "Queued restart on reused session never started",
                    started.await(30, TimeUnit.SECONDS),
                )
                assertNull(unexpected.get())
                onMain { session.cancel() }
            }
            onMain {
                session.begin()
                session.cancel()
                session.begin()
                // Cancel must also remove the single pending restart.
                session.cancel()
                forbidden.set(true)
            }
            Thread.sleep(2000)
            assertNull(unexpected.get())
        } finally {
            onMain { session.release() }
        }
    }

    /** No model/mic needed: emulate the bounded restart waiting for old native cleanup. */
    fun testStopRevokesQueuedBeginAndReportsIdleWithoutOpeningMicrophone() {
        onMain {
            val states = mutableListOf<OfflineVoiceSession.State>()
            val session = OfflineVoiceSession(context, object : OfflineVoiceSession.Listener {
                override fun onState(state: OfflineVoiceSession.State) { states.add(state) }
                override fun onPreview(text: String, isFinal: Boolean) {}
                override fun onError(message: String) { fail(message) }
            })
            try {
                OfflineVoiceSession::class.java.getDeclaredField("busy")
                    .apply { isAccessible = true }.setBoolean(session, true)
                session.begin()
                val pending = OfflineVoiceSession::class.java.getDeclaredField("pendingBegin")
                    .apply { isAccessible = true }
                assertTrue(pending.getBoolean(session))
                assertEquals(OfflineVoiceSession.State.LOADING, states.last())
                session.stop()
                assertFalse("Releasing held space must revoke a queued microphone start", pending.getBoolean(session))
                assertEquals(OfflineVoiceSession.State.IDLE, states.last())
                assertFalse(states.contains(OfflineVoiceSession.State.LISTENING))
            } finally {
                session.release()
            }
        }
    }

    private fun onMain(block: () -> Unit) {
        val done = CountDownLatch(1)
        val error = AtomicReference<Throwable?>(null)
        Handler(Looper.getMainLooper()).post {
            try {
                block()
            } catch (t: Throwable) {
                error.set(t)
            } finally {
                done.countDown()
            }
        }
        assertTrue("Main thread did not respond", done.await(10, TimeUnit.SECONDS))
        error.get()?.let { throw AssertionError("Main thread failure", it) }
    }

    private fun readPcm16Wav(file: File): FloatArray {
        val bytes = file.readBytes()
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun ascii(offset: Int, length: Int) = String(bytes, offset, length, Charsets.US_ASCII)
        assertEquals("RIFF", ascii(0, 4))
        assertEquals("WAVE", ascii(8, 4))
        var cursor = 12
        var validFormat = false
        while (cursor + 8 <= bytes.size) {
            val kind = ascii(cursor, 4)
            val size = data.getInt(cursor + 4)
            assertTrue(
                "Invalid RIFF chunk size",
                size >= 0 && cursor.toLong() + 8 + size <= bytes.size,
            )
            val start = cursor + 8
            if (kind == "fmt ") {
                assertTrue(size >= 16)
                assertEquals(1, data.getShort(start).toInt())
                assertEquals(1, data.getShort(start + 2).toInt())
                assertEquals(16000, data.getInt(start + 4))
                assertEquals(16, data.getShort(start + 14).toInt())
                validFormat = true
            }
            if (kind == "data") {
                assertTrue(validFormat)
                return FloatArray(size / 2) { data.getShort(start + it * 2) / 32768f }
            }
            cursor = start + size + (size and 1)
        }
        throw AssertionError("Missing WAV data")
    }
}
