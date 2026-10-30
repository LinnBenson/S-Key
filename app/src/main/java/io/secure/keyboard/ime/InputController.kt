package io.secure.keyboard.ime

import android.content.*
import android.os.*
import android.view.*
import android.widget.*
import kotlin.math.*
import io.secure.keyboard.data.DictionaryKind

import io.secure.keyboard.*
import io.secure.keyboard.ime.KeyboardSession.QueuedKey
import io.secure.keyboard.ime.KeyboardSession.PendingConfirmation

/** Input composition, commit ordering and editor interaction. */
internal object InputController {
    internal fun KeyboardSession.clearPendingImpl() {
        spacingContext.invalidate()
        queueEpoch++
        spacingExpectedCursors.clear()
        pendingConfirmation = null
        englishConfirmation = null
        queueReplayPosted = false
        queuedKeys.clear()
        queueSaturationNotified = false
        clearComposition()
        pinyinContext = ""
    }

    /** Successful ordered commits clear the code without discarding later queued keystrokes. */
    internal fun KeyboardSession.clearCompositionImpl() {
        clearEditorPreview()
        generation++
        cancelCandidateQueries()
        code = ""
        pinyinComposition.clear()
        clearCandidates()
        if (candidatesExpanded) {
            candidatesExpanded = false
            if (bodyInitialized) renderKeyboardBody()
        }
    }

    /** Finished/hidden/editor-invalidated ownership only; never the asynchronous loading path. */
    internal fun KeyboardSession.releaseFinishedInputPresentationImpl() {
        releaseSpaceGlossary()
        spaceGlossLabel = null
        // Native posted clicks/long presses must be revoked while their views are still in
        // the root. cancelTransient separately revokes our key timers and voice session.
        main.removeCallbacks(quickPasteExpiry)
        quickPasteState.clear()
        if (headerInitialized) {
            toolbarGroup.removeAllViews()
            toolbarSignature = null
        }
        if (rootInitialized) root.cancelPendingInputEvents()
        contentQueries.clear()
        val releasePanel = panel != "keys" || translation.enabled
        stopAiRequest()
        clearAiConversation(); aiMode = false; aiCustom = ""; aiCustomReasoning = "low"; aiCustomPresetName = ""; aiCustomModel = ""; aiCustomWebSearch = false; aiCustomXSearch = false; aiPresetLoadGeneration++; aiSelectionTarget.revoke()
        translation.close()
        releaseAssistantViews()
        if (assistantAreaInitialized) assistantArea.removeAllViews()
        // Reset before cancelTransient: held-voice cancellation must not rebuild an obsolete
        // body before a new editor's language/privacy policy has been resolved.
        panel = "keys"
        if (candidateStripInitialized) candidateStrip.releasePresentation()
        candidateGrid?.let { grid ->
            grid.releasePresentation()
            (grid.parent as? ViewGroup)?.removeView(grid)
        }
        candidateGrid = null
        candidatesExpanded = false
        if (releasePanel && bodyInitialized) {
            // Content rows own complete strings and callbacks; emoji/other panels own their
            // models/listeners. Drop only the finished panel, retaining ordinary letter keys.
            body.removeAllViews()
            letterKeyViews.clear()
            shiftKeyView = null
        }
        if (preeditInitialized) {
            preedit.text = ""
            preedit.visibility = View.GONE
        }
        voicePreview = ""
        voiceText?.text = ""
        voiceStatus?.text = ""
        voiceText = null
        voiceStatus = null
        voiceWaveform = null
    }

    internal fun KeyboardSession.chooseImpl(value: String): Boolean = choose(value) {}

