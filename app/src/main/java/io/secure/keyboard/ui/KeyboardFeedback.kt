package io.secure.keyboard.ui

import android.media.AudioManager
import android.view.View

/** Submit the platform effects before the caller performs its already-validated key action. */
object KeyboardFeedback {
    fun perform(view: View, sound: Boolean, vibration: Boolean, strength: Int) {
        KeyboardSounds.perform(view, sound)
        KeyboardHaptics.perform(view, vibration, strength)
    }

    /** Child controls must not add View.performClick/performLongClick's automatic effects. */
    fun disableAutomatic(view: View) {
        view.isSoundEffectsEnabled = false
        view.isHapticFeedbackEnabled = false
    }
}

internal object KeyboardSounds {
    fun perform(view: View, enabled: Boolean) {
        // Sound is independently opt-in. The default path does not look up a system service.
        if (!enabled || !view.isShown) return
        try {
            val audio = view.context.getSystemService(AudioManager::class.java) ?: return
            if (!KeyboardSoundPolicy.canPlay(
                    audio.ringerMode, audio.getStreamVolume(AudioManager.STREAM_SYSTEM),
                    audio.isStreamMute(AudioManager.STREAM_SYSTEM),
                )) return
            // The one-argument API also respects the system UI sound switch. Do not use the
            // volume override, preload/wait for audio, or delay the key action for playback.
            audio.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD)
        } catch (_: RuntimeException) {
            // Missing/OEM audio support must never block typing or the independent haptic.
        }
    }
}

internal object KeyboardSoundPolicy {
    fun canPlay(ringerMode: Int, systemVolume: Int, systemMuted: Boolean): Boolean =
        ringerMode == AudioManager.RINGER_MODE_NORMAL && systemVolume > 0 && !systemMuted
}
