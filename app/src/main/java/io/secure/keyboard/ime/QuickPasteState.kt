package io.secure.keyboard.ime

/** Only actual keyboard openings offer clips; clipboard callbacks never do. */
internal class QuickPasteState(private val now: () -> Long = System::currentTimeMillis) {
    companion object {
        const val MAX_AGE_MS = 5 * 60 * 1000L
    }
    var text: String? = null
        private set
    private var copiedAt = 0L
    var revision = 0L
        private set

    fun open(token: Long, value: String?, consumed: Long, consume: (Long) -> Unit) {
        clear()
        if (token <= 0 || token == consumed) return
        consume(token)
        // The clipboard timestamp and this clock both use wall-clock milliseconds.
        val currentTime = now()
        if (token > currentTime || currentTime - token > MAX_AGE_MS) return
        copiedAt = token
        text = value
    }

    fun expireIfNeeded(): Boolean {
        if (text == null) return false
        val current = now()
        if (current >= copiedAt && current - copiedAt <= MAX_AGE_MS) return false
        clear()
        return true
    }

    val remainingMillis: Long
        get() = if (text == null) 0L else (MAX_AGE_MS - (now() - copiedAt)).coerceIn(0L, MAX_AGE_MS) + 1L

    fun clear() {
        copiedAt = 0L
        text = null
        revision++
    }
}
