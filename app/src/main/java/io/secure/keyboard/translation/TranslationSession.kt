package io.secure.keyboard.translation

internal class TranslationSession(private val onInvalidate: () -> Unit = {}) {
    var enabled = false
    var phraseDraft = false
    var phraseGeneration = 0L
    var phraseEditId: String? = null
    var phraseSaveToken: Any? = null
    var source = "auto"
    var target = "en"
    var text = ""; private set
    var result = ""; private set
    var status = "输入原文后点击翻译"; private set
    var errorMessage = ""; private set
    val resultOrError: String get() = result.ifEmpty { errorMessage }
    var revision = 0; private set
    var busy = false; private set
    fun edit(value: String): Boolean {
        if (value.length > 5000) return false
        text = value; invalidate(); return true
    }
    /** AI drafts may change without canceling or erasing the response being generated. */
    fun editDraft(value: String): Boolean {
        if (value.length > 5000) return false
        text = value; return true
    }
    fun invalidate() { onInvalidate(); revision++; errorMessage = ""; result = ""; busy = false; status = "输入原文后点击翻译" }
    fun begin(): Int { invalidate(); busy = true; status = "正在翻译…"; return revision }
    fun partial(token: Int, value: String) {
        if (enabled && busy && token == revision) result = value
    }
    fun complete(token: Int, value: String, error: Boolean = false) {
        if (!enabled || token != revision) return
        busy = false
        errorMessage = if (error) value else ""
        if (error) { status = value; result = "" } else { result = value; status = "译文 · 点击插入" }
    }
    fun close() { phraseDraft = false; phraseEditId = null; phraseSaveToken = null; enabled = false; text = ""; invalidate() }
    fun backspace() {
        if (text.isNotEmpty()) edit(text.substring(0, text.offsetByCodePoints(text.length, -1)))
    }
}