    internal fun KeyboardSession.chooseImpl(value: String, feedback: () -> Unit): Boolean {
        dismissQuickPasteOnInput()
        val ic = currentInputConnection ?: return false
        // A queued tap on the retired word must not cancel the next-word loading presentation.
        if (pinyinPredictionGeneration == generation && candidateTexts.isEmpty()) return false
        val kind = if (chinese) DictionaryKind.CHINESE else DictionaryKind.ENGLISH
        val dictionaryRevision = candidateDictionaryRevision
        if (candidateDictionaryKind != kind || dictionaryRevision != content.dictionaryRevision(kind) || candidateRuntimeRevision != engine.revision) {
            clearCandidates()
            renderHeader()
            return false
        }
        if (
            !active || secret || candidateGeneration != generation ||
                candidateConnection !== ic || value !in candidateTexts
        ) return false
        if (chinese) {
            if (candidateQuery != code) return false
            if (settings.chineseInputMode == io.secure.keyboard.engine.ChineseInputMode.PINYIN) {
                val choice = pinyinCandidates.firstOrNull { it.text == value } ?: return false
                feedback()
                if (choice.consumed < code.length) {
                    val remaining = code.drop(choice.consumed).trimStart('\'')
                    val consumed = code.dropLast(remaining.length)
                    pinyinComposition.select(value, consumed)
                    code = remaining
                    refreshCandidates()
                    return true
                }
                val sentence = pinyinComposition.text + value
                if (!commitInputText(sentence, glossary = true)) return false
                pinyinContext = (pinyinContext + sentence).takeLast(16)
                clearComposition()
                refreshCandidates()
                return true
            }
            if (code.isEmpty() && pinyinContext.isEmpty()) return false
            feedback()
            if (!commitInputText(value, glossary = true)) return false
            pinyinContext = (pinyinContext + value).takeLast(16)
            clearComposition()
            refreshCandidates()
        } else {
            if (candidateQuery.isEmpty() || !settings.englishAssociation) return false
            val expected = candidateQuery
            activeAssistantEditor()?.let { editor ->
                if(assistantEnglishPrefix(editor)!=expected) return false
                val end=editor.selectionStart
                val existingSpace=editor.text.getOrNull(end)==' '
                feedback()
                editor.text.replace(end-expected.length,end,if(existingSpace) value else "$value ")
                editor.setSelection((end-expected.length+value.length+1).coerceAtMost(editor.length()))
                clearPending(); refreshCandidates()
                return true
            }
            val seq = ++generation
            englishConfirmation = seq
            cancelCandidateQueries()
            worker.execute {
                val prefix = englishPrefix(ic)
                val following = try { ic.getTextAfterCursor(1, 0)?.toString() }
                    catch (_: RuntimeException) { null }
                val existingSpace = following?.startsWith(" ") == true
                main.post {
                    try {
                    if (following == null) return@post
                    if (
                        !active ||
                            chinese ||
                            secret ||
                            !settings.englishAssociation ||
                            seq != generation ||
                            currentInputConnection !== ic ||
                            prefix != expected ||
                            candidateQuery != expected ||
                            candidateDictionaryKind != kind ||
                            candidateDictionaryRevision != dictionaryRevision
                    )
                        return@post
                    // A deletion/edit can occur after the tap and during either host-prefix read.
                    // Validate the same language's revision again before host mutation.
                    if (dictionaryRevision != content.dictionaryRevision(kind)) {
                        clearCandidates()
                        renderHeader()
                        return@post
                    }
                    feedback()
                    ic.beginBatchEdit()
                    try {
                        if (dictionaryRevision != content.dictionaryRevision(kind)) {
                            clearCandidates()
                            renderHeader()
                            return@post
                        }
                        if (!ic.deleteSurroundingText(prefix.length, 0)) return@post
                        // Cursor=2 advances over the existing ASCII space after the replacement.
                        // Otherwise insert exactly one new space without touching host suffix text.
                        if (!ic.commitText(outputText(if (existingSpace) value else "$value "),
                                if (existingSpace) 2 else 1)) return@post
                    } finally {
                        ic.endBatchEdit()
                    }
                    clearComposition()
                    renderHeader()
                    } finally {
                        if (englishConfirmation == seq) {
                            englishConfirmation = null
                            replayQueuedKeys()
                        }
                    }
                }
            }
            return true
        }
        renderHeader()
        return true
    }

