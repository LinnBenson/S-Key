package io.secure.keyboard.ime

import android.content.*
import android.os.*
import android.view.*
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.*
import kotlin.math.*
import io.secure.keyboard.voice.ModelRepository
import io.secure.keyboard.voice.OfflineVoiceSession
import io.secure.keyboard.voice.VoiceCommitGuard

import io.secure.keyboard.*

/** Voice capture coordination and editor validation. */
internal object VoiceController {
    private const val BUBBLE_HEIGHT_DP = 36

    internal fun KeyboardSession.renderVoiceImpl() {
        val col =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(8), dp(14), dp(8))
            }
        // Measure in logical panel coordinates, then fit the entire panel inside the
        // body allocated below the toolbar and above the floating resize handle.
        val hasShortcuts = heldVoice && voiceChoices.isNotEmpty()
        val viewport = object : FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val w = MeasureSpec.getSize(widthMeasureSpec)
                val h = MeasureSpec.getSize(heightMeasureSpec)
                val minHeight = dp(16 + 28 + 72 + 12 + 44 + if (hasShortcuts) BUBBLE_HEIGHT_DP else 0)
                val minWidth = dp(if (hasShortcuts) 28 + voiceChoices.size * 58 + (voiceChoices.size - 1) * 12 else 180)
                val scale = minOf(1f, h.toFloat() / minHeight, w.toFloat() / minWidth)
                setMeasuredDimension(w, h)
                if (scale <= 0f) return
                col.pivotX = 0f
                col.pivotY = 0f
                col.scaleX = scale
                col.scaleY = scale
                col.measure(MeasureSpec.makeMeasureSpec((w / scale).toInt(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec((h / scale).toInt(), MeasureSpec.EXACTLY))
            }
        }
        viewport.addView(col, FrameLayout.LayoutParams(-1, -1, Gravity.TOP or Gravity.LEFT))
        body.addView(viewport, FrameLayout.LayoutParams(-1, -1))
        voiceStatus = label("普通话 · 离线语音", 15f, fg)
        col.addView(voiceStatus, LinearLayout.LayoutParams(-1, dp(28)))
        voiceWaveform = io.secure.keyboard.ui.VoiceWaveform(this, accent)
        col.addView(voiceWaveform, LinearLayout.LayoutParams(-1, 0, 1f))
        voiceBubbles.clear()
        if (heldVoice && voiceChoices.isNotEmpty()) {
            val row = LinearLayout(this)
            col.addView(row, LinearLayout.LayoutParams(-1, dp(BUBBLE_HEIGHT_DP)))
            voiceChoices.forEachIndexed { index, choice ->
                val bubble = label(choice.title, 14f, fg).apply {
                    gravity = Gravity.CENTER
                    setSingleLine()
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    background = shape(keyBg, BUBBLE_HEIGHT_DP / 2)
                }
                voiceBubbles[choice] = bubble
                row.addView(bubble, LinearLayout.LayoutParams(0, -1, 1f).apply {
                    marginStart = dp(if (index == 0) 2 else 12)
                    if (index == voiceChoices.lastIndex) marginEnd = dp(2)
                })
            }
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        col.addView(actions, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(12) })
        if(settings.voiceMode=="ai" && !heldVoice) {
            actions.addView(
                button("识别语音") {
                    if (!voiceStarting) when (voiceState) {
                        OfflineVoiceSession.State.LISTENING -> aiVoice?.stop()
                        OfflineVoiceSession.State.IDLE -> if (voiceGuard == null) startVoice()
                        else -> Unit
                    }
                }.apply {
                    tag = "voice-submit"
                    background = shape(keyBg, 22)
                },
                LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = dp(10) },
            )
        }
        actions.addView(
            voiceCancelButton {
                cancelTransient()
                panel = "keys"
                renderHeader()
                renderBody()
            },
            LinearLayout.LayoutParams(dp(44), dp(44)),
        )
        updateVoiceLabels()
    }

    internal fun KeyboardSession.updateShortcutHover(x: Float, y: Float) {
        if (!heldVoice || heldVoiceFinishRequested || voiceBubbles.isEmpty()) return
        val bounds = android.graphics.Rect()
        val rootLocation = IntArray(2)
        body.rootView.getLocationOnScreen(rootLocation)
        val hit = voiceBubbles.entries.firstOrNull { (_, bubble) ->
            bubble.getGlobalVisibleRect(bounds) &&
                bounds.contains(floor(x - rootLocation[0]).toInt(), floor(y - rootLocation[1]).toInt())
        }?.key
        if (voiceChoice == hit) return
        voiceChoice = hit
        voiceBubbles.forEach { (choice, bubble) ->
            bubble.background = shape(if (choice == hit) accent else keyBg, BUBBLE_HEIGHT_DP / 2)
            bubble.setTextColor(if (choice == hit) enterText else fg)
            bubble.isSelected = choice == hit
        }
    }

    private fun KeyboardSession.processShortcut(choice: io.secure.keyboard.ai.VoiceAiChoice, text: String) {
        val connection = voiceConnection ?: return
        val request = voiceRequest
        val editor = voiceEditorGeneration
        val call = aiCallFactory()
        voiceShortcutCall = call
        updateVoiceLabels()
        aiWorker.execute {
            val result = runCatching {
                val preset = choice.preset
                val original = io.secure.keyboard.data.ApiConfigurationStore(this).load()
                val config = if (preset != null && io.secure.keyboard.data.AiProviders.valid(preset.provider))
                    original.copy(aiProvider = preset.provider) else original
                val prompts = io.secure.keyboard.ai.AiPromptStore(this)
                val model = prompts.resolveModel(choice.task, choice.tone, preset?.modelId.orEmpty(), config.chatModel, config.aiProvider)
                val instruction = prompts.system(choice.task, choice.tone, preset?.prompt.orEmpty())
                call.execute(config.withChatModel(model), text, instruction, custom = preset != null,
                    reasoningEffort = preset?.reasoningEffort ?: "low", systemOverride = instruction,
                    webSearch = preset?.webSearch ?: false, xSearch = preset?.xSearch ?: false)
            }
            if (!voiceRequestIsCurrent(request, editor)) return@execute
            val output = result.getOrNull()
            main.post {
                if (!voiceTargetIsCurrent(request, editor, connection) || voiceShortcutCall !== call) return@post
                voiceShortcutCall = null
                if (output.isNullOrBlank()) {
                    failVoice(result.exceptionOrNull()?.localizedMessage ?: "AI 未返回文字")
                } else {
                    voiceSegments.addLast(heldSegmentId to output)
                    drainVoiceSegments()
                }
            }
        }
    }

    internal fun KeyboardSession.updateVoiceLabelsImpl() {
        if (voiceState != OfflineVoiceSession.State.LISTENING) voiceWaveform?.reset()
        if (bodyInitialized) body.findViewWithTag<TextView>("voice-submit")?.apply {
            text = if (voiceState == OfflineVoiceSession.State.IDLE) "重新录音" else "识别语音"
            isEnabled = !voiceStarting && (voiceState == OfflineVoiceSession.State.LISTENING ||
                (voiceState == OfflineVoiceSession.State.IDLE && voiceGuard == null))
        }
        voiceStatus?.text =
            if (voiceShortcutCall != null) "内容正由 ${voiceChoice?.title} 处理中..."
            else if (heldVoiceWaitingKeys) "正在完成先前输入 · 松手取消"
            else when (voiceState) {
                OfflineVoiceSession.State.LOADING -> if(usingAiVoice) "正在准备 AI 语音" else "正在准备离线语音"
                OfflineVoiceSession.State.LISTENING ->
                    if(usingAiVoice) { if(heldVoice) "聆听中 · 松手识别" else "聆听中" }
                    else if (heldVoice) "正在聆听 · 松手完成" else "正在聆听"
                OfflineVoiceSession.State.PROCESSING -> if(usingAiVoice) aiVoice?.recognitionStatus ?: "正在识别…" else "正在完成最后一段"
                else -> if(settings.voiceMode=="ai") { if (aiVoiceRoundCommitted) "识别完成" else "等待聆听" } else "普通话 · 离线语音"
            }
    }

    /** Invalidates queued recognition and editor checks before stopping the microphone. */
    internal fun KeyboardSession.cancelVoiceImpl(preserveSpaceHold: Boolean = false) {
        voiceShortcutCall?.cancel()
        voiceShortcutCall = null
        voiceChoices = emptyList()
        voiceChoice = null
        voiceBubbles.clear()
        heldTranscript.clear()
        heldSegmentId = -1L
        aiVoiceRestart?.let(main::removeCallbacks); aiVoiceRestart = null
        aiVoiceRoundCommitted = false
        val closeHeldPanel = heldVoice
        if (!preserveSpaceHold) spaceHoldTouch.revoke()
        heldVoice = false
        heldVoiceWaitingKeys = false
        heldVoiceFinishRequested = false
        heldVoiceSessionFinished = false
        voiceRequest++
        voiceSelectionChecks.clear()
        voiceStarting = false
        voiceGuard?.cancel()
        voiceGuard = null
        voiceConnection = null
        voiceSegments.clear()
        voiceValidationPending = false
        aiVoice?.release(); aiVoice=null
        voice?.cancel()
        voiceState = OfflineVoiceSession.State.IDLE
        if (closeHeldPanel && panel == "voice") {
            panel = "keys"
            voiceStatus = null
            voiceWaveform = null
            if (active && bodyInitialized) {
                renderHeader()
                renderBody()
            }
        }
    }

    internal fun KeyboardSession.beginHeldVoiceImpl(pointerId: Int, downVersion: Long) {
        // Never clearPending here: earlier released taps may be queued behind a dictionary query.
        cancelVoice()
        if (!spaceHoldTouch.claim(pointerId, downVersion)) return
        keyFeedback()
        heldVoice = true
        heldVoiceWaitingKeys = true
        heldVoiceFinishRequested = false
        heldVoiceSessionFinished = false
        panel = "voice"
        renderHeader()
        renderBody()
        startHeldVoiceWhenKeysSettle()
    }

    internal fun KeyboardSession.startHeldVoiceWhenKeysSettleImpl() {
        if (!heldVoice || !heldVoiceWaitingKeys || heldVoiceFinishRequested ||
            !active || panel != "voice" || pendingConfirmation != null || englishConfirmation != null || queueReplayPosted || queuedKeys.isNotEmpty()) return
        heldVoiceWaitingKeys = false
        startVoice()
    }

    /** A normal UP flushes the recognizer; a real CANCEL/editor change uses cancelVoice instead. */
    internal fun KeyboardSession.finishHeldVoiceImpl() {
        if (!heldVoice) return
        heldVoiceFinishRequested = true
        if (heldVoiceWaitingKeys || voiceStarting || voiceGuard == null) {
            // No microphone session has begun. Revoke async cursor checks/deferred starts, so
            // lifting during preparation can never cause surprise recording a moment later.
            cancelVoice()
            return
        }
        if(usingAiVoice) aiVoice?.stop(skipAutoTranslation = voiceChoice != null) else voice?.stop()
        completeHeldVoiceIfDrained()
    }

    internal fun KeyboardSession.completeHeldVoiceIfDrainedImpl() {
        if (heldVoice && heldVoiceFinishRequested && heldVoiceSessionFinished &&
            !voiceStarting && !voiceValidationPending && voiceSegments.isEmpty()) {
            if (voiceShortcutCall != null) return
            if (heldTranscript.isNotEmpty()) {
                val text = heldTranscript.toString()
                heldTranscript.clear()
                val choice = voiceChoice
                if (choice == null) {
                    voiceSegments.addLast(heldSegmentId to text)
                    drainVoiceSegments()
                } else processShortcut(choice, text)
                return
            }
            // Final callbacks and guarded commits finished. Only now may panel==keys invalidate
            // the voice target. Already-inserted endpoint segments are never undone.
            cancelVoice()
        }
    }

    /** Restart only after a successful host commit AND capture/network cleanup have finished. */
    internal fun KeyboardSession.restartAiVoiceIfDrainedImpl() {
        if (!usingAiVoice || heldVoice || !aiVoiceRoundCommitted || aiVoiceRestart != null ||
            voiceState != OfflineVoiceSession.State.IDLE || voiceStarting || voiceValidationPending ||
            voiceSegments.isNotEmpty() || !active || panel != "voice" || secret) return
        val guard = voiceGuard ?: return
        val connection = voiceConnection ?: return
        val request = voiceRequest
        val editor = voiceEditorGeneration
        aiVoiceRestart = Runnable {
            aiVoiceRestart = null
            if (!voiceTargetIsCurrent(request, editor, connection) || voiceGuard !== guard ||
                !guard.active || heldVoice || !aiVoiceRoundCommitted) return@Runnable
            cancelVoice()
            startVoice()
        }.also { main.postDelayed(it, 500) }
    }

    internal fun KeyboardSession.stopVoiceForEditorChangeImpl() {
        cancelVoice()
        updateVoiceLabels()
    }

    /** Never call this synchronous host API from the IME/main thread (WebView can deadlock). */
    internal fun KeyboardSession.readVoiceSelectionImpl(connection: InputConnection): Pair<Int, Int>? =
        try {
            val extracted = connection.getExtractedText(
                ExtractedTextRequest().apply { hintMaxChars = 1; hintMaxLines = 1 }, 0
            )
            if (extracted == null || extracted.startOffset < 0 ||
                extracted.selectionStart < 0 || extracted.selectionEnd < 0) null
            else {
                val start = extracted.startOffset.toLong() + extracted.selectionStart
                val end = extracted.startOffset.toLong() + extracted.selectionEnd
                if (start > Int.MAX_VALUE || end > Int.MAX_VALUE) null
                else start.toInt() to end.toInt()
            }
        } catch (_: RuntimeException) { null }

    // Only volatile invalidation tokens are read by the worker. Full UI/editor guards stay on main.
    internal fun KeyboardSession.voiceRequestIsCurrentImpl(request: Long, editor: Int) =
        !destroyed && voiceRequest == request && voiceEditorGeneration == editor

    internal fun KeyboardSession.voiceTargetIsCurrentImpl(request: Long, editor: Int, connection: InputConnection) =
        !destroyed && active && panel == "voice" && voiceRequest == request &&
            voiceEditorGeneration == editor && currentInputConnection === connection

    internal fun KeyboardSession.startVoiceImpl() {
        if (!active || panel != "voice" || voiceStarting || voiceGuard != null) return
        usingAiVoice = settings.voiceMode == "ai"
        if ((!usingAiVoice && !ModelRepository(this).isInstalled()) ||
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val message = if(usingAiVoice) "请先允许麦克风权限" else "请先完成模型和麦克风设置，再点击语音"
            if (heldVoice) {
                cancelVoice()
                toast(message)
            }
            startActivity(
                Intent(this, VoiceSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        if(usingAiVoice) {
            val configured=runCatching { io.secure.keyboard.data.ApiConfigurationStore(this).load().resolvedVoice().voiceKey.isNotBlank() }.getOrDefault(false)
            if(!configured) {
                failVoice("请先在接口 API 配置中保存语音密钥")
                startActivity(Intent(this,SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("page","api"))
                return
            }
        }
        val connection = currentInputConnection ?: run {
            if (heldVoice) cancelVoice()
            return
        }
        val request = voiceRequest
        val editor = voiceEditorGeneration
        voiceStarting = true
        voiceState = OfflineVoiceSession.State.LOADING
        updateVoiceLabels()
        checkVoiceStart(request, editor, connection)
    }

    internal fun KeyboardSession.checkVoiceStartImpl(request: Long, editor: Int, connection: InputConnection) {
        val selected = if (heldVoice && !secret) settings.voiceAiChoices else emptySet()
        voiceSelectionChecks.submit(onRejected = {
            main.post {
                if (voiceTargetIsCurrent(request, editor, connection) && voiceStarting)
                    failVoice("输入框检查暂时不可用，请重试")
            }
        }) selection@{
            if (!voiceRequestIsCurrent(request, editor)) return@selection
            val choices = if (selected.isEmpty()) emptyList() else {
                val presets = if (selected.any { it.startsWith("custom:") }) runCatching { content.aiPresets() }.getOrDefault(emptyList()) else emptyList()
                io.secure.keyboard.ai.VoiceAiChoice.options(presets).filter { it.id in selected }.take(4)
            }
            val selection = readVoiceSelection(connection)
            if (!voiceRequestIsCurrent(request, editor)) return@selection
            main.post {
                if (!voiceTargetIsCurrent(request, editor, connection) || !voiceStarting) return@post
                voiceStarting = false
                if (selection == null || selection.first != selection.second) {
                    failVoice(if (selection == null) "此输入框无法安全确认光标位置"
                        else "请先将光标放到要输入的位置，再点击语音")
                    return@post
                }
                voiceGuard = VoiceCommitGuard(editor, selection.first)
                voiceConnection = connection
                if (choices.isNotEmpty()) {
                    voiceChoices = choices
                    renderBody(false)
                }
                if(usingAiVoice) {
                    val config=runCatching { io.secure.keyboard.data.ApiConfigurationStore(this).load() }.getOrNull()
                    if(config==null) { failVoice("无法读取语音 API 配置");return@post }
                    if (choices.isEmpty() && !chinese && settings.voiceAutoTranslate && config.googleKey.isBlank()) { failVoice("英文语音需要先配置 Google 翻译密钥"); return@post }
                    aiVoice?.release()
                    aiVoice=io.secure.keyboard.voice.AiVoiceSession(this,config,voiceListener(), translateToEnglish = !chinese && settings.voiceAutoTranslate)
                    aiVoice?.begin()
                } else {
                    if (voice == null) voice = createVoiceSession()
                    voice?.begin()
                }
            }
        }
    }

    internal fun KeyboardSession.createVoiceSessionImpl() = OfflineVoiceSession(this, voiceListener())

    internal fun KeyboardSession.voiceListenerImpl() = object : OfflineVoiceSession.Listener {
            override fun onLevel(level: Float) {
                if (voiceState == OfflineVoiceSession.State.LISTENING) voiceWaveform?.setLevel(level)
            }
            override fun onState(state: OfflineVoiceSession.State) {
                voiceState = state
                if (heldVoice && state == OfflineVoiceSession.State.IDLE &&
                    !heldVoiceWaitingKeys && !voiceStarting) heldVoiceSessionFinished = true
                updateVoiceLabels()
                completeHeldVoiceIfDrained()
                restartAiVoiceIfDrained()
            }

            override fun onCaptureTimeout() {
                cancelVoice()
                updateVoiceLabels()
            }

            override fun onSegment(segmentId: Long, text: String) {
                queueVoiceSegment(segmentId, text)
            }

            override fun onError(message: String) {
                failVoice(message)
            }
        }

    internal fun KeyboardSession.failVoiceImpl(message: String) {
        cancelVoice()
        toast(message)
        updateVoiceLabels()
    }

    /** Only finalized segments enter this bounded queue; partial previews never reach the host. */
    internal fun KeyboardSession.queueVoiceSegmentImpl(segmentId: Long, text: String) {
        if (voiceGuard?.active != true) return
        val commitText = text.trim()
        if (commitText.isEmpty()) return
        if (heldVoice && voiceChoices.isNotEmpty()) {
            if (segmentId <= heldSegmentId) return
            heldSegmentId = segmentId
            if (heldTranscript.length + commitText.length > 20000) {
                failVoice("语音内容过长，请分段录入")
                return
            }
            heldTranscript.append(commitText)
            return
        }
        if (voiceSegments.size >= 8) {
            failVoice("输入框响应较慢，语音已停止")
            return
        }
        // Queue the exact host text so the guard advances by its actual UTF-16 length.
        voiceSegments.addLast(segmentId to commitText)
        drainVoiceSegments()
    }

    internal fun KeyboardSession.drainVoiceSegmentsImpl() {
        if (voiceValidationPending) return
        if (voiceSegments.isEmpty()) {
            completeHeldVoiceIfDrained()
            restartAiVoiceIfDrained()
            return
        }
        val guard = voiceGuard ?: return
        val connection = voiceConnection ?: return
        val request = voiceRequest
        val editor = voiceEditorGeneration
        val (segmentId, text) = voiceSegments.removeFirst()
        voiceValidationPending = true
        // One current-session validation owns one removed segment until delivery. Later finals
        // remain in voiceSegments; they never enter the replaceable mailbox ahead of this one.
        voiceSelectionChecks.submit(onRejected = {
            main.post {
                if (voiceTargetIsCurrent(request, editor, connection) && voiceGuard === guard)
                    failVoice("输入框检查暂时不可用，语音已停止")
            }
        }) selection@{
            if (!voiceRequestIsCurrent(request, editor)) return@selection
            val selection = readVoiceSelection(connection)
            if (!voiceRequestIsCurrent(request, editor)) return@selection
            main.post {
                // The callback owns only its captured session, never a later editor/start.
                if (!voiceTargetIsCurrent(request, editor, connection) || voiceGuard !== guard)
                    return@post
                voiceValidationPending = false
                val convertedText = outputText(text)
                val decision = guard.reserve(
                    segmentId, convertedText.length, voiceEditorGeneration,
                    selection?.first ?: -1, selection?.second ?: -1,
                )
                when (decision) {
                    VoiceCommitGuard.Decision.STOP -> stopVoiceForEditorChange()
                    VoiceCommitGuard.Decision.IGNORE -> drainVoiceSegments()
                    VoiceCommitGuard.Decision.COMMIT -> {
                        val inserted = try { connection.commitText(convertedText, 1) }
                            catch (_: RuntimeException) { false }
                        if (!inserted) {
                            failVoice("输入框未接受文字，语音已停止")
                        } else {
                            if (usingAiVoice && !heldVoice) {
                                aiVoiceRoundCommitted = true
                                updateVoiceLabels()
                            }
                            drainVoiceSegments()
                        }
                    }
                }
            }
        }
    }
}

internal fun KeyboardSession.voiceCancelButton(action: () -> Unit) =
    icon("clear", "取消", action).apply {
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(keyBg)
        }
    }
