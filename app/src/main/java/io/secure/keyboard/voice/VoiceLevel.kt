package io.secure.keyboard.voice

/** Only a normalized volume value leaves the capture worker; no audio is retained. */
internal object VoiceLevel {
    fun measure(samples: ByteArray, offset: Int, count: Int): Float {
        val pairs = count / 2
        if (pairs == 0) return 0f
        var energy = 0.0
        for (i in 0 until pairs) {
            val index = offset + i * 2
            val sample = ((samples[index].toInt() and 255) or
                (samples[index + 1].toInt() shl 8)).toShort() / 32768.0
            energy += sample * sample
        }
        val rms = kotlin.math.sqrt(energy / pairs)
        return ((20 * kotlin.math.log10(rms.coerceAtLeast(0.00001)) + 55) / 45).toFloat().coerceIn(0f, 1f)
    }
    fun measure(samples: ShortArray, offset: Int, count: Int): Float {
        if (count <= 0) return 0f
        var energy = 0.0
        for (i in offset until offset + count) {
            val sample = samples[i] / 32768.0
            energy += sample * sample
        }
        val rms = kotlin.math.sqrt(energy / count)
        return ((20 * kotlin.math.log10(rms.coerceAtLeast(0.00001)) + 55) / 45).toFloat().coerceIn(0f, 1f)
    }
}
