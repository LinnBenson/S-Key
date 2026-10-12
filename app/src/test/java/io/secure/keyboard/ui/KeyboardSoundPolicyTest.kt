package io.secure.keyboard.ui

import android.media.AudioManager
import org.junit.Assert.*
import org.junit.Test

class KeyboardSoundPolicyTest {
    @Test fun silentAndVibrateModesNeverRequestSound() {
        for (mode in listOf(AudioManager.RINGER_MODE_SILENT, AudioManager.RINGER_MODE_VIBRATE)) {
            assertFalse(KeyboardSoundPolicy.canPlay(mode, 7, false))
        }
    }

    @Test fun zeroOrMutedSystemVolumeNeverRequestsSound() {
        assertFalse(KeyboardSoundPolicy.canPlay(AudioManager.RINGER_MODE_NORMAL, 0, false))
        assertFalse(KeyboardSoundPolicy.canPlay(AudioManager.RINGER_MODE_NORMAL, 7, true))
    }

    @Test fun audibleNormalModeCanRequestTheSystemControlledEffect() {
        assertTrue(KeyboardSoundPolicy.canPlay(AudioManager.RINGER_MODE_NORMAL, 1, false))
    }
}