    internal fun KeyboardSession.commitInputTextImpl(value: String, glossary: Boolean = false): Boolean {
        dismissQuickPasteOnInput()
        if (aiCommandVoice != null || inlineOfflineVoice != null) cancelAiCommandVoice()
        if (translation.enabled) {
            activeAssistantEditor()?.let { editor ->
                syncingAssistantEditor = true
                try {
                    val preview = previewEditor === editor
                    val start = (if (preview) editorPreview.startOffset else minOf(editor.selectionStart, editor.selectionEnd))
                        .coerceIn(0, editor.length())
                    val end = (if (preview) start + editorPreview.length else maxOf(editor.selectionStart, editor.selectionEnd))
                        .coerceIn(start, editor.length())
                    val edit = if (settings.autoChineseSpacing && !secret)
                        ChineseSpacing.format(value, editor.text.substring(0, start), editor.text.substring(end))
                        else ChineseSpacing.Edit(value, false)
                    val retainedLength = editor.length() - (end - start)
                    if (preview) {
                        if (!editorPreview.commit(edit.text)) return false
                        clearEditorPreview()
                    } else editor.text.replace(start, end, edit.text)
                    val insertedLength = (editor.length() - retainedLength).coerceAtLeast(0)
                    val trailing = if (edit.trailingSpace && insertedLength == edit.text.length) 1 else 0
                    editor.setSelection((start + insertedLength - trailing).coerceIn(0, editor.length()))
                } finally { syncingAssistantEditor = false }
                aiDraftEpoch++
                if (aiMode) translation.editDraft(editor.text.toString()) else translation.edit(editor.text.toString())
                updateTranslationPreview()
                return true
            }
            if (aiMode) stopAiRequest()
            val formatted = if (settings.autoChineseSpacing && !secret) ChineseSpacing.format(value, translation.text).text else value
            val accepted = translation.edit(translation.text + formatted)
            if (!accepted) toast("原文最多 5000 字符")
            updateTranslationPreview()
            return accepted
        }
        val previewTarget = currentInputConnection
        val type = currentInputEditorInfo?.inputType ?: 0
        val variation = type and android.text.InputType.TYPE_MASK_VARIATION
        val spaced = settings.autoChineseSpacing && !secret && value.isNotEmpty() &&
            type and android.text.InputType.TYPE_MASK_CLASS == android.text.InputType.TYPE_CLASS_TEXT &&
            variation !in listOf(android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                android.text.InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, android.text.InputType.TYPE_TEXT_VARIATION_URI)
        // Replace the composing span in one edit. Context is optional: a slow host must
        // never hold up confirmation, erase preedit first, or patch the cursor afterwards.
        if (editorPreview.owns(previewTarget) || spaced) {
            val connection = previewTarget ?: return false
            val ownsPreview = editorPreview.owns(connection)
            val start = if (ownsPreview) editorPreview.startOffset else spacingCursor
            val edit = if (spaced) spacingContext.format(connection, value, start >= 0) else ChineseSpacing.Edit(value, false)
            val output = outputText(edit.text)
            val end = (start + output.length - if (edit.trailingSpace) 1 else 0).takeIf { start >= 0 }
            for (cursor in editorPreview.cursorsOnCommit() + listOfNotNull((start + output.length).takeIf { start >= 0 }, end)) {
                if (spacingExpectedCursors.size >= 1024) spacingExpectedCursors.removeFirst()
                spacingExpectedCursors.addLast(cursor)
            }
            val wasSyncing = syncingAssistantEditor
            syncingAssistantEditor = true
            connection.beginBatchEdit()
            val accepted = try {
                // Batch the optional suffix cursor placement with the replacement so the
                // host sees the final position, never an empty composing span between edits.
                (if (ownsPreview) editorPreview.commit(output) else connection.commitText(output, 1)).also { committed ->
                    if (committed && edit.trailingSpace && end != null)
                        connection.setSelection(end, end)
                }
            } finally {
                connection.endBatchEdit()
                syncingAssistantEditor = wasSyncing
            }
            if (accepted) {
                if (spaced) spacingContext.committed(connection, output, edit.trailingSpace)
                else spacingContext.invalidate()
                spaceGlossaryCommitted(output.length, glossary, end,
                    committed = if (spaced) value.takeIf { it.length in 2..4 }?.let(::outputText) else output)
            } else spacingContext.invalidate()
            return accepted
        }
        val output = outputText(value)
        val connection = currentInputConnection ?: return false
        connection.beginBatchEdit()
        val accepted = try { connection.commitText(output, 1) }
            finally { connection.endBatchEdit() }
        if (accepted) {
            spacingContext.invalidate()
            spaceGlossaryCommitted(output.length, glossary, committed = output)
        } else spacingContext.invalidate()
        return accepted
    }

    internal fun KeyboardSession.commitImpl(value: String) {
        pinyinContext = ""
        commitInputText(value)
        refreshCandidates()
    }

