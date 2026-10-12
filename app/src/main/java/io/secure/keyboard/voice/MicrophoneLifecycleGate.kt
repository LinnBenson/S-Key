package io.secure.keyboard.voice

/** Serializes only the recorder's start/stop boundary, never model loading or decoding. */
internal class MicrophoneLifecycleGate {
    private val lock = Any()

    fun startIfAllowed(isAllowed: () -> Boolean, start: () -> Unit): Boolean = synchronized(lock) {
        if (!isAllowed()) false
        else {
            start()
            true
        }
    }

    fun stop(stop: () -> Unit) = synchronized(lock) { stop() }
}
