package io.secure.keyboard.voice

/** One recording's focus ownership. No queued focus grant and no playback commands. */
internal class RecordingFocusLease(
    private val request: (() -> Unit) -> Boolean,
    private val abandon: () -> Unit,
    private val mediaPlaying: () -> Boolean,
) {
    @Volatile private var closed = false
    @Volatile private var lost = false
    private var requested = false
    private var granted = false

    @Synchronized fun acquire() {
        check(!closed && !requested)
        requested = true
        try {
            granted = request { if (!closed) lost = true }
            checkAvailable()
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    @Synchronized fun checkAvailable() {
        if (closed) return
        if (lost) throw RecordingFocusException("录音被其他音频打断，请重试")
        if (!granted && mediaPlaying())
            throw RecordingFocusException("无法暂停媒体播放，请先暂停视频或音乐再使用语音")
    }

    @Synchronized fun close() {
        if (closed) return
        closed = true
        if (requested) {
            requested = false
            try { abandon() } catch (_: Exception) {}
        }
    }
}

internal class RecordingFocusException(message: String) : IllegalStateException(message)