    internal fun KeyboardSession.typeImpl(s: String, fromSymbolPage: Boolean = false) {
        val asCode =
            !fromSymbolPage && chinese &&
                !symbols &&
                !secret &&
                !shifted &&
                !capsLocked &&
                s.length == 1 &&
                (s[0] in 'a'..'z' || (s == "'" && code.isNotEmpty() && settings.chineseInputMode == io.secure.keyboard.engine.ChineseInputMode.PINYIN))
        // Capture case/mode at release. A later Shift or symbol-page change must not reinterpret
        // a key that is waiting behind an earlier Chinese confirmation.
        if (shifted && !capsLocked) {
            shifted = false
            updateShiftKeyFaces()
        }
        val rule = if (fromSymbolPage) io.secure.keyboard.data.SymbolClosureStore(this).rules().firstOrNull { it.opening == s } else null
        dispatchKey(if (rule != null) QueuedKey.EnglishPair(rule.opening, rule.closing, rule.moveBackUnits) else QueuedKey.Text(s, asCode))
    }

    internal fun KeyboardSession.dispatchKeyImpl(key: QueuedKey) {
        dismissQuickPasteOnInput()
        if (key != QueuedKey.Backspace) assistantBackspaceTapAt = 0L
        if (pendingConfirmation != null || englishConfirmation != null || queueReplayPosted) {
            if (queuedKeys.size >= 1024) {
                if (!queueSaturationNotified) {
                    queueSaturationNotified = true
                    toast("等待输入过多，请先取消待输入内容再继续")
                }
                return
            }
            queuedKeys.addLast(key)
            return
        }
        applyKey(key)
    }

    internal fun KeyboardSession.applyKeyImpl(key: QueuedKey) {
        when (key) {
            is QueuedKey.Text -> applyText(key.value, key.asCode)
            is QueuedKey.EnglishPair -> applyEnglishPair(key.opening, key.closing, key.moveBackUnits)
            QueuedKey.Space -> applySpace()
            QueuedKey.Enter -> applyEnter()
            QueuedKey.SubmitAssistant -> applyAssistantSubmitImpl()
            QueuedKey.Backspace -> applyBackspace()
            QueuedKey.RepeatedBackspace -> performBackspace(false)
        }
    }

    internal fun KeyboardSession.applyEnglishPairImpl(opening: String, closing: String, moveBackUnits: Int) {
        if (translation.enabled) {
            val editor = activeAssistantEditor()
            if (editor == null) { commit(opening + closing); return }
            val start = minOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
            val end = maxOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(start)
            // Reject the whole pair at the limit instead of letting LengthFilter insert half.
            if (editor.length() - (end - start) + opening.length + closing.length > 5000) {
                toast("原文最多 5000 字符"); return
            }
            if (!commitInputText(opening + closing)) return
            editor.setSelection(start + opening.length + closing.length - moveBackUnits)
            sourceCursor = editor.selectionStart
            symbols = false
            symbolPage = false
            renderBody(true)
            refreshCandidates()
            return
        }
        val connection = currentInputConnection ?: return
        connection.beginBatchEdit()
        val inserted = try {
            // Replace the selection once, then move back by the configured UTF-16 offset with an empty
            // edit. A nonempty commit at cursor=0 is unreliable on older BaseInputConnection:
            // its selection marker advances across the newly inserted character. The negative offset is
            // relative to the already-collapsed caret, and no second text insertion can move it.
            // Both operations use the same connection/batch; no text reads or queued key events.
            connection.commitText(outputText(opening + closing), 1) && (moveBackUnits == 0 || connection.commitText("", -moveBackUnits))
        } finally {
            connection.endBatchEdit()
        }
        if (!inserted) return
        symbols = false
        symbolPage = false
        renderBody()
        refreshCandidates()
    }

    internal fun KeyboardSession.applyTextImpl(s: String, asCode: Boolean) {
        if (asCode) {
            if(aiMode) aiDraftEpoch++
            if (translation.enabled && !aiMode) { translation.invalidate(); updateTranslationPreview() }
            if (code.length + pinyinComposition.rawLength >= if (settings.chineseInputMode == io.secure.keyboard.engine.ChineseInputMode.PINYIN) 128 else 40) return
            code += s.lowercase()
            if (settings.chineseInputMode != io.secure.keyboard.engine.ChineseInputMode.PINYIN &&
                settings.fourCodeAutoCommit && code.length == 4 && loaded) {
                currentInputConnection?.let {
                    // Queue subsequent keys while the worker finds the exact Wubi candidate.
                    // Never acquire a dictionary lock or open SQLite on the input/UI thread.
                    pendingConfirmation = PendingConfirmation(code, it, null, automaticWubi = true)
                }
            }
            refreshCandidates()
        } else {
            if (chinese && code.isNotEmpty()) {
                if (!confirmChinese(s)) return
            }
            commit(s)
        }
    }

