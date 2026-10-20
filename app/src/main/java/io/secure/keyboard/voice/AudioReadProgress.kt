package io.secure.keyboard.voice

/** Detect stalled capture, not silence: zero-valued PCM is still valid received data. */
internal class AudioReadProgress(startMs: Long) {
    private var lastDataMs = startMs

    fun accept(byteCount: Int, nowMs: Long): Boolean {
        if (byteCount < 0) return false
        if (byteCount > 0) lastDataMs = nowMs
        return nowMs - lastDataMs < 2_000
    }
}
