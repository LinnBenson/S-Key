package io.secure.keyboard.ui

import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View

/** Keyboard feedback, never a timed buzz. The platform/OEM decides which actuator produces it. */
object KeyboardHaptics {
    fun perform(view: View, enabled: Boolean, strength: Int) {
        if (!enabled || !view.isShown || !view.isHapticFeedbackEnabled) return
        if (KeyboardHapticPolicy.useSystemFeedback(strength, Build.VERSION.SDK_INT)) {
            systemTap(view)
            return
        }
        // View's API checks system settings itself. Direct primitive playback must do so
        // explicitly.
        val resolver = view.context.contentResolver
        val customAllowed =
            try {
                Settings.System.getInt(resolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0 &&
                    // AOSP's separately readable keyboard toggle is optional on older/vendor
                    // builds.
                    Settings.System.getInt(resolver, "keyboard_vibration_enabled", 1) != 0
            } catch (_: Exception) {
                false
            }
        if (!customAllowed) {
            systemTap(view)
            return
        }
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                val vibrator =
                    view.context.getSystemService(VibratorManager::class.java)?.defaultVibrator
                if (vibrator != null && vibrator.hasVibrator()) {
                    val primitive =
                        when {
                            vibrator.areAllPrimitivesSupported(
                                VibrationEffect.Composition.PRIMITIVE_TICK
                            ) -> VibrationEffect.Composition.PRIMITIVE_TICK
                            vibrator.areAllPrimitivesSupported(
                                VibrationEffect.Composition.PRIMITIVE_CLICK
                            ) -> VibrationEffect.Composition.PRIMITIVE_CLICK
                            else -> null
                        }
                    if (primitive != null) {
                        val effect =
                            VibrationEffect.startComposition()
                                .addPrimitive(
                                    primitive,
                                    KeyboardHapticPolicy.primitiveScale(strength),
                                )
                                .compose()
                        if (Build.VERSION.SDK_INT >= 33) {
                            vibrator.vibrate(
                                effect,
                                VibrationAttributes.Builder()
                                    .setUsage(VibrationAttributes.USAGE_TOUCH)
                                    .build(),
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            vibrator.vibrate(
                                effect,
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                    .build(),
                            )
                        }
                        return
                    }
                }
            } catch (_: Exception) {
                // Unsupported vendor implementation or unavailable permission: let the View fall
                // back.
            }
        }
        systemTap(view)
    }

    private fun systemTap(view: View) {
        // No IGNORE_* flags: respect both View and system touch/keyboard feedback preferences.
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }
}

/**
 * API30 reports composition support, not reliable per-primitive support; keep its OEM key effect.
 */
internal object KeyboardHapticPolicy {
    fun useSystemFeedback(strength: Int, sdk: Int): Boolean =
        strength.coerceIn(1, 100) <= 10 || sdk < 31

    fun primitiveScale(strength: Int): Float = strength.coerceIn(1, 100) / 100f
}