    /** Returns true only when the candidate has already been committed synchronously. */
    internal fun KeyboardSession.confirmChineseImpl(suffix: String?): Boolean {
        val connection = currentInputConnection ?: return false
        val ready = candidateGeneration == generation && candidateQuery == code &&
            candidateConnection === connection &&
            candidateDictionaryKind == DictionaryKind.CHINESE &&
            candidateDictionaryRevision == content.dictionaryRevision(DictionaryKind.CHINESE)
        if (ready) {
            if (candidateTexts.isEmpty()) {
                toast("没有匹配候选，请修改编码或按回车输入编码")
                return false
            }
            val committed = choose(candidateTexts.first())
            if (!committed) toast("输入框暂时无法接收文字，请重试")
            if (committed && code.isNotEmpty() && suffix != null) {
                confirmChinese(suffix)
                return false
            }
            return committed
        }
        if (!loaded && dictionaryLoadFailed) {
            toast("词库不可用，可按回车输入原始编码")
            return false
        }
        pendingConfirmation = PendingConfirmation(code, connection, suffix)
        // Keep confirmation/queued keys separate from the latest derived query. Never commit a stale row.
        refreshCandidates()
        return false
    }

    internal fun KeyboardSession.finishPendingConfirmationImpl() {
        val pending = pendingConfirmation ?: return
        if (!active || !chinese || secret || currentInputConnection !== pending.connection ||
            code != pending.code) {
            clearPending()
            renderHeader()
            return
        }
        if (candidateGeneration != generation || candidateQuery != pending.code ||
            candidateConnection !== pending.connection) return
        pendingConfirmation = null
        if (pending.automaticWubi) {
            val value = candidateExactWubi
            if (settings.fourCodeAutoCommit && value != null &&
                candidateRuntimeRevision == engine.revision &&
                candidateDictionaryRevision == content.dictionaryRevision(DictionaryKind.CHINESE)) {
                if (commitInputText(value, glossary = true)) {
                    pinyinContext = (pinyinContext + value).takeLast(16)
                    clearComposition()
                    refreshCandidates()
                }
            }
            // No exact match or a rejected host edit retains the composition for explicit retry.
            replayQueuedKeys()
            return
        }
        val first = candidateTexts.firstOrNull()
        if (first == null) {
            toast("没有匹配候选，请修改编码或按回车输入编码")
        } else if (choose(first)) {
            pending.suffix?.let { if (code.isEmpty()) commit(it) else confirmChinese(it) }
        } else {
            toast("输入框暂时无法接收文字，请重试")
        }
        replayQueuedKeys()
    }

    /** Failure revokes only the wait; the original code remains editable and later taps replay. */
    internal fun KeyboardSession.releasePendingConfirmationImpl() {
        pendingConfirmation = null
        replayQueuedKeys()
    }

    internal fun KeyboardSession.replayQueuedKeysImpl() {
        if (queueReplayPosted) return
        while (active && pendingConfirmation == null && englishConfirmation == null && queuedKeys.isNotEmpty()) {
            val key = queuedKeys.removeFirst()
            applyKey(key)
            if (key == QueuedKey.Backspace || key == QueuedKey.RepeatedBackspace || key == QueuedKey.Enter) {
                // Host key events are dispatched asynchronously. Let them run before a later commit.
                val epoch = queueEpoch
                queueReplayPosted = true
                main.post {
                    if (epoch == queueEpoch) {
                        queueReplayPosted = false
                        replayQueuedKeys()
                    }
                }
                return
            }
        }
        if (queuedKeys.isEmpty()) queueSaturationNotified = false
        startHeldVoiceWhenKeysSettle()
    }

    internal fun KeyboardSession.backspaceImpl() = dispatchKey(QueuedKey.Backspace)

    internal fun KeyboardSession.applyBackspaceImpl() = performBackspace(true)

