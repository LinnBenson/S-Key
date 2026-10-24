package io.secure.keyboard.ime

import android.content.*
import android.content.Context.MODE_PRIVATE
import android.os.*
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.*
import kotlin.math.*
import io.secure.keyboard.data.ContentStore
import io.secure.keyboard.ui.ToolIcon
import io.secure.keyboard.voice.OfflineVoiceSession

import io.secure.keyboard.*

/** AI and translation requests, inline editing and presentation. */
internal object AssistantController {
    internal fun KeyboardSession.activeAssistantEditorImpl(): EditText? =
        if (!translation.enabled) null
        else translationTextView as? EditText

    internal fun KeyboardSession.syncAssistantTextImpl(view: TextView?, value: String) {
        if (view == null || view.text.toString() == value || (view === previewEditor && code.isNotEmpty())) return
        syncingAssistantEditor = true
        try {
            val cursor=(view as? EditText)?.selectionStart ?: value.length
            view.text=value
            (view as? EditText)?.setSelection(cursor.coerceIn(0,value.length))
        } finally { syncingAssistantEditor=false }
    }

    internal fun KeyboardSession.assistantEditorImpl(): EditText = object: EditText(this) {
        override fun onSelectionChanged(start:Int,end:Int) {
            super.onSelectionChanged(start,end)
            if (!syncingAssistantEditor && active && activeAssistantEditor() === this && previewEditor === this) {
                // A local selection move ends preedit without deleting at the new cursor.
                if (editorPreview.selectionChanged(start, end, -1, -1)) {
                    previewEditor = null
                    clearPending()
                    if (aiMode) translation.editDraft(text.toString()) else translation.edit(text.toString())
                }
            }
            if(!syncingAssistantEditor && active && activeAssistantEditor()===this && !chinese) refreshCandidates()
        }
    }.apply {
        setText(translation.text)
        textSize=14f
        setTextColor(fg); setHintTextColor(muted)
        hint=""
        background=null
        setPadding(0,0,0,0)
        gravity=Gravity.START or Gravity.TOP
        inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        showSoftInputOnFocus=false
        isSaveEnabled=false
        importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        imeOptions=EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        isVerticalScrollBarEnabled=false; isHorizontalScrollBarEnabled=false
        filters=arrayOf(android.text.InputFilter.LengthFilter(5000))
        setSelection(sourceCursor.let { if(it<0) length() else it.coerceAtMost(length()) })
        setOnFocusChangeListener { _, focused -> if(focused) {
            if(!chinese) refreshCandidates()
        } }
        addTextChangedListener(object: android.text.TextWatcher {
            override fun beforeTextChanged(t:CharSequence?, start:Int,count:Int,after:Int) {}
            override fun onTextChanged(t:CharSequence?, start:Int,before:Int,count:Int) {}
            override fun afterTextChanged(t:android.text.Editable?) {
                if(syncingAssistantEditor || !active || !translation.enabled) return
                aiDraftEpoch++
                if (aiMode) translation.editDraft(t.toString())
                else translation.edit(t.toString())
                updateTranslationPreview()
                refreshCandidates()
            }
        })
    }

    internal fun KeyboardSession.clearAssistantSourceImpl() {
        if (aiMode) {
            stopAiRequest(); resetAiConversation(); clearAiConversation(); aiSelectionTarget.revoke()
        }
        translation.edit(""); clearComposition(); renderBody(false); refreshCandidates()
    }

    internal fun KeyboardSession.insertAssistantResultImpl() {
        if (!active || secret || !translation.enabled || translation.busy || code.isNotEmpty() || translation.result.isEmpty()) return
        if (aiMode) applyAiResult()
        else if (currentInputConnection?.commitText(outputText(translation.result),1)==true) {
            translation.edit(""); clearComposition(); updateTranslationPreview(); refreshCandidates()
        }
    }

    internal fun KeyboardSession.quickEditConnectionImpl(): android.view.inputmethod.InputConnection? {
        if (!translation.enabled) return currentInputConnection
        val editor = activeAssistantEditor() ?: return null
        return assistantEditConnection ?: object : android.view.inputmethod.BaseInputConnection(editor, true) {
            override fun sendKeyEvent(event: KeyEvent): Boolean = when (event.action) {
                KeyEvent.ACTION_DOWN -> editor.onKeyDown(event.keyCode, event)
                KeyEvent.ACTION_UP -> editor.onKeyUp(event.keyCode, event)
                else -> false
            }
        }.also { assistantEditConnection = it }
    }

    internal fun KeyboardSession.quickEditActionImpl(action: Int) {
        if (translation.enabled) activeAssistantEditor()?.let { editor ->
            editor.requestFocus()
            editor.onTextContextMenuItem(action)
        } else if (action == android.R.id.paste && settings.traditionalOutput && !secret) {
            val value = try { clips.primaryClip?.let { io.secure.keyboard.data.ClipboardCapturePolicy.textForHistory(it) } }
                catch (_: SecurityException) { null }
            if (value != null) commitInputText(value)
        } else currentInputConnection?.performContextMenuAction(action)
    }

    internal fun KeyboardSession.releaseAssistantViewsImpl() {
        if (assistantEditConnection != null) quickEditSelection.reset()
        assistantEditConnection = null
        sourceCursor=(translationTextView as? EditText)?.selectionStart ?: sourceCursor
        syncingAssistantEditor=true
        aiCustomTab=null
        aiTaskTabs=null
        assistantResultTitle=null
        aiResultNavigation=null; assistantSourceTitle=null
        translationTextView?.text = ""
        translationResultView?.text = ""
        translationTextView = null
        translationResultView = null
        translationAction = null
        translationInsert = null
        syncingAssistantEditor=false
    }

    internal fun KeyboardSession.stopTranslationRequestImpl() {
        translationCall?.cancel(); translationCall = null
        translationTask?.let { translationWorker.remove(it) }; translationTask = null
    }

