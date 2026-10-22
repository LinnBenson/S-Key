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
import io.secure.keyboard.voice.VoiceSegmentFormatter

import io.secure.keyboard.*

/** Voice capture coordination and editor validation. */
internal object VoiceController {
    internal fun KeyboardSession.renderVoiceImpl() {
        val col =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(8), dp(14), dp(8))
            }
        body.addView(col, FrameLayout.LayoutParams(-1, -1))
        voiceStatus = label("普通话 · 离线语音", 15f, fg)
        col.addView(voiceStatus, LinearLayout.LayoutParams(-1, dp(28)))
        voiceWaveform = io.secure.keyboard.ui.VoiceWaveform(this, accent)
        col.addView(voiceWaveform, LinearLayout.LayoutParams(-1, dp(88)))
        voiceText =
            label(voicePreview, 20f).apply {
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
        col.addView(
            ScrollView(this).apply {
                isFillViewport = true
                addView(voiceText, ViewGroup.LayoutParams(-1, -2))
            },
            LinearLayout.LayoutParams(-1, 0, 1f),
        )
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        col.addView(actions, LinearLayout.LayoutParams(-1, dp(44)))
        if(settings.voiceMode=="ai" && !heldVoice) {
            actions.addView(
                button("识别语音") { if (!voiceStarting && voiceState == OfflineVoiceSession.State.LISTENING) aiVoice?.stop() },
                LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = dp(10) },
            )
        }
        actions.addView(
            button("取消") {
                cancelTransient()
                panel = "keys"
                renderHeader()
                renderBody()
            },
            LinearLayout.LayoutParams(0, -1, 1f),
        )
        updateVoiceLabels()
    }

    internal fun KeyboardSession.updateVoiceLabelsImpl() {
        if (voiceState != OfflineVoiceSession.State.LISTENING) voiceWaveform?.reset()
        voiceStatus?.text =
            if (heldVoiceWaitingKeys) "正在完成先前输入 · 松手取消"
            else when (voiceState) {
                OfflineVoiceSession.State.LOADING -> if(usingAiVoice) "正在准备 AI 语音" else "正在准备离线语音"
                OfflineVoiceSession.State.LISTENING ->
                    if(usingAiVoice) { if(heldVoice) "聆听中 · 松手识别" else "聆听中" }
                    else if (heldVoice) "正在聆听 · 松手完成" else "正在聆听"
                OfflineVoiceSession.State.PROCESSING -> if(usingAiVoice) "正在识别…" else "正在完成最后一段"
                else -> if(settings.voiceMode=="ai") { if (aiVoiceRoundCommitted) "识别完成" else "等待聆听" } else "普通话 · 离线语音"
            }
    }

    /** Invalidates queued recognition and editor checks before stopping the microphone. */
    internal fun KeyboardSession.cancelVoiceImpl() {
        aiVoiceRestart?.let(main::removeCallbacks); aiVoiceRestart = null
        aiVoiceRoundCommitted = false
        val closeHeldPanel = heldVoice
        spaceHoldTouch.revoke()
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
            voicePreview = ""
            voiceText = null
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
        if(usingAiVoice) aiVoice?.stop() else voice?.stop()
        completeHeldVoiceIfDrained()
    }

    internal fun KeyboardSession.completeHeldVoiceIfDrainedImpl() {
        if (heldVoice && heldVoiceFinishRequested && heldVoiceSessionFinished &&
            !voiceStarting && !voiceValidationPending && voiceSegments.isEmpty()) {
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
        voicePreview = ""
        voiceText?.text = "输入位置已改变，语音已停止"
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
            voiceText?.text = message
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
            val configured=runCatching { io.secure.keyboard.data.ApiConfigurationStore(this).load().voiceKey.isNotBlank() }.getOrDefault(false)
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
        voicePreview = ""
        voiceText?.text = ""
        updateVoiceLabels()
        checkVoiceStart(request, editor, connection)
    }

    internal fun KeyboardSession.checkVoiceStartImpl(request: Long, editor: Int, connection: InputConnection) {
        voiceSelectionChecks.submit(onRejected = {
            main.post {
                if (voiceTargetIsCurrent(request, editor, connection) && voiceStarting)
                    failVoice("输入框检查暂时不可用，请重试")
            }
        }) selection@{
            if (!voiceRequestIsCurrent(request, editor)) return@selection
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
                if(usingAiVoice) {
                    val config=runCatching { io.secure.keyboard.data.ApiConfigurationStore(this).load() }.getOrNull()
                    if(config==null) { failVoice("无法读取语音 API 配置");return@post }
                    if (!chinese && settings.voiceAutoTranslate && config.googleKey.isBlank()) { failVoice("英文语音需要先配置 Google 翻译密钥"); return@post }
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

            override fun onPreview(text: String, isFinal: Boolean) {
                voicePreview = text
                voiceText?.text = text.ifEmpty {
                    if (isFinal) "本次语音已结束" else ""
                }
            }

            override fun onSegment(segmentId: Long, text: String) {
                queueVoiceSegment(segmentId, text)
            }

            override fun onError(message: String) {
                failVoice(message)
            }
        }

    internal fun KeyboardSession.failVoiceImpl(message: String) {
        val wasHeld = heldVoice
        cancelVoice()
        if (wasHeld) toast(message) else voiceText?.text = message
        updateVoiceLabels()
    }

    /** Only finalized segments enter this bounded queue; partial previews never reach the host. */
    internal fun KeyboardSession.queueVoiceSegmentImpl(segmentId: Long, text: String) {
        if (voiceGuard?.active != true) return
        val commitText = VoiceSegmentFormatter.formatFinalSegment(text)
        if (commitText.isEmpty()) return
        if (voiceSegments.size >= 8) {
            failVoice("输入框响应较慢，语音已停止")
            return
        }
        // Queue the exact host text so the guard advances by its formatted UTF-16 length.
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
                                voicePreview = ""
                                voiceText?.text = ""
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
