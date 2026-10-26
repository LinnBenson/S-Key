package io.secure.keyboard.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.SystemClock
import android.os.Looper

/** Scoped to microphone capture, never network recognition. Caller serializes open/close with its gate. */
internal class VoiceCaptureEnvironment(context: Context) {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    @Volatile private var focus: RecordingFocusLease? = null
    private var nextCheckAt = 0L
    private var echo: AudioEffect? = null
    private var noise: AudioEffect? = null

    fun open(mic: AudioRecord) {
        close()
        nextCheckAt = 0L
        var request: AudioFocusRequest? = null
        val lease = RecordingFocusLease(
            request = { onLost ->
                val value = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener({ change ->
                        if (change == AudioManager.AUDIOFOCUS_LOSS ||
                            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) onLost()
                    }, Handler(Looper.getMainLooper())).build()
                request = value
                // Android 15+ may deny focus to an IME. Quiet recording remains usable;
                // ongoing media requires a user pause instead of changing global volume.
                try { audio.requestAudioFocus(value) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED }
                catch (_: RuntimeException) { false }
            },
            abandon = { request?.let { audio.abandonAudioFocusRequest(it) }; request = null },
            mediaPlaying = { audio.isMusicActive },
        )
        focus = lease
        try {
            lease.acquire()
            echo = enableOptional {
                if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(mic.audioSessionId) else null
            }
            noise = enableOptional {
                if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(mic.audioSessionId) else null
            }
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    fun checkAvailable() {
        val lease = focus ?: return
        val now = SystemClock.elapsedRealtime()
        if (now < nextCheckAt) return
        nextCheckAt = now + 100
        lease.checkAvailable()
    }

    fun close() {
        val oldEcho = echo; echo = null
        val oldNoise = noise; noise = null
        val oldFocus = focus; focus = null
        try { oldEcho?.release() } catch (_: Exception) {}
        try { oldNoise?.release() } catch (_: Exception) {}
        oldFocus?.close()
    }

    private fun enableOptional(create: () -> AudioEffect?): AudioEffect? {
        var effect: AudioEffect? = null
        try {
            effect = create() ?: return null
            if (effect.hasControl() && effect.setEnabled(true) == AudioEffect.SUCCESS && effect.enabled)
                return effect
        } catch (_: RuntimeException) {
            // OEM effect missing, unavailable or without control: basic capture is still valid.
        }
        try { effect?.release() } catch (_: Exception) {}
        return null
    }
}
