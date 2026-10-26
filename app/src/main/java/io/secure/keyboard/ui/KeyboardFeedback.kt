package io.secure.keyboard.ui

import android.media.AudioManager
import android.media.AudioAttributes
import android.media.SoundPool
import android.content.Context
import io.secure.keyboard.R
import android.view.View

/** Submit the platform effects before the caller performs its already-validated key action. */
object KeyboardFeedback {
    fun perform(view: View, sound: String, vibration: Boolean, strength: Int, soundVolume: Int) {
        KeyboardSounds.perform(view, sound, soundVolume)
        KeyboardHaptics.perform(view, vibration, strength)
    }

    /** Child controls must not add View.performClick/performLongClick's automatic effects. */
    fun disableAutomatic(view: View) {
        view.isSoundEffectsEnabled = false
        view.isHapticFeedbackEnabled = false
    }
}

internal object KeyboardSounds : KeySoundPlayer()

internal open class KeySoundPlayer {
    private var pool: SoundPool? = null
    private val samples = mutableMapOf<String, Int>()
    private val ready = mutableSetOf<Int>()

    /** Decode off the input path. Never queue a delayed key sound while loading. */
    fun prepare(context: Context, sound: String) {
        if (sound == "none" || sound == "system" || pool != null) return
        try {
            val created = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            ).build()
            pool = created
            created.setOnLoadCompleteListener { owner, id, status ->
                if (owner === pool && status == 0) ready.add(id)
            }
            mapOf(
                "bong_001" to R.raw.bong_001, "click_001" to R.raw.click_001,
                "click_002" to R.raw.click_002, "click_003" to R.raw.click_003,
                "click_004" to R.raw.click_004, "click_005" to R.raw.click_005,
            ).forEach { (name, resource) -> samples[name] = created.load(context.applicationContext, resource, 1) }
        } catch (_: RuntimeException) { release() }
    }

    fun release() {
        pool?.release()
        pool = null
        samples.clear()
        ready.clear()
    }

    fun perform(view: View, sound: String, volume: Int) {
        if (sound == "none" || !view.isShown) return
        try {
            val audio = view.context.getSystemService(AudioManager::class.java) ?: return
            if (!KeyboardSoundPolicy.canPlay(
                    audio.ringerMode, audio.getStreamVolume(AudioManager.STREAM_SYSTEM),
                    audio.isStreamMute(AudioManager.STREAM_SYSTEM),
                )) return
            if (sound == "system") {
                // The platform effect also respects Android's UI sound switch.
                audio.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD)
            } else {
                val gain = volume.coerceIn(0, 100) / 100f
                if (gain == 0f) return
                val sample = samples[sound] ?: return
                if (sample in ready) pool?.play(sample, gain, gain, 1, 0, 1f)
            }
        } catch (_: RuntimeException) {
            // Audio support must never block typing or the independent haptic.
        }
    }
}

internal object KeyboardSoundPolicy {
    fun canPlay(ringerMode: Int, systemVolume: Int, systemMuted: Boolean): Boolean =
        ringerMode == AudioManager.RINGER_MODE_NORMAL && systemVolume > 0 && !systemMuted
}
