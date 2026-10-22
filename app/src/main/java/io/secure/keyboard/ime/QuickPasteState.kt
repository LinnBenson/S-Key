package io.secure.keyboard.ime

/** Only actual keyboard openings offer clips; clipboard callbacks never do. */
internal class QuickPasteState {
    var text: String? = null
        private set
    var revision = 0L
        private set

    fun open(token: Long, value: String?, consumed: Long, consume: (Long) -> Unit) {
        clear()
        if (token <= 0 || token == consumed) return
        consume(token)
        text = value
    }

    fun clear() {
        text = null
        revision++
    }
}
