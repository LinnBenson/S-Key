package io.secure.keyboard.voice

/** Bounded scalar statistics only. Silence is allowed; this is not a speech detector. */
internal class CaptureSignalHealth {
    private var previous = 0
    private var seen = false
    private var constantNonzero = 0
    private var clipped = 0
    var hasVariation = false
        private set

    private fun acceptSample(value: Int): Boolean {
        if (seen && value != previous) hasVariation = true
        constantNonzero = if (seen && value == previous && kotlin.math.abs(value) >= 64)
            constantNonzero + 1 else 0
        clipped = if (kotlin.math.abs(value) >= 32760) clipped + 1 else 0
        seen = true
        previous = value
        // One second of identical nontrivial DC, or two seconds continuously at the rails.
        return constantNonzero < 16000 && clipped < 32000
    }

    fun accept(pcm: ShortArray, offset: Int, count: Int): Boolean {
        for (i in offset until offset + count) if (!acceptSample(pcm[i].toInt())) return false
        return true
    }

    fun accept(pcm: ByteArray, offset: Int, count: Int): Boolean {
        if (count % 2 != 0) return false
        for (i in offset until offset + count step 2) {
            val value = ((pcm[i].toInt() and 255) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
            if (!acceptSample(value)) return false
        }
        return true
    }
}