    internal fun KeyboardSession.stopAiRequestImpl() {
        aiPreviewDelivery?.close(); aiPreviewDelivery = null
        cancelAiCommandVoice()
        aiCall?.cancel(); aiCall = null
        aiTaskRunnable?.let { aiWorker.remove(it) }; aiTaskRunnable = null
    }

    internal fun KeyboardSession.bindSourcePasteImpl(heading: View, title: View, text: TextView) {
        val listener = View.OnLongClickListener {
            if (active && !secret && translation.enabled && translationTextView === text && text.isAttachedToWindow && !translation.busy) {
                val value = runCatching { clips.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text }.getOrNull()
                if (value != null && pasteSourceText(value)) {
                    toast("已粘贴")
                    if (translation.text.isNotBlank()) requestTranslation()
                }
            }
            true
        }
        heading.setOnLongClickListener(listener)
        title.setOnLongClickListener(listener)
        title.contentDescription = "长按粘贴剪贴板并生成结果"
    }

    internal fun KeyboardSession.bindCardLongPressImpl(view: View, listener: View.OnLongClickListener) {
        view.setOnLongClickListener(listener)
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) bindCardLongPress(view.getChildAt(i), listener)
        }
    }

    internal fun KeyboardSession.bindResultCopyImpl(card: View, text: TextView) {
        // The content fills its ScrollView, so blank space below a short result is tappable too.
        // Keep the heading/history controls independent and let ScrollView cancel a drag.
        for (target in listOfNotNull<View>(text, text.parent as? ScrollView)) {
            var touchRevision: Long? = null
            var validAtDown = false
            target.setOnTouchListener { _, event ->
                if(event.actionMasked == MotionEvent.ACTION_DOWN) {
                    touchRevision = translation.revision.toLong()
                    validAtDown = !translation.busy && translation.result.isNotEmpty()
                } else if(event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    validAtDown = false
                }
                false
            }
            target.setOnClickListener {
                val validTouch = touchRevision == null || (validAtDown && touchRevision == translation.revision.toLong())
                touchRevision = null
                if(validTouch && translationResultView === text && text.isAttachedToWindow) insertAssistantResult()
            }
        }
        bindCardLongPress(card, View.OnLongClickListener {
            if (active && !secret && translation.enabled && translationResultView === text &&
                text.isAttachedToWindow && !translation.busy && translation.result.isNotEmpty()) {
                runCatching { clips.setPrimaryClip(android.content.ClipData.newPlainText("", translation.result)) }
                    .onSuccess { toast("已复制") }
                    .onFailure { toast("复制失败") }
            }
            true
        })
    }

    internal fun KeyboardSession.pasteSourceTextImpl(value: CharSequence): Boolean {
        if (!active || secret || !translation.enabled) return false
        val remaining = 5000 - translation.text.length
        var end = minOf(value.length, remaining)
        if (end > 0 && end < value.length && Character.isHighSurrogate(value[end - 1]) && Character.isLowSurrogate(value[end])) end--
        if (end <= 0) return false
        stopAiRequest(); clearComposition()
        translation.edit(translation.text + value.subSequence(0, end))
        if (aiMode) renderBody(false) else updateTranslationPreview()
        refreshCandidates()
        return true
    }

    internal fun KeyboardSession.updateAiPreviewImpl() {
        if(!translation.busy && aiConversation?.valid(System.currentTimeMillis())==false) resetAiConversation()
        aiCustomTab?.text=io.secure.keyboard.ai.AiWriting.customTabTitle(aiCustomPresetName)
        assistantResultTitle?.text = if (translation.busy) "生成中" else "AI 结果"
        assistantSourceTitle?.text = aiConversation?.let { "续聊 ${it.id.take(4)}" } ?: "原文"
        aiResultNavigation?.text = if (aiResults.isEmpty()) "0/0" else "${aiResultIndex + 1}/${aiResults.size}"

        syncAssistantText(translationTextView, translation.text)
        translationTextView?.setTextColor(if(translation.text.isEmpty()) muted else fg)
        translationResultView?.text=when {
            translation.busy -> translation.result.ifEmpty { "正在生成，请稍候…" }
            translation.result.isNotEmpty() -> translation.result
            translation.status == "输入原文后点击翻译" -> ""
            else -> translation.status
        }
        translationResultView?.setTextColor(if(translation.result.isEmpty()) muted else fg)
        translationAction?.apply {
            text=if(translation.busy) "停止" else if(translation.result.isNotEmpty()) "重新生成" else "生成"
            isEnabled=translation.busy || translation.text.isNotBlank()
            alpha=if(isEnabled) 1f else .45f
        }
        translationInsert?.apply {
            text=if(aiSelectionTarget.text!=null) "替换选中" else "插入"
            isEnabled=translation.result.isNotEmpty() && !translation.busy && code.isEmpty()
            alpha=if(isEnabled) 1f else .4f
        }
    }

    internal fun KeyboardSession.loadAssistantWidthImpl() {
        val saved = getSharedPreferences("settings", MODE_PRIVATE).getFloat("assistantSourceWeight", 0.35f)
        assistantSourceWeight = if (saved.isFinite()) saved.coerceIn(0.2f, 0.8f) else 0.35f
    }

    internal fun KeyboardSession.saveAssistantWidthImpl() {
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putFloat("assistantSourceWeight", assistantSourceWeight).apply()
    }

    internal fun KeyboardSession.addAssistantCardsImpl(row: LinearLayout, source: View, result: View) {
        val sourceParams = LinearLayout.LayoutParams(0, -1, assistantSourceWeight)
        val resultParams = LinearLayout.LayoutParams(0, -1, 1f - assistantSourceWeight)
        val handle = FrameLayout(this).apply {
            contentDescription = "左右拖动调整原文和结果宽度"
            isFocusable = true
            addView(View(this@addAssistantCardsImpl).apply {
                background = shape(dividerColor, 2)
            }, FrameLayout.LayoutParams(dp(1), dp(28), Gravity.CENTER))
        }
        fun resize(weight: Float) {
            assistantSourceWeight = weight.coerceIn(0.2f, 0.8f)
            sourceParams.weight = assistantSourceWeight
            resultParams.weight = 1f - assistantSourceWeight
            row.requestLayout()
        }
        var downX = 0f
        var downWeight = assistantSourceWeight
        handle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downWeight = assistantSourceWeight
                    view.parent.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val available = row.width - row.paddingLeft - row.paddingRight - handle.width
                    if (available > 0) resize(downWeight + (event.rawX - downX) / available)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.parent.requestDisallowInterceptTouchEvent(false)
                    if (event.actionMasked == MotionEvent.ACTION_UP) saveAssistantWidth()
                    else resize(downWeight)
                    true
                }
                else -> false
            }
        }
        handle.setOnKeyListener { _, key, event ->
            if (event.action == KeyEvent.ACTION_DOWN && key in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) {
                resize(assistantSourceWeight + if (key == KeyEvent.KEYCODE_DPAD_LEFT) -0.05f else 0.05f)
                saveAssistantWidth()
                true
            } else false
        }
        row.addView(source, sourceParams)
        row.addView(handle, LinearLayout.LayoutParams(dp(8), -1))
        row.addView(result, resultParams)
    }

    internal fun KeyboardSession.renderAiBarImpl(column: LinearLayout) {
        val extra=translationExtraHeightPx()
        val area=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(4),dp(12),dp(4),0) }
        column.addView(area,LinearLayout.LayoutParams(-1,extra))
        val unit=minOf(dp(32),extra/4)
        val tasks=LinearLayout(this).also { aiTaskTabs = it }
        area.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; isFillViewport=true; addView(tasks) },LinearLayout.LayoutParams(-1,unit))
        renderAiTaskTabs(tasks)
        val cards=LinearLayout(this)
        area.addView(cards,LinearLayout.LayoutParams(-1,0,1f).apply { topMargin=dp(6) })
        fun card(title:String,source:Boolean): View {
            val card=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL; background=shape(keyBg,10)
                setPadding(dp(if(source) 6 else 10),dp(6),dp(if(source) 6 else 10),dp(6))
            }
            val heading=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
            val titleView=label(title,10f,muted).apply {
                gravity=Gravity.START or Gravity.CENTER_VERTICAL; setSingleLine(true)
                if(source) setAutoSizeTextTypeUniformWithConfiguration(6,10,1,android.util.TypedValue.COMPLEX_UNIT_SP)
            }
            heading.addView(titleView,LinearLayout.LayoutParams(0,-1,1f))
            if (source) { assistantSourceTitle=titleView; heading.addView(icon("clear","清空原文") { clearAssistantSource() },LinearLayout.LayoutParams(dp(24),-1)) }
            else assistantResultTitle=titleView
            if (!source) {
                heading.addView(icon("previous","上一版") { selectAiResult(-1) },LinearLayout.LayoutParams(dp(26),-1))
                aiResultNavigation=label("",10f,muted).apply { setSingleLine(true) }
                heading.addView(aiResultNavigation,LinearLayout.LayoutParams(dp(30),-1))
                heading.addView(icon("forward","下一版") { selectAiResult(1) },LinearLayout.LayoutParams(dp(26),-1))
            }
            card.addView(heading,LinearLayout.LayoutParams(-1,dp(24)))
            val text: TextView=if(source) assistantEditor() else label("",14f).apply { gravity=Gravity.START or Gravity.TOP }
            if(source) card.addView(text,LinearLayout.LayoutParams(-1,0,1f))
            else card.addView(ScrollView(this).apply { isFillViewport=true; isVerticalScrollBarEnabled=false; isHorizontalScrollBarEnabled=false; addView(text) },LinearLayout.LayoutParams(-1,0,1f))
            if(source) {
                translationTextView=text
                bindSourcePaste(heading, titleView, text)
                text.contentDescription="AI 原文，点击切换到原文输入"
            } else { translationResultView=text; bindResultCopy(card, text) }
            return card
        }
        addAssistantCards(cards, card("原文",true), card("AI 结果",false))
        translationTextView?.requestFocus()

        updateAiPreview()
    }

    private fun KeyboardSession.renderAiTaskTabs(tasks: LinearLayout) {
        tasks.removeAllViews()
        aiCustomTab = null
        fun compact(text: String, selected: Boolean=false, action:()->Unit): TextView = button(text,action).apply {
            textSize=12f; setPadding(dp(7),0,dp(7),0); setSingleLine(true)
            ellipsize=android.text.TextUtils.TruncateAt.END
            background=shape(if(selected) accent else keyBg,9); setTextColor(if(selected) enterText else fg)
        }
        io.secure.keyboard.ai.AiWriting.tasks.forEach { (id,name) ->
            if (id == "custom" && aiCustomPresetName.isEmpty()) return@forEach
            val title = when(id) {
                "custom" -> io.secure.keyboard.ai.AiWriting.customTabTitle(aiCustomPresetName)
                "tone" -> "语气: $aiTone"
                else -> name
            }
            val tab = compact(title, aiTask == id) {}
            tab.setOnClickListener {
                if (aiTask == id) {
                    when(id) {
                        "custom" -> showCustomAiPresets(tab)
                        "tone" -> showAiToneChoices(tab)
                    }
                } else {
                    stopAiRequest(); clearComposition(); resetAiConversation(); aiTask = id; translation.invalidate()
                    if (id == "custom") settings.lastCustomAiPresetId = aiCustomPresetId
                    getSharedPreferences("settings", MODE_PRIVATE).edit().putString("aiTask", id).apply()
                    renderBody(false)
                }
            }
            if (id == "custom") { aiCustomTab = tab; tab.contentDescription = aiCustomPresetName }
            tasks.addView(tab, LinearLayout.LayoutParams(dp(if (id == "custom" || id == "tone") 80 else 58), -1, 1f).apply { rightMargin = dp(5) })
        }
    }

    internal fun KeyboardSession.fillDefaultCustomAiPresetImpl() {
        if (!aiMode || !active || secret) return
        val generation = ++aiPresetLoadGeneration
        val store = content
        val epoch = store.customAiEpoch()
        worker.execute {
            val entries = readContent(emptyList<io.secure.keyboard.data.ContentStore.AiPreset>()) { store.aiPresets() }
            val selected = entries.firstOrNull { it.id == settings.lastCustomAiPresetId } ?: entries.firstOrNull()
            main.post {
                if (destroyed || generation != aiPresetLoadGeneration || content !== store ||
                    epoch != store.customAiEpoch() || !active || secret || !aiMode) return@post
                aiCustomPresetId = selected?.id
                aiCustomPresetName = selected?.name.orEmpty()
                aiCustom = selected?.prompt.orEmpty()
                aiCustomReasoning = selected?.reasoningEffort ?: "low"
                aiCustomModel = selected?.modelId.orEmpty()
                if (selected == null && aiTask == "custom") {
                    resetAiConversation(); aiTask = "polish"
                    getSharedPreferences("settings", MODE_PRIVATE).edit().putString("aiTask", aiTask).apply()
                }
                if (aiTask == "custom") { settings.lastCustomAiPresetId = selected?.id; restoreAiConversation() }
                // Metadata arrives after the user may already have selected or typed text.
                // Refresh only tabs; rebuilding the source editor loses selection and preedit.
                aiTaskTabs?.let { renderAiTaskTabs(it) }
                updateAiPreview()
            }
        }
    }

    private fun KeyboardSession.aiChoicePopup(anchor: View): Pair<LinearLayout, PopupWindow> {
        popup?.dismiss()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val scroll = ScrollView(this).apply { addView(column) }
        val width = box.width.coerceAtLeast(dp(220)) - dp(16)
        val window = PopupWindow(scroll, width, dp(220), true).apply {
            setBackgroundDrawable(shape(keyBg, 12))
            elevation = dp(8).toFloat()
            isOutsideTouchable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        }
        popup = window
        val detach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) { window.dismiss() }
        }
        anchor.addOnAttachStateChangeListener(detach)
        window.setOnDismissListener {
            anchor.removeOnAttachStateChangeListener(detach)
            column.removeAllViews()
            if (popup === window) popup = null
        }
        window.showAsDropDown(anchor, anchor.width - width, dp(4))
        return column to window

    }

    private fun <T> KeyboardSession.renderAiChoices(
        column: LinearLayout, window: PopupWindow, entries: List<T>, title: (T) -> String,
        isCurrent: (T) -> Boolean, kind: String, select: (T) -> Unit
    ) {
        val task = aiTask
        val editor = voiceEditorGeneration
        entries.chunked(3).forEach { group ->
            val row = LinearLayout(this)
            group.forEachIndexed { index, item ->
                val selected = isCurrent(item)
                row.addView(button(title(item)) {
                    if (popup === window && window.isShowing && active && !secret && aiMode &&
                        aiTask == task && voiceEditorGeneration == editor) {
                        window.dismiss(); select(item)
                    }
                }.apply {
                    gravity = Gravity.CENTER
                    background = shape(if (selected) accent else special, 10)
                    setTextColor(if (selected) enterText else fg)
                    isSelected = selected
                    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                    textSize = 13f; contentDescription = "${if (selected) "已选择" else "选择"}$kind：${title(item)}"
                }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (index < 2) rightMargin = dp(8) })
            }
            repeat(3 - group.size) { row.addView(View(this), LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (group.size + it < 2) rightMargin = dp(8) }) }
            column.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun KeyboardSession.showAiToneChoices(anchor: View) {
        if (!aiMode || aiTask != "tone" || !active || secret || !anchor.isAttachedToWindow) return
        val (column, window) = aiChoicePopup(anchor)
        renderAiChoices(column, window, io.secure.keyboard.ai.AiWriting.tones, { it }, { it == aiTone }, "语气") { tone ->
            stopAiRequest(); clearComposition()
            if (aiTone != tone) resetAiConversation()
            aiTone = tone
            getSharedPreferences("settings", MODE_PRIVATE).edit().putString("aiTone", tone).apply()
            translation.invalidate(); renderBody(false)
        }
    }

    internal fun KeyboardSession.showCustomAiPresetsImpl(anchor: View) {
        if (!aiMode || aiTask != "custom" || !active || secret || !anchor.isAttachedToWindow) return
        val (column, window) = aiChoicePopup(anchor)
        column.addView(label("正在读取…", 13f, muted))
        fun current() = !destroyed && !secret && popup === window && active && aiMode && aiTask == "custom" && anchor.isAttachedToWindow
        fun refresh() {
            val store = content
            val epoch = store.customAiEpoch()
            worker.execute {
                val entries = readContent(emptyList<io.secure.keyboard.data.ContentStore.AiPreset>()) { store.aiPresets() }
                main.post {
                    if (!current()) return@post
                    if (content !== store || epoch != store.customAiEpoch()) { window.dismiss(); return@post }
                    column.removeAllViews()
                    if (entries.isEmpty()) column.addView(label("在 AI 设置 → 使用设置 → 自定义 AI 中添加提示词", 13f, muted), LinearLayout.LayoutParams(-1, dp(64)))
                    val selectedId = entries.firstOrNull { it.id == aiCustomPresetId && it.prompt == aiCustom }?.id
                        ?: entries.firstOrNull { it.prompt == aiCustom }?.id
                    renderAiChoices(column, window, entries, { it.name }, { it.id == selectedId }, "自定义 AI") { preset ->
                        if (content !== store || epoch != store.customAiEpoch()) { fillDefaultCustomAiPreset(); return@renderAiChoices }
                        stopAiRequest(); clearComposition()
                        if(aiCustomPresetId!=preset.id || aiCustom!=preset.prompt || aiCustomReasoning!=preset.reasoningEffort || aiCustomModel!=preset.modelId) resetAiConversation()
                        aiCustomPresetId = preset.id
                        aiCustomPresetName = preset.name
                        settings.lastCustomAiPresetId = preset.id
                        aiCustom = preset.prompt; aiCustomReasoning = preset.reasoningEffort; aiCustomModel = preset.modelId
                        translation.invalidate(); renderBody(false)
                    }
                }
            }
        }
        refresh()
    }

    internal fun KeyboardSession.effectiveAiConfigurationImpl(): io.secure.keyboard.data.ApiConfiguration {
        val config = aiConfiguration()
        val model = io.secure.keyboard.ai.AiPromptStore(this).resolveModel(aiTask, aiTone, aiCustomModel, config.grokModel)
        return config.copy(grokModel = model)
    }

    internal fun KeyboardSession.aiTypeImpl() = if(aiTask=="custom") "custom:${aiCustomPresetId.orEmpty()}" else aiTask

    internal fun KeyboardSession.aiScopeImpl(config: io.secure.keyboard.data.ApiConfiguration): String =
        io.secure.keyboard.ai.AiConversation.scope(io.secure.keyboard.ai.AiWriting.contextKey(config),
            aiType(),
            io.secure.keyboard.ai.AiPromptStore(this).system(aiTask,aiTone,aiCustom) + "\u0000reasoning:" + if(aiTask=="custom") aiCustomReasoning else "low")

    internal fun KeyboardSession.resetAiConversationImpl() {
        aiConversation=null
        aiConversationStore.clear()
        assistantSourceTitle?.text="原文"
    }

    internal fun KeyboardSession.restoreAiConversationImpl(): Long = restoreAiConversation(null)

    internal fun KeyboardSession.restoreAiConversationImpl(expectedScope: String?): Long {
        val (saved, revision)=aiConversationStore.snapshot()
        if (saved==null) { aiConversation=null; assistantSourceTitle?.text="原文"; return revision }
        val scope=expectedScope ?: runCatching { aiScope(effectiveAiConfiguration()) }.getOrNull()
        if(scope==null || !saved.valid(System.currentTimeMillis(),scope)) {
            resetAiConversation()
            return aiConversationStore.revision()
        }
        aiConversation=saved; assistantSourceTitle?.text="续聊 ${saved.id.take(4)}"
        return revision
    }

    internal fun KeyboardSession.requestAiImpl() {
        if(!aiMode || !translation.enabled || !active || secret || translation.busy) return
        if(code.isNotEmpty()) { toast("请先确认候选词再生成"); return }
        val config=try { effectiveAiConfiguration() }
            catch(_:Exception) { toast("无法读取API配置，请重新保存"); return }
        if(config.grokKey.isBlank() || config.grokModel.isBlank()) { toast("请在 AI 设置中填写密钥和模型 ID"); return }
        val instruction=try { io.secure.keyboard.ai.AiPromptStore(this).system(aiTask,aiTone,aiCustom) }
            catch(e:Exception) { toast(e.message ?: "请检查指令"); return }
        if(translation.text.isBlank()) { toast("请输入原文"); return }
        val scope=try { aiScope(config) } catch(_:Exception) { toast("请检查 API 配置"); return }
        val storeRevision=restoreAiConversation(scope)
        val parent=aiConversation
        val previousResponseId=parent?.id
        val customRequest=aiTask=="custom"
        val reasoningEffort=if(customRequest) aiCustomReasoning else "low"
        val autoApplySelectionEpoch=aiSelectionEpoch
        val draftEpoch=aiDraftEpoch
        val text=translation.text
        val oldResult=translation.result
        val token=translation.begin(); val editor=voiceEditorGeneration
        val connection=currentInputConnection
        stopAiRequest(); val call=aiCallFactory(); aiCall=call; updateAiPreview()
        val preview = io.secure.keyboard.ai.LatestPreview({ main.postDelayed(it, 40L) }, { main.removeCallbacks(it) }) { partial ->
            if (!destroyed && active && aiMode && !secret && editor==voiceEditorGeneration &&
                connection===currentInputConnection && aiCall===call && aiConversationStore.revision()==storeRevision) {
                translation.partial(token, partial); updateAiPreview()
            }
        }
        aiPreviewDelivery = preview
        val task = Runnable {
            var failed=false
            var expired=false
            val result=try { call.execute(config,text,if(previousResponseId==null) instruction else "", preview::offer, custom = customRequest, previousResponseId = previousResponseId, reasoningEffort = reasoningEffort, systemOverride = instruction) }
                catch(_:io.secure.keyboard.ai.AiWriting.ConversationExpired) { failed=true; expired=true; "会话已失效，请再次发送以开始新对话" }
                catch(e:IllegalArgumentException) { failed=true; e.message ?: "配置错误" }
                catch(e:IllegalStateException) { failed=true; e.message ?: "生成失败" }
                catch(_:Exception) { failed=true; "连接失败或超时，请重试" }
            main.post {
                if(destroyed || !active || !aiMode || secret || editor!=voiceEditorGeneration || connection!==currentInputConnection || aiCall!==call) return@post
                preview.close(); aiPreviewDelivery=null
                aiCall=null; aiTaskRunnable=null
                if (failed) {
                    if(expired) resetAiConversation()
                    if(oldResult.isNotEmpty()) translation.complete(token,oldResult) else translation.complete(token,result,true)
                    updateAiPreview(); toast(result)
                    return@post
                }
                if(aiConversationStore.revision()!=storeRevision) {
                    aiConversation=null
                    translation.complete(token,"会话设置已变化，请再次发送",true); updateAiPreview(); return@post
                }
                translation.complete(token,result)
                if (translation.result == result) {
                    val id=call.responseId
                    if(id!=null) {
                        val saved=io.secure.keyboard.ai.AiConversation(id,scope,System.currentTimeMillis(),aiType())
                        runCatching { aiConversationStore.save(saved,storeRevision) }
                            .onSuccess { aiConversation=saved }
                            .onFailure { resetAiConversation(); toast("会话保存失败，下次将开始新对话") }
                    }
                    // Even editing and restoring the same text counts as a newer draft.
                    if(aiDraftEpoch==draftEpoch && translation.text==text && code.isEmpty()) {
                        translation.editDraft(""); syncAssistantText(translationTextView,""); sourceCursor=0
                    }
                    aiResults.add(result)
                    if (aiResults.size > 8) aiResults.removeAt(0)
                    aiResultIndex=aiResults.lastIndex
                    updateAiPreview(); refreshCandidates()
                    if (settings.aiAutoApply && autoApplySelectionEpoch == aiSelectionEpoch) applyAiResult()
                }
            }
        }
        aiTaskRunnable = task
        aiWorker.execute(task)
    }

    /** UI history is ephemeral; the encrypted continuation survives ordinary exits. */
    internal fun KeyboardSession.clearAiConversationImpl() {
        cancelAiCommandVoice()
        aiConversation=null
        aiResults.clear(); aiResultIndex=-1; aiResultNavigation=null
    }

    internal fun KeyboardSession.selectAiResultImpl(offset: Int) {
        if (translation.busy || aiResults.isEmpty()) return
        aiResultIndex=(aiResultIndex+offset).coerceIn(0,aiResults.lastIndex)
        val token=translation.begin()
        translation.complete(token,aiResults[aiResultIndex]); updateAiPreview()
    }

    internal fun KeyboardSession.cancelAiCommandVoiceImpl() {
        if(assistantHeldVoice) spaceHoldTouch.revoke()
        assistantHeldVoice=false
        aiCommandEpoch++
        assistantVoicePanel?.let { (it.parent as? ViewGroup)?.removeView(it) }; assistantVoicePanel=null
        val oldAiVoice=aiCommandVoice; aiCommandVoice=null
        val oldOfflineVoice=inlineOfflineVoice; inlineOfflineVoice=null
        // Release can synchronously notify listeners; detach ownership first.
        oldAiVoice?.release(); oldOfflineVoice?.release()
    }

    internal fun KeyboardSession.toggleAiCommandVoiceImpl() = startAssistantVoice(false)

    internal fun KeyboardSession.startAssistantVoiceImpl(held: Boolean) {
        if(aiCommandVoice!=null || inlineOfflineVoice!=null) { aiCommandVoice?.stop(); inlineOfflineVoice?.stop(); toast("识别中…"); return }
        val target=activeAssistantEditor() ?: return
        if(!active || secret || translation.busy) return
        val usingAi = settings.voiceMode == "ai"
        if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
            toast("请先在语音设置中允许麦克风权限"); return
        }
        val overlay=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; setBackgroundColor(bg)
            setPadding(dp(14),dp(8),dp(14),dp(8)); isClickable=true
        }
        val status=label("准备聆听",15f,fg)
        val waveform=io.secure.keyboard.ui.VoiceWaveform(this,accent)
        val preview=label("",18f).apply { gravity=Gravity.CENTER; maxLines=3; ellipsize=android.text.TextUtils.TruncateAt.END }
        overlay.addView(status,LinearLayout.LayoutParams(-1,dp(28)))
        overlay.addView(waveform,LinearLayout.LayoutParams(-1,0,1f))
        overlay.addView(preview,LinearLayout.LayoutParams(-1,dp(50)))
        val actions=LinearLayout(this)
        overlay.addView(actions,LinearLayout.LayoutParams(-1,dp(44)))
        if(usingAi && !held) actions.addView(button("识别语音") { aiCommandVoice?.stop() },LinearLayout.LayoutParams(0,-1,1f).apply { rightMargin=dp(10) })
        actions.addView(button("取消") { cancelAiCommandVoice() },LinearLayout.LayoutParams(0,-1,1f))
        assistantVoicePanel=overlay
        body.addView(overlay,FrameLayout.LayoutParams(-1,-1))
        val epoch=++aiCommandEpoch; val editor=voiceEditorGeneration; val connection=currentInputConnection
        val start=target.selectionStart; val end=target.selectionEnd; val original=target.text.toString()
        fun valid()=epoch==aiCommandEpoch && active && !secret && translation.enabled && !destroyed &&
            editor==voiceEditorGeneration && connection===currentInputConnection && target===activeAssistantEditor() &&
            target.isAttachedToWindow && target.selectionStart==start && target.selectionEnd==end && target.text.toString()==original
        val offlineSegments=StringBuilder()
        var lastSegmentId = Long.MIN_VALUE
        fun finish(text: String) {
            if (!valid()) { cancelAiCommandVoice(); toast("输入位置已改变，语音已停止"); return }
            val capacity = 5000 - (target.length() - kotlin.math.abs(end - start))
            cancelAiCommandVoice()
            when {
                text.isBlank() -> toast("未识别到有效文字，请重试")
                text.length > capacity -> toast("语音文字超出原文容量，请缩短录音后重试")
                else -> commitInputText(text)
            }
        }
        val listener=object: OfflineVoiceSession.Listener {
            override fun onSegment(segmentId:Long,text:String) {
                if (!usingAi && valid() && segmentId > lastSegmentId) {
                    lastSegmentId = segmentId
                    if (offlineSegments.length + text.length > 5000) {
                        cancelAiCommandVoice(); toast("语音文字超出原文容量，请缩短录音后重试"); return
                    }
                    offlineSegments.append(text)
                    // Tap-mode offline input keeps its endpoint auto-commit behavior.
                    // Held input must wait for release/finalization and retain every segment.
                    if (!held) finish(offlineSegments.toString())
                }
            }
            override fun onLevel(level:Float) { if(valid()) waveform.setLevel(level) }
            override fun onState(state:OfflineVoiceSession.State) {
                if(epoch!=aiCommandEpoch) return
                if(!valid()) { cancelAiCommandVoice(); toast("输入位置已改变，语音已停止"); return }
                if (state == OfflineVoiceSession.State.IDLE) {
                    finish(if (usingAi) "" else offlineSegments.toString()); return
                }
                status.text=when(state) {
                    OfflineVoiceSession.State.LISTENING -> if (held) "按住说话，松手识别" else "聆听中"
                    OfflineVoiceSession.State.PROCESSING -> "识别中"
                    OfflineVoiceSession.State.LOADING -> "准备聆听"
                    else -> "识别完成"
                }
            }
            override fun onPreview(text:String,isFinal:Boolean) {
                if(epoch!=aiCommandEpoch) return
                if(valid()) preview.text=text
                if(!isFinal) return
                finish(if (usingAi) text else offlineSegments.toString().ifEmpty { text })
            }
            override fun onError(message:String) { if(epoch==aiCommandEpoch) { cancelAiCommandVoice(); toast(message) } }
        }
        if(usingAi) {
            val config=try { io.secure.keyboard.data.ApiConfigurationStore(this).load() } catch(_:Exception) { cancelAiCommandVoice(); toast("请检查 AI 语音配置"); return }
            aiCommandVoice=io.secure.keyboard.voice.AiVoiceSession(this,config,listener,translateToEnglish = !chinese && settings.voiceAutoTranslate)
            aiCommandVoice?.begin()
        } else {
            inlineOfflineVoice=OfflineVoiceSession(this,listener)
            inlineOfflineVoice?.begin()
        }
    }

    internal fun KeyboardSession.applyAiResultImpl() {
        if(aiApplying || !aiMode || !active || secret || translation.result.isEmpty() || code.isNotEmpty()) return
        val connection=currentInputConnection ?: return
        val editor=voiceEditorGeneration; val epoch=aiSelectionEpoch; val revision=translation.revision; val result=translation.result
        val replace=aiSelectionTarget.text!=null
        aiApplying=true
        worker.execute {
            // Verify both the live selection text and selection epoch before touching the host.
            var readSucceeded=true
            val selected=try { connection.getSelectedText(0)?.toString() } catch(_:Exception) { readSucceeded=false; null }
            main.post {
                aiApplying=false
                if(destroyed || !active || !aiMode || secret || editor!=voiceEditorGeneration || connection!==currentInputConnection || revision!=translation.revision) return@post
                if(!readSucceeded || epoch!=aiSelectionEpoch ||
                    (replace && !aiSelectionTarget.matches(selected,epoch)) || (!replace && !selected.isNullOrEmpty())) {
                    aiSelectionTarget.revoke(); updateAiPreview(); toast("选区已变化，请取消选区后插入，或重新打开 AI 助手"); return@post
                }
                if(connection.commitText(outputText(result),1)) {
                    aiSelectionTarget.revoke(); updateAiPreview(); refreshCandidates()
                } else toast("输入框暂时无法接收文字，请重试")
            }
        }
    }

    internal fun KeyboardSession.saveTranslationLanguagesImpl() {
        settings.translationSource = translation.source
        settings.translationTarget = translation.target
    }

    internal fun KeyboardSession.fillTranslationFromSelectionImpl() {
        val connection = currentInputConnection ?: return
        val editor = voiceEditorGeneration
        val revision = translation.revision
        val draftEpoch = aiDraftEpoch
        val selectionEpoch = aiSelectionEpoch
        // Host text queries can require the UI thread (notably WebView); never block it here.
        worker.execute {
            if (destroyed) return@execute
            val selected = runCatching { connection.getSelectedText(0)?.toString() }.getOrNull()
            main.post {
                if (destroyed || !active || secret || !translation.enabled ||
                    editor != voiceEditorGeneration || connection !== currentInputConnection ||
                    revision != translation.revision || draftEpoch != aiDraftEpoch || selected.isNullOrEmpty()) return@post
                if (selectionEpoch != aiSelectionEpoch) return@post
                if (!translation.edit(selected)) toast("选中文字超过 5000 字符，请缩小选择范围")
                else if (aiMode) aiSelectionTarget.capture(selected, selectionEpoch)
                updateTranslationPreview()
            }
        }
    }

    internal fun KeyboardSession.updateTranslationPreviewImpl() {
        if (aiMode) { updateAiPreview(); return }
        assistantResultTitle?.text = if (translation.busy) "翻译中" else "译文"
        syncAssistantText(translationTextView, translation.text)
        translationTextView?.setTextColor(if (translation.text.isEmpty()) muted else fg)
        translationResultView?.text = translation.result.ifEmpty {
            if (translation.status == "输入原文后点击翻译") "" else translation.status
        }
        translationResultView?.setTextColor(if (translation.result.isEmpty()) muted else fg)
        translationAction?.apply {
            text = if (translation.busy) "翻译中…" else "翻译"
            isEnabled = !translation.busy && translation.text.isNotBlank()
            alpha = if (isEnabled) 1f else .45f
        }
        translationInsert?.apply {
            isEnabled = translation.result.isNotEmpty() && code.isEmpty()
            alpha = if (isEnabled) 1f else .4f
        }
    }

    internal fun KeyboardSession.renderTranslationBarImpl(column: LinearLayout) {
        if (aiMode) { renderAiBar(column); return }
        val area = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(12), dp(4), 0)
        }
        val extra = translationExtraHeightPx()
        column.addView(area, LinearLayout.LayoutParams(-1, extra))
        val controlHeight = minOf(dp(32), extra / 4)
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        area.addView(row, LinearLayout.LayoutParams(-1, controlHeight))
        fun languageChip(source: Boolean): View {
            val selected = if (source) translation.source else translation.target
            return LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; background = shape(keyBg, 10)
                setPadding(dp(10),0,dp(6),0)
                addView(label(translationLanguages[selected] ?: selected, 12f).apply {
                    setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0,-1,1f))
                addView(ToolIcon(this@renderTranslationBarImpl,"hide",muted,artworkSizeDp=14f),LinearLayout.LayoutParams(dp(18),-1))
                contentDescription = (if (source) "源语言：" else "目标语言：") + (translationLanguages[selected] ?: selected)
                setOnClickListener {
                    PopupMenu(this@renderTranslationBarImpl, this).apply {
                        translationLanguages.filter { source || it.key != "auto" }.forEach { (id, name) ->
                            menu.add(name).setOnMenuItemClickListener {
                                if (source) translation.source = id else translation.target = id
                                saveTranslationLanguages()
                                translation.invalidate(); renderBody(); true
                            }
                        }
                        show()
                    }
                }
            }
        }
        row.addView(languageChip(true), LinearLayout.LayoutParams(0,-1,1f))
        val swap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(ToolIcon(this@renderTranslationBarImpl,"arrow_right",muted,artworkSizeDp=13f),LinearLayout.LayoutParams(dp(18),dp(10)))
            addView(ToolIcon(this@renderTranslationBarImpl,"arrow_left",muted,artworkSizeDp=13f),LinearLayout.LayoutParams(dp(18),dp(10)))
            contentDescription = "交换语言"; isFocusable = true
            setOnClickListener {
                if (translation.source == "auto") toast("请先选择明确的源语言")
                else {
                    val old = translation.source; translation.source = translation.target; translation.target = old
                    saveTranslationLanguages()
                    translation.invalidate(); renderBody()
                }
            }
        }
        row.addView(swap, LinearLayout.LayoutParams(dp(30),-1))
        row.addView(languageChip(false), LinearLayout.LayoutParams(0,-1,1f))
        val cards = LinearLayout(this)
        area.addView(cards, LinearLayout.LayoutParams(-1,0,1f).apply { topMargin=dp(6) })
        fun card(title: String, source: Boolean): View {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; background = shape(keyBg, 10)
                setPadding(dp(if(source) 6 else 10),dp(6),dp(if(source) 6 else 10),dp(6))
            }
            val heading=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
            val titleView=label(title,10f,muted).apply {
                gravity=Gravity.START or Gravity.CENTER_VERTICAL; setSingleLine(true)
                if(source) setAutoSizeTextTypeUniformWithConfiguration(6,10,1,android.util.TypedValue.COMPLEX_UNIT_SP)
            }
            heading.addView(titleView,LinearLayout.LayoutParams(0,-1,1f))
            if (source) { assistantSourceTitle=titleView; heading.addView(icon("clear","清空原文") { clearAssistantSource() },LinearLayout.LayoutParams(dp(24),-1)) }
            else assistantResultTitle=titleView
            card.addView(heading,LinearLayout.LayoutParams(-1,dp(24)))
            val text: TextView=if(source) assistantEditor() else label("",14f).apply { gravity=Gravity.START or Gravity.TOP }
            if(source) card.addView(text,LinearLayout.LayoutParams(-1,0,1f))
            else card.addView(ScrollView(this).apply { isFillViewport=true; isVerticalScrollBarEnabled=false; isHorizontalScrollBarEnabled=false; addView(text) },LinearLayout.LayoutParams(-1,0,1f))
            if (source) {
                translationTextView=text; text.contentDescription="翻译原文"
                bindSourcePaste(heading, titleView, text)
            } else { translationResultView=text; bindResultCopy(card, text) }
            return card
        }
        addAssistantCards(cards, card("原文",true), card("译文",false))

        updateTranslationPreview()
    }

    internal fun KeyboardSession.requestTranslationImpl() {
        if (aiMode) { requestAi(); return }
        if (!translation.enabled || !active || secret || translation.busy) return
        if (code.isNotEmpty()) { toast("请先选择候选词，再翻译"); return }
        if (translation.text.isBlank()) { toast("请输入要翻译的文字"); return }
        val config = try { io.secure.keyboard.data.ApiConfigurationStore(this).load() }
            catch (_: Exception) { toast("无法读取 API 配置，请重新保存"); return }
        if (config.googleKey.isBlank()) { toast("请先在接口 API 配置中保存 Google 翻译密钥"); return }
        val text = translation.text; val source = translation.source; val target = translation.target
        val token = translation.begin(); val editor = voiceEditorGeneration; val connection = currentInputConnection
        val call = io.secure.keyboard.translation.GoogleTranslation.Call()
        translationCall = call
        updateTranslationPreview()
        val task = Runnable {
            var failed = false
            val result = try { call.execute(config, text, source, target) }
                catch (e: IllegalArgumentException) { failed = true; e.message ?: "翻译配置错误" }
                catch (e: IllegalStateException) { failed = true; e.message ?: "翻译失败" }
                catch (_: Exception) { failed = true; "网络连接失败，请稍后重试" }
            main.post {
                if (translationCall !== call || !active || destroyed || secret || editor != voiceEditorGeneration || connection !== currentInputConnection) return@post
                translationCall = null; translationTask = null
                translation.complete(token, result, failed); updateTranslationPreview()
            }
        }
        translationTask = task
        translationWorker.execute(task)
    }

}
