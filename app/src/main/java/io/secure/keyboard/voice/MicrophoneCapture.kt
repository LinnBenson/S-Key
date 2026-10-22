package io.secure.keyboard.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock

/** No audio retention, device names, persistent diagnostics or global audio routing changes. */
internal object MicrophoneCapture {
    // Prefer ordinary microphone capture over vendor-specific recognition preprocessing.
    // Fall back only on initialization/start failure, never merely because a user is quiet.
    fun start(context: Context, gate: MicrophoneLifecycleGate, allowed: () -> Boolean,
              publish: (AudioRecord) -> Unit): AudioRecord? {
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "设备不支持此录音格式" }
        for (source in intArrayOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION)) {
            if (!allowed()) return null
            var candidate: AudioRecord? = null
            var transferred = false
            try {
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                    throw SecurityException("麦克风权限不可用")
                val mic = AudioRecord(source, 16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 6400))
                candidate = mic
                check(mic.state == AudioRecord.STATE_INITIALIZED)
                val started = gate.startIfAllowed(allowed) {
                    mic.startRecording()
                    check(mic.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                    publish(mic)
                }
                if (started) { transferred = true; return mic }
                return null
            } catch (e: SecurityException) {
                throw e
            } catch (_: IllegalStateException) {
                // The failed recorder is released before another source can acquire the mic.
            } catch (_: IllegalArgumentException) {
                // Some devices reject a source even when the format is supported.
            } finally {
                if (!transferred) {
                    try { candidate?.stop() } catch (_: Exception) {}
                    candidate?.release()
                }
            }
        }
        error("无法启动麦克风，请关闭占用麦克风的功能后重试")
    }
}

/** Poll on the capture worker: no callback registration or extra thread to leak on cancellation. */
internal class MicrophoneStatus(private val mic: AudioRecord) {
    private var nextCheck = 0L
    fun checkAvailable() {
        val now = SystemClock.elapsedRealtime()
        if (now < nextCheck) return
        nextCheck = now + 100
        if (Build.VERSION.SDK_INT >= 29) {
            check(mic.activeRecordingConfiguration?.isClientSilenced != true) {
                "麦克风被系统静音，请关闭占用麦克风的功能后重试"
            }
        }
    }
}