    internal fun KeyboardSession.performBackspaceImpl(tap: Boolean) {
        dismissQuickPasteOnInput()
        pinyinContext = ""
        if (chinese && settings.chineseInputMode == io.secure.keyboard.engine.ChineseInputMode.PINYIN && (code.isNotEmpty() || pinyinComposition.text.isNotEmpty())) {
            if (code.isNotEmpty()) code = code.dropLast(1)
            if (code.isEmpty()) pinyinComposition.undo()?.let { code = it }
            refreshCandidates()
            return
        }
        // Unconfirmed Chinese input belongs to the composition, not the editor's text.
        if (translation.enabled && chinese && code.isNotEmpty()) {
            assistantBackspaceTapAt = 0L
            code = code.dropLast(1)
            refreshCandidates()
            return
        }
        activeAssistantEditor()?.let { editor ->
            if(editor.text.isNotEmpty()) {
                assistantBackspaceTapAt = 0L
                val start=minOf(editor.selectionStart,editor.selectionEnd).coerceAtLeast(0)
                val end=maxOf(editor.selectionStart,editor.selectionEnd).coerceAtLeast(start)
                if(start!=end) editor.text.delete(start,end)
                else if(start>0) editor.text.delete(Character.offsetByCodePoints(editor.text,start,-1),start)
                return
            }
        }
        if (translation.enabled && translation.text.isEmpty() && code.isEmpty()) {
            if (phraseMode) return
            val now = SystemClock.uptimeMillis()
            if (tap && assistantBackspaceTapAt != 0L &&
                now - assistantBackspaceTapAt <= ViewConfiguration.getDoubleTapTimeout()) {
                assistantBackspaceTapAt = 0L
                if (aiMode) resetAiConversation()
                openTool(if (aiMode) "ai" else "translate")
            } else assistantBackspaceTapAt = if (tap) now else 0L
            return
        }
        assistantBackspaceTapAt = 0L
        if (translation.enabled && !aiMode) { translation.invalidate(); updateTranslationPreview() }
        if (chinese && code.isNotEmpty()) {
            code = code.dropLast(1)
            refreshCandidates()
        } else if (translation.enabled) {
            if (aiMode) stopAiRequest()
            translation.backspace(); updateTranslationPreview(); refreshCandidates()
        } else {
            // Delegate selection and Unicode deletion to the host's standard Backspace handler.
            // Synchronous text reads here can deadlock a same-process WebView renderer.
            arrow(KeyEvent.KEYCODE_DEL)
            refreshCandidates()
        }
    }

    /** Host cursor navigation preserves the editor's own Unicode/grapheme and selection rules. */
    internal fun KeyboardSession.moveCursorImpl(direction: Int) {
        pinyinContext = ""; clearCandidates()
        if (translation.enabled && activeAssistantEditor()==null) return
        arrow(if (direction < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
    }

    internal fun KeyboardSession.spaceImpl() = dispatchKey(QueuedKey.Space)

    internal fun KeyboardSession.applySpaceImpl() {
        if (chinese && code.isNotEmpty()) {
            confirmChinese(null)
        } else if (!chinese && settings.englishSpaceSelectsFirst && candidateTexts.isNotEmpty() && choose(candidateTexts.first())) {
            // English completion owns replacement and its single trailing space.
            return
        } else commit(" ")
    }

    internal fun KeyboardSession.enterImpl() = dispatchKey(QueuedKey.Enter)

    internal fun KeyboardSession.applyEnterImpl() {
        if (translation.enabled && !phraseMode) {
            val pending = if (code.isNotEmpty()) pinyinComposition.text + code else ""
            if (commitInputText(pending + "\n")) {
                clearComposition()
                renderHeader()
            }
            return
        }
        if (chinese && code.isNotEmpty()) {
            val rawCode = pinyinComposition.text + code
            if (!commitInputText(rawCode)) return
            clearComposition()
            renderHeader()
            return
        }
        if (phraseMode) { commitInputText("\n"); return }
        if (!sendDefaultEditorAction(true)) commit("\n")
    }

    internal fun KeyboardSession.applyAssistantSubmitImpl() {
        if (!translation.enabled || phraseMode || !active || secret) return
        if (aiMode && translation.busy) {
            if (aiCall == null) return
            stopAiRequest()
            val token = translation.begin()
            translation.complete(token, "已停止生成", true)
            updateAiPreview()
            return
        }
        if (translation.busy) return
        if (code.isNotEmpty()) {
            if (!commitInputText(pinyinComposition.text + code)) return
            clearComposition()
            renderHeader()
        }
        requestTranslation()
    }

}
