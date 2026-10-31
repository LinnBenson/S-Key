package io.secure.keyboard.ime

internal class QuickPasteState(private val now: () -> Long = System::currentTimeMillis) {
    var text: String? = null
        private set
    var token = 0L
        private set
    var revision = 0L
        private set

    fun offer(timestamp: Long, value: String?, consumed: Long, opening: Boolean, durationSeconds: Int) {
        // Duplicate change notifications must not reopen a dismissed preview or expire a visible one.
        if (!opening && timestamp == token && value != null) return
        token = timestamp
        text = value?.takeIf { timestamp > 0 && timestamp != consumed && now() - timestamp in 0L until durationSeconds * 1000L }
        revision++
    }

    fun clear() {
        text = null
        revision++
    }
}
