package io.secure.keyboard.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.SystemClock
import android.test.InstrumentationTestCase
import kotlin.math.sqrt

/** Explicit local diagnostic: retain no audio and never call a network service. */
@Suppress("DEPRECATION")
class AudioCaptureDiagnosticTest : InstrumentationTestCase() {
    fun testCaptureStatistics() {
        val activity = instrumentation.startActivitySync(android.content.Intent(instrumentation.targetContext, io.secure.keyboard.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val missing = mutableListOf<Int>()
        try {
        for (source in listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val mic = AudioRecord(source, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 6400))
            val pcm = ShortArray(1600)
            try {
                assertEquals(AudioRecord.STATE_INITIALIZED, mic.state)
                mic.startRecording()
                val end = SystemClock.elapsedRealtime() + 3000
                var samples = 0L; var sum = 0.0; var energy = 0.0; var clipped = 0L; var changes = 0L
                var previous: Short = 0; var reads = 0; var empty = 0
                var low = 1f; var high = 0f
                while (SystemClock.elapsedRealtime() < end) {
                    val n = mic.read(pcm, 0, pcm.size, AudioRecord.READ_NON_BLOCKING)
                    check(n >= 0) { "AudioRecord error $n" }
                    if (n == 0) { empty++; Thread.sleep(10); continue }
                    reads++
                    val level = VoiceLevel.measure(pcm, 0, n)
                    low = minOf(low, level); high = maxOf(high, level)
                    for (i in 0 until n) {
                        val x = pcm[i].toDouble() / 32768.0
                        sum += x; energy += x*x
                        if (kotlin.math.abs(x) > .99) clipped++
                        if (pcm[i] != previous) changes++
                        previous = pcm[i]
                    }
                    samples += n; pcm.fill(0)
                }
                val mean = if(samples > 0) sum/samples else 0.0
                val rms = if(samples > 0) sqrt(energy/samples) else 0.0
                val ac = sqrt((rms*rms-mean*mean).coerceAtLeast(0.0))
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "source=$source samples=$samples reads=$reads empty=$empty mean=$mean rms=$rms ac=$ac clipped=$clipped changes=$changes level=$low..$high\n") })
                if (samples == 0L) missing.add(source)
            } finally { pcm.fill(0); runCatching { mic.stop() }; mic.release() }
        }
        assertTrue("No microphone samples for sources $missing", missing.isEmpty())
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
