package io.secure.keyboard.ime

import io.secure.keyboard.ime.KeyboardSession.PendingConfirmation
import io.secure.keyboard.ime.KeyboardSession.QueuedKey

/** Per-editor input state. No global storage or learned input history. */
internal class InputState {
    var chinese = true
    var shifted = false
    var capsLocked = false
    var lastShiftTap = 0L
    var symbols = false
    var symbolPage = false
    var code = ""
    var pendingConfirmation: PendingConfirmation? = null
    var queueReplayPosted = false
    var queueEpoch = 0
    var englishConfirmation: Int? = null
    var spacingSelectionVersion = 0
    val spacingExpectedCursors = ArrayDeque<Int>()
    val queuedKeys = ArrayDeque<QueuedKey>()
    var queueSaturationNotified = false
    var secret = false
}
