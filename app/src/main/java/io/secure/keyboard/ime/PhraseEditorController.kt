package io.secure.keyboard.ime

import android.view.Gravity
import android.widget.LinearLayout

/** Local, memory-only draft using the same editor/input routing as assistant source fields. */
internal object PhraseEditorController {
    fun KeyboardSession.openPhraseEditor(id: String? = null, text: String = "") {
        if (!active || secret) return
        if (text.length > 5000) { toast("常用语超过 5000 字符，请在设置中编辑"); return }
        cancelTransient(); clearComposition(); stopAiRequest(); stopTranslationRequest()
        clearAiConversation(); aiMode = false; aiPresetLoadGeneration++; aiSelectionTarget.revoke()
        aiCustom = ""; aiCustomPresetName = ""; aiCustomModel = ""; aiCustomReasoning = "low"
        aiCustomWebSearch = false; aiCustomXSearch = false
        translation.close(); translation.enabled = true; translation.phraseDraft = true
        translation.phraseGeneration = content.phraseGeneration()
        translation.phraseEditId = id; translation.edit(text)
        sourceCursor = text.length; panel = "keys"
        renderHeader(); renderBody(false); refreshCandidates()
        activeAssistantEditor()?.requestFocus()
    }

    private fun KeyboardSession.closePhraseEditor() {
        cancelTransient(); clearComposition(); translation.close()
        panel = "phrases"; renderHeader(); renderBody(false); refreshCandidates()
    }

    fun KeyboardSession.updatePhraseEditor() {
        syncAssistantText(translationTextView, translation.text)
        updatePhraseActions()
    }

    fun KeyboardSession.updatePhraseActions() {
        translationAction?.apply {
            text = if (translation.phraseSaveToken != null) "保存中…" else "保存"
            isEnabled = translation.phraseSaveToken == null && translation.text.isNotBlank() && code.isEmpty()
            alpha = if (isEnabled) 1f else .45f
        }
        translationInsert?.isEnabled = translation.phraseSaveToken == null
    }

    private fun KeyboardSession.savePhrase() {
        if (!phraseMode || !active || secret || translation.phraseSaveToken != null) return
        if (code.isNotEmpty()) { toast("请先确认候选词"); return }
        val draft = translation.text
        if (draft.isBlank()) { toast("请输入常用语"); return }
        val ticket = Any(); translation.phraseSaveToken = ticket
        val editor = voiceEditorGeneration
        val store = content
        val editId = translation.phraseEditId
        val generation = translation.phraseGeneration
        updatePhraseEditor()
        worker.execute {
            val result = runCatching { store.savePhrase(draft, editId, generation) }
            main.post {
                if (destroyed || !active || !phraseMode || editor != voiceEditorGeneration || translation.phraseSaveToken !== ticket) return@post
                translation.phraseSaveToken = null
                if (result.isFailure) { toast(result.exceptionOrNull()?.message ?: "保存失败，请重试"); updatePhraseEditor(); return@post }
                translation.phraseEditId = result.getOrThrow()
                toast("已保存常用语")
                if (translation.text == draft && code.isEmpty()) closePhraseEditor()
                else updatePhraseEditor()
            }
        }
    }

    fun KeyboardSession.renderPhraseEditor(column: LinearLayout) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(keyBg)
            setPadding(dp(12), dp(6), dp(12), 0)
        }
        column.addView(card, LinearLayout.LayoutParams(-1, translationExtraHeightPx() - dp(12)).apply {
            setMargins(dp(4), dp(12), dp(4), 0)
        })
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label(if (translation.phraseEditId == null) "添加常用语" else "编辑常用语", 14f).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(0, -1, 1f))
        translationInsert = button("取消") { if (translation.phraseSaveToken == null) closePhraseEditor() }.apply { tag = "phrase_draft_cancel" }
        translationAction = button("保存") { savePhrase() }.apply { tag = "phrase_draft_save" }
        var headerHeight = dp(36)
        listOfNotNull(translationInsert, translationAction).forEach { action ->
            action.setPadding(dp(8), 0, dp(8), 0)
            action.textSize = 14f
            action.includeFontPadding = false
            action.gravity = Gravity.CENTER
            action.setSingleLine(true)
            action.minWidth = dp(60)
            val metrics = action.paint.fontMetricsInt
            headerHeight = maxOf(headerHeight, metrics.bottom - metrics.top + dp(4))
            header.addView(action, LinearLayout.LayoutParams(-2, -1))
        }
        card.addView(header, LinearLayout.LayoutParams(-1, headerHeight))
        val editor = assistantEditor().apply { contentDescription = "常用语内容" }
        translationTextView = editor
        card.addView(editor, LinearLayout.LayoutParams(-1, 0, 1f))
        updatePhraseEditor()
    }
}
