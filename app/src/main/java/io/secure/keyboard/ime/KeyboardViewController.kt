package io.secure.keyboard.ime

import android.content.*
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.*
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.*
import kotlin.math.*
import io.secure.keyboard.data.SettingsStore
import io.secure.keyboard.ui.KeyboardPressFeedback
import io.secure.keyboard.ui.FloatingResize
import io.secure.keyboard.ui.KeyboardHaptics
import io.secure.keyboard.ui.KeyboardFeedback
import io.secure.keyboard.ui.CandidateStrip
import io.secure.keyboard.ui.CandidateGrid
import io.secure.keyboard.ui.IndentedKeyRow
import io.secure.keyboard.ui.KeyContainer
import io.secure.keyboard.ui.KeyTouchRouter
import io.secure.keyboard.ui.SymbolKeyLayout
import io.secure.keyboard.ui.SymmetricBottomRow
import io.secure.keyboard.ui.PunctuationKeyLabel
import io.secure.keyboard.ui.PreeditCodeView
import io.secure.keyboard.ui.ToolIcon
import io.secure.keyboard.ui.styleSystemBars

import io.secure.keyboard.*
import io.secure.keyboard.ime.KeyboardSession.QueuedKey

/** Keyboard keys, header and layout presentation. */
internal object KeyboardViewController {
    internal fun KeyboardSession.requestExecutorImpl(threads: Int) = java.util.concurrent.ThreadPoolExecutor(
        threads, threads, 15L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(1),
        java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy()
    ).apply { allowCoreThreadTimeOut(true) }
    internal fun KeyboardSession.editorEntryImpl(info: EditorInfo?) = EditorEntryPolicy.resolve(
        inputType = info?.inputType ?: 0,
        imeOptions = info?.imeOptions ?: 0,
        rememberedChinese = settings.lastChineseLanguage,
    )

    internal fun KeyboardSession.applyEditorEntryImpl(entry: EditorEntryPolicy.Entry) {
        // Field defaults are temporary. Only the explicit globe action writes the preference.
        chinese = entry.chinese
        symbols = entry.symbols
        symbolPage = false
    }

    internal fun KeyboardSession.cancelTransientImpl() {
        assistantBackspaceTapAt = 0L
        cancelAiCommandVoice()
        quickEditSelection.reset()
        popup?.dismiss()
        popup = null
        cancelVoice()
        voicePreview = ""
        voiceText = null
        voiceStatus = null
        voiceWaveform = null
        main.removeCallbacksAndMessages("key")
    }

    internal fun KeyboardSession.dpImpl(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    internal fun KeyboardSession.pxToDpImpl(v: Float): Int {
        val value = v / resources.displayMetrics.density
        return if (value.isFinite()) value.roundToInt() else 0
    }

    internal fun KeyboardSession.toastImpl(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    internal fun KeyboardSession.paletteImpl() {
        dark =
            when (settings.theme) {
                "dark" -> true
                "light" -> false
                else ->
                    resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                        Configuration.UI_MODE_NIGHT_YES
            }
        val colors = settings.themeTokens(dark)
        bg = Color.parseColor(colors.keyboardBackground)
        keyBg = Color.parseColor(colors.normalKeyBackground)
        fg = Color.parseColor(colors.text)
        muted = Color.parseColor(colors.hint)
        accent = Color.parseColor(colors.accent)
        special = Color.parseColor(colors.specialKeyBackground)
        toolbarColor = Color.parseColor(colors.toolbarIcon)
        candidateColor = Color.parseColor(colors.candidate)
        selectedCandidateColor = Color.parseColor(colors.candidateSelected)
        preeditColor = Color.parseColor(colors.preedit)
        dividerColor = Color.parseColor(colors.divider)
        specialText = Color.parseColor(colors.specialKeyText)
        selectedKeyBg = Color.parseColor(colors.selectedKeyBackground)
        selectedKeyText = Color.parseColor(colors.selectedKeyText)
        enterText = Color.parseColor(colors.enterText)
        window?.window?.let { styleSystemBars(it, Color.luminance(bg) < .5f, bg) }
    }

    internal fun KeyboardSession.shapeImpl(color: Int, radius: Int = 12) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    internal fun KeyboardSession.labelImpl(text: String, size: Float = 16f, color: Int = fg) =
        TextView(this).apply {
            KeyboardFeedback.disableAutomatic(this)
            this.text = text
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }

    internal fun KeyboardSession.buttonImpl(text: String, action: () -> Unit): TextView =
        label(text).apply {
            background = shape(keyBg)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = true
            val feedback = installPressFeedback()
            setOnClickListener {
                feedback.click()
                action()
            }
        }

    internal fun KeyboardSession.toolIsSelectedImpl(name: String): Boolean = when(name) {
        "ai" -> translation.enabled && aiMode
        "translate" -> translation.enabled && !aiMode
        else -> name == panel
    }

    internal fun KeyboardSession.iconImpl(name: String, description: String, action: () -> Unit) =
        ToolIcon(
            this,
            if (name == "input_mode") "mode_" + settings.chineseInputMethod.id else if (name == "script") {
                if (settings.traditionalOutput) "script_traditional" else "script"
            } else if (name == "floating") {
                if (profile.floating) "floating_exit" else "floating_enter"
            } else name,
            if (toolIsSelected(name)) accent else toolbarColor,
        ).apply {
            KeyboardFeedback.disableAutomatic(this)
            isSelected = toolIsSelected(name)
            contentDescription = if (name == "input_mode") (if (settings.chineseInputMethod == io.secure.keyboard.engine.ChineseInputMode.WUBI) "五笔" else "拼音") + "，点击切换输入模式" else if (name == "script") (if (settings.traditionalOutput) "繁体输出，点击切换简体" else "简体输出，点击切换繁体") else description
            isClickable = true
            isFocusable = true
            val feedback = installPressFeedback()
            setOnClickListener {
                feedback.click()
                action()
            }
        }

    internal fun KeyboardSession.loadProfileImpl() {
        val c = resources.configuration
        landscape = c.orientation == Configuration.ORIENTATION_LANDSCAPE
        outer = c.smallestScreenWidthDp < 600
        profile = settings.profile(outer, landscape)
    }

    internal fun KeyboardSession.saveProfileImpl(p: SettingsStore.LayoutProfile) {
        settings.saveProfile(outer, landscape, p)
        profile = p
    }

    internal fun KeyboardSession.createViewImpl() {
        // A replacement root cannot inherit a finger from the old window/view hierarchy.
        if (heldVoice) cancelVoice()
        spaceHoldTouch.reset()
        palette()
        loadProfile()
        candidateGrid = null
        root =
            object : FrameLayout(this) {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    // Ignore late events from a root replaced during rotation/editor restart.
                    if (rootInitialized && this !== root) return true
                    if (spaceHoldTouch.observe(event)) return true
                    return super.dispatchTouchEvent(event)
                }
            }.apply {
                setBackgroundColor(if (profile.floating) Color.TRANSPARENT else bg)
                layoutDirection = View.LAYOUT_DIRECTION_LTR
            }
        box =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background =
                    if (profile.floating) shape(bg, 12)
                    else android.graphics.drawable.ColorDrawable(bg)
                clipToOutline = profile.floating
                foreground =
                    if (profile.floating) shape(Color.TRANSPARENT, 12).apply {
                        setStroke(dp(1), dividerColor)
                    } else null
                setPadding(dp(4), 0, dp(4), 0)
            }
        val usable = resources.displayMetrics.widthPixels
        val h = baseKeyboardHeightPx() + translationExtraHeightPx()
        val side = dp(profile.sideGapDp).coerceAtMost((usable - dp(220)).coerceAtLeast(0) / 2)
        val width =
            if (profile.floating)
                dp(profile.floatingWidthDp).coerceIn(dp(220).coerceAtMost(usable), usable)
            else usable - side * 2
        val left =
            if (profile.floating)
                dp(profile.floatingXDp).coerceIn(0, (usable - width).coerceAtLeast(0))
            else side
        val bottom =
            dp(if (profile.floating) profile.floatingYDp else profile.bottomGapDp)
                .coerceIn(0, if (profile.floating) FloatingResize.maxBottom(floatingScreenHeightPx(), h)
                    else resources.displayMetrics.heightPixels / 3)
        root.addView(
            box,
            FrameLayout.LayoutParams(width, h, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = left
                bottomMargin = bottom
            },
        )
        root.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h + bottom)
        preedit =
            PreeditCodeView(this).apply {
                text = code
                setTextColor(preeditColor)
                setPadding(dp(12), 0, dp(12), 0)
            }
        assistantArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(assistantArea, LinearLayout.LayoutParams(-1, -2))
        box.addView(preedit, LinearLayout.LayoutParams(-1, dp(18)))
        preedit.visibility = if (chinese && code.isNotEmpty()) View.VISIBLE else View.GONE
        header =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        box.addView(header, LinearLayout.LayoutParams(-1, dp(18) + dp(40)))
        createHeaderContents()
        body = FrameLayout(this)
        box.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        if (profile.floating) floatingHandle(box)
        renderHeader()
        renderBody()
    }

    internal fun KeyboardSession.createHeaderContentsImpl() {
        toolbarSignature = null
        headerShowsCandidates = null
        candidateGroup = LinearLayout(this)
        candidateStrip =
            CandidateStrip(this, candidateColor, selectedCandidateColor,
                loadMore = { with(CandidateController) { loadMoreCandidates() } },
                selectionVersion = { generation.takeIf { candidateGeneration == it } },
                feedback = { pressFeedback() },
                textSizeSp = settings.candidateTextSize.toFloat(),
                chooseWithFeedback = { value, feedback -> choose(value, feedback) },
            ) { choose(it) }
        candidateGroup.addView(candidateStrip, LinearLayout.LayoutParams(0, -1, 1f))
        candidateGroup.addView(
            icon("clear", "取消待输入内容") {
                clearPending()
                renderHeader()
            },
            LinearLayout.LayoutParams(dp(42), -1),
        )
        header.addView(candidateGroup, LinearLayout.LayoutParams(0, -1, 1f))
        toolbarGroup = io.secure.keyboard.ui.ToolbarRow(this)
        header.addView(toolbarGroup, LinearLayout.LayoutParams(0, -1, 1f))
        hideIcon = icon("hide", "收起键盘") {
            if (showsCandidateMode()) {
                candidatesExpanded = !candidatesExpanded
                if (candidatesExpanded) with(CandidateController) { loadMoreCandidates() }
                renderHeader()
                renderKeyboardBody()
            } else {
                releaseFinishedInputPresentation()
                clearPending()
                cancelTransient()
                requestHideSelf(0)
            }
        }
        header.addView(hideIcon, LinearLayout.LayoutParams(dp(44), -1))
    }

    internal fun KeyboardSession.showsCandidateModeImpl() = !secret && panel == "keys" &&
        ((chinese && (code.isNotEmpty() || candidateTexts.isNotEmpty() || pinyinPredictionGeneration == generation)) || (!chinese && candidateTexts.isNotEmpty()))

    internal fun KeyboardSession.renderHeaderImpl() {
        if (!headerInitialized) return
        val visibleCode = if (secret) "" else pinyinComposition.text + code
        if (preedit.text.toString() != visibleCode) preedit.text = visibleCode
        val showCode = !secret && chinese && code.isNotEmpty()
        val codeVisibility = if (showCode) View.VISIBLE else View.GONE
        if (preedit.visibility != codeVisibility) preedit.visibility = codeVisibility
        val hasCandidates = showsCandidateMode()
        if (candidatesExpanded && !hasCandidates) {
            candidatesExpanded = false
            renderKeyboardBody()
        }
        // Keep the complete top region fixed, so starting/canceling composition never moves keys.
        // Sum the separately rounded dimensions to preserve exact pixel equality at every density.
        val candidateHeight = (settings.candidateTextSize * resources.displayMetrics.scaledDensity * 1.4f).roundToInt() + dp(4)
        val headerHeight = maxOf(dp(40), candidateHeight) + if (showCode) 0 else dp(18)
        if (header.layoutParams.height != headerHeight)
            header.layoutParams = header.layoutParams.apply { height = headerHeight }
        val visibleCandidates = if (secret) emptyList() else candidateTexts
        candidateStrip.updateCandidates(visibleCandidates)
        if (candidatesExpanded) candidateGrid?.updateCandidates(visibleCandidates)
        hideIcon.rotation = if (candidatesExpanded) 180f else 0f
        hideIcon.contentDescription = when {
            !hasCandidates -> "收起键盘"
            candidatesExpanded -> "收起候选"
            else -> "展开候选"
        }
        quickPasteState.expireIfNeeded()
        val paste = quickPasteState.text?.takeIf {
            active && !secret && settings.quickPaste && panel == "keys" && !translation.enabled
        }
        val pasteRevision = quickPasteState.revision
        val signature = "$panel:${translation.enabled}:$aiMode:${settings.traditionalOutput}:${settings.chineseInputMode.id}:${paste != null}:$pasteRevision" to settings.toolbarPins.toList()
        if (!hasCandidates && toolbarSignature != signature) {
            toolbarGroup.removeAllViews()
            toolbarGroup.addView(
                icon(
                    if (panel == "keys" && !translation.enabled) "more" else "back",
                    if (translation.enabled && panel !in assistantTools) "退出模式" else if (panel == "keys") "更多工具" else "返回键盘",
                ) {
                    if (translation.enabled && panel in assistantTools) {
                        cancelTransient(); clearComposition(); panel = "keys"
                        renderHeader(); renderBody(true)
                        return@icon
                    }
                    if (translation.enabled) {
                        cancelTransient()
                        stopAiRequest()
                        clearComposition()
                        if (aiMode) resetAiConversation()
                        clearAiConversation(); aiMode = false
                        aiCustom = ""; aiCustomReasoning = "low"
                        aiCustomPresetName = ""; aiCustomModel = ""; aiPresetLoadGeneration++
                        aiSelectionTarget.revoke()
                        translation.close()
                        panel = "keys"
                    } else {
                        panel = if (panel == "keys") "more" else "keys"
                        cancelTransient()
                    }
                    renderHeader()
                    renderBody()
                },
                LinearLayout.LayoutParams(dp(44), -1),
            )
            if (paste != null) {
                val preview = button(paste.replace(Regex("[\\r\\n\\t\\u2028\\u2029]+"), " ")) {
                    if (quickPasteState.expireIfNeeded()) { renderHeader(); return@button }
                    if (active && !secret && settings.quickPaste && panel == "keys" &&
                        !translation.enabled && quickPasteState.revision == pasteRevision) {
                        commitInputText(paste)
                        quickPasteState.clear()
                        renderHeader()
                    }
                }.apply {
                    background = null
                    textSize = 16f
                    gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
                    setPadding(dp(12), 0, dp(4), 0)
                    setSingleLine(true)
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    contentDescription = "快捷粘贴：" + paste.take(160)
                }
                val pasteBar = LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    background = shape(keyBg)
                    addView(preview, LinearLayout.LayoutParams(0, -1, 1f))
                }
                pasteBar.addView(icon("clear", "关闭快捷粘贴") {
                    if (quickPasteState.revision == pasteRevision) {
                        quickPasteState.clear()
                        renderHeader()
                    }
                }, LinearLayout.LayoutParams(dp(40), -1).apply { marginStart = dp(4) })
                toolbarGroup.addView(pasteBar, LinearLayout.LayoutParams(0, dp(36), maxOf(1, signature.second.size).toFloat()).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    marginStart = dp(6)
                    marginEnd = dp(6)
                })
            } else {
                signature.second.forEach { tool ->
                    toolbarGroup.addView(
                        icon(tool, tools[tool] ?: tool) { openTool(tool) },
                        LinearLayout.LayoutParams(dp(44), -1),
                    )
                }
            }
            toolbarGroup.layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
            toolbarSignature = signature
        }
        if (headerShowsCandidates != hasCandidates) {
            candidateGroup.visibility = if (hasCandidates) View.VISIBLE else View.GONE
            toolbarGroup.visibility = if (hasCandidates) View.GONE else View.VISIBLE
            val toolbarInset = if (hasCandidates) 0 else dp(8)
            header.setPadding(toolbarInset, 0, toolbarInset, 0)
            hideIcon.layoutParams = LinearLayout.LayoutParams(dp(44), -1)
            headerShowsCandidates = hasCandidates
        }
    }

    internal fun KeyboardSession.renderBodyImpl() = renderBody(translation.enabled && panel in assistantTools)

    internal fun KeyboardSession.renderBodyImpl(preserveAssistant: Boolean) {
        if(assistantVoicePanel!=null) cancelAiCommandVoice()
        val keepAssistant = preserveAssistant && activeAssistantEditor()?.isAttachedToWindow == true
        if (!keepAssistant) releaseAssistantViews()
        applyLiveLayout()
        letterKeyViews.clear()
        shiftKeyView = null
        body.removeAllViews()
        if (!keepAssistant) {
            assistantArea.removeAllViews()
            if (translation.enabled && (panel == "keys" || panel in assistantTools)) renderTranslationBar(assistantArea)
        }
        when (panel) {
            "keys" -> renderKeyboardBody()
            "more" -> renderMore()
            "quickedit" -> renderQuickEdit()
            "clipboard",
            "phrases" -> renderContent()
            "emoji" -> renderEmoji()
            "layout" -> renderLayout()
            "voice" -> renderVoice()
            else -> renderKeys()
        }
    }

    /** Candidate expansion only replaces keys; keep editor identity, selection and focus. */
    internal fun KeyboardSession.renderKeyboardBodyImpl() {
        applyLiveLayout()
        letterKeyViews.clear()
        shiftKeyView = null
        body.removeAllViews()
        if (candidatesExpanded && showsCandidateMode()) renderCandidateGrid() else renderKeys()
    }

    internal fun KeyboardSession.renderCandidateGridImpl() {
        val grid = candidateGrid ?: CandidateGrid(this, candidateColor, selectedCandidateColor, keyBg,
            loadMore = { with(CandidateController) { loadMoreCandidates() } },
            feedback = { pressFeedback() },
            textSizeSp = settings.expandedCandidateTextSize.toFloat(),
            chooseWithFeedback = { value, feedback -> choose(value, feedback) },
            selectionVersion = {
                generation.takeIf { candidatesExpanded && showsCandidateMode() && candidateGeneration == it }
            },
        ) { choose(it) }.also { candidateGrid = it }
        grid.updateCandidates(candidateTexts)
        body.addView(grid, FrameLayout.LayoutParams(-1, -1))
    }

    internal fun KeyboardSession.pressFeedbackImpl() = KeyboardPressFeedback(
        onPress = {
            if (rootInitialized)
                KeyboardHaptics.perform(root, settings.vibration, settings.vibrationStrength)
        },
        onClick = { vibrate -> keyFeedback(vibrate) },
    )

    @android.annotation.SuppressLint("ClickableViewAccessibility") // Return false: View owns click and accessibility dispatch.
    internal fun KeyboardSession.keyFeedbackImpl(vibrate: Boolean = true) {
        if (rootInitialized)
            KeyboardFeedback.perform(
                root,
                settings.keySound,
                settings.vibration && vibrate,
                settings.vibrationStrength,
            )
    }

    internal fun KeyboardSession.renderKeysImpl() {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(column, FrameLayout.LayoutParams(-1, -1))
        val letterSymbols = io.secure.keyboard.data.LetterSymbolStore(this)
        val rows =
            if (symbols) io.secure.keyboard.data.SymbolKeyboardStore(this).rows(chinese, symbolPage)
            else listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").map { row -> row.map { it.toString() } }
        rows.forEachIndexed { i, row ->
            val symbolRow = symbols
            val symbolSpacer =
                if (symbolRow) SymbolKeyLayout.sideSpacerWeight(row.size, i == 2) else 0f
            val line =
                (if (i == 1 && !symbols) IndentedKeyRow(this) else LinearLayout(this)).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
            column.addView(line, LinearLayout.LayoutParams(-1, 0, 1f))
            if (i == 1 && !symbolRow) {
                line.addView(View(this), LinearLayout.LayoutParams(0, 1, .45f))
            }
            if (i == 2)
                addKey(
                    line,
                    if (symbols) "=\\<" else "⇧",
                    1.3f,
                    "",
                    {
                        if (symbols) symbolPage = !symbolPage
                        else {
                            if (chinese && code.isNotEmpty() && pendingConfirmation == null) {
                                toast("请先选择或取消当前编码，再切换大写")
                            } else {
                                val now = keyClickTime
                                if (capsLocked) {
                                    capsLocked = false
                                    shifted = false
                                } else if (shifted && now - lastShiftTap in 0L until 400L) {
                                    capsLocked = true
                                } else shifted = !shifted
                                lastShiftTap = now
                            }
                        }
                        if (symbols) renderBody() else updateShiftKeyFaces()
                    },
                )
            if (symbolSpacer > 0f)
                line.addView(View(this), LinearLayout.LayoutParams(0, 1, symbolSpacer))
            row.forEachIndexed { idx, ch ->
                if (profile.split && !outer && idx == row.size / 2)
                    line.addView(
                        View(this),
                        LinearLayout.LayoutParams(dp(profile.splitGapDp.coerceAtMost(180)), 1),
                    )
                val s = if (!symbolRow && shifted) ch.uppercase() else ch.toString()
                val letter =
                    addKey(
                        line,
                        s,
                        1f,
                        if (!symbols) letterSymbols.symbols(chinese, ch.single()) else "",
                        { type(if (!symbolRow && shifted) ch.uppercase() else ch.toString(),
                            fromSymbolPage = symbolRow) },
                    )
                if (!symbols) letterKeyViews.add(letter to ch.single())
            }
            if (symbolSpacer > 0f)
                line.addView(View(this), LinearLayout.LayoutParams(0, 1, symbolSpacer))
            if (i == 1 && !symbolRow)
                line.addView(View(this), LinearLayout.LayoutParams(0, 1, .45f))
            if (i == 2) addKey(line, "⌫", 1.3f, "", { backspace() }, true)
        }
        val bottom = SymmetricBottomRow(this)
        column.addView(bottom, LinearLayout.LayoutParams(-1, 0, 1f))
        // Integer-scaled ratios avoid fractional-weight rounding leaving a dead pixel at the edge.
        addKey(
            bottom,
            if (symbols) "ABC" else "?123",
            17f,
            "",
            {
                symbols = !symbols
                if (symbols) symbolPage = false
                renderBody()
            },
        )
        addKey(
            bottom,
            if (symbols) { if (chinese) "，" else "." } else if (chinese) "，。" else ". ,",
            9f,
            if (symbols) "" else if (chinese) "。" else ",",
            { type(if (chinese) "，" else ".") },
        )
        addKey(bottom, if (chinese) { if (settings.chineseInputMode == io.secure.keyboard.engine.ChineseInputMode.PINYIN) "拼音" else "五笔" } else "English", 42f, "", { space() }, isSpace = true)
        addKey(
            bottom,
            if (symbols) { if (chinese) "。" else "," } else "◎",
            9f,
            "",
            {
                if (symbols) {
                    type(if (chinese) "。" else ",")
                } else {
                    clearPending()
                    chinese = !chinese
                    settings.lastChineseLanguage = chinese
                    symbols = false
                    renderHeader()
                    renderBody()
                    refreshCandidates()
                }
            },
        )
        addKey(bottom, "↵", 17f, "", { enter() })
    }

    internal fun KeyboardSession.floatingHandleImpl(column: LinearLayout) {
        val row = LinearLayout(this)
        column.addView(row, LinearLayout.LayoutParams(-1, dp(32)))
        // Small artwork inside broad hit regions; the whole frame remains draggable on each panel.
        // Symmetric hit regions keep the drag bar centered; resize artwork aligns to the right edge.
        row.addView(View(this), LinearLayout.LayoutParams(0, -1, 17f))
        val drag =
            ToolIcon(this, "drag_handle", muted).apply {
                contentDescription = "拖动悬浮键盘"
                isFocusable = true
            }
        val resize =
            ToolIcon(this, "resize", muted, artworkSizeDp = 14f,
                artworkRightInsetDp = 10f, opticalOffsetYFraction = -0.13f).apply {
                contentDescription = "调整悬浮键盘大小"
                isFocusable = true
            }
        row.addView(drag, LinearLayout.LayoutParams(0, -1, 60f))
        row.addView(resize, LinearLayout.LayoutParams(0, -1, 17f))
        fun handle(view: View, resizing: Boolean) {
            var sx = 0f
            var sy = 0f
            var original = profile
            var resizeStart = FloatingResize.Bounds(0, 0, 0, 0)
            var validGesture = false
            var translationExtraAtStart = 0
            view.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        validGesture = e.rawX.isFinite() && e.rawY.isFinite()
                        if (!validGesture) return@setOnTouchListener true
                        sx = e.rawX
                        sy = e.rawY
                        original = profile
                        val bounds = box.layoutParams as FrameLayout.LayoutParams
                        translationExtraAtStart = if (resizing) translationExtraHeightPx() else 0
                        resizeStart = FloatingResize.Bounds(
                            pxToDp(bounds.width.toFloat()), pxToDp((bounds.height - translationExtraAtStart).toFloat()),
                            pxToDp(bounds.leftMargin.toFloat()), pxToDp(bounds.bottomMargin.toFloat()),
                        )
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!validGesture || !e.rawX.isFinite() || !e.rawY.isFinite())
                            return@setOnTouchListener true
                        val dx = pxToDp(e.rawX - sx)
                        val dy = pxToDp(e.rawY - sy)
                        val maxWidth = resources.configuration.screenWidthDp
                        profile =
                            if (resizing) {
                                val bounds = FloatingResize.resize(
                                    resizeStart, dx, dy,
                                    pxToDp(resources.displayMetrics.widthPixels.toFloat()),
                                    minOf(700, pxToDp((resources.displayMetrics.heightPixels * 3 / 4).toFloat())),
                                    pxToDp((floatingScreenHeightPx() - translationExtraAtStart).toFloat()),
                                )
                                original.copy(
                                    floatingWidthDp = bounds.width,
                                    floatingHeightDp = bounds.height,
                                    floatingXDp = bounds.left,
                                    floatingYDp = bounds.bottom,
                                )
                            }
                            else
                                original.copy(
                                    floatingXDp =
                                        (original.floatingXDp + dx).coerceIn(
                                            0,
                                            (maxWidth - original.floatingWidthDp).coerceAtLeast(0),
                                        ),
                                    floatingYDp =
                                        (resizeStart.bottom - dy).coerceIn(
                                            0,
                                            FloatingResize.maxBottom(
                                                pxToDp(floatingScreenHeightPx().toFloat()), resizeStart.height),
                                        ),
                                )
                        applyLiveLayout()
                        true
                    }
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        if (validGesture) saveProfile(profile)
                        validGesture = false
                        if (e.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                        true
                    }
                    else -> true
                }
            }
        }
        handle(drag, false)
        handle(resize, true)
    }

    internal fun KeyboardSession.alternateImpl(c: Char): String {
        return io.secure.keyboard.data.LetterSymbolStore(this).symbols(chinese, c)
    }

    internal fun KeyboardSession.updateShiftKeyFacesImpl() {
        // Keep key containers alive between rapid taps; rebuilding them can lose a second touch.
        letterKeyViews.forEach { (view, letter) ->
            val text = if (shifted) letter.uppercase() else letter.toString()
            (view.getChildAt(0) as? TextView)?.text = text
            view.contentDescription = text
        }
        shiftKeyView?.let { view ->
            val ink = if (shifted) selectedKeyText else specialText
            view.background = keyShape(if (shifted) selectedKeyBg else special)
            view.removeViewAt(0)
            view.addView(
                ToolIcon(this, if (capsLocked) "caps" else "shift", ink),
                0,
                FrameLayout.LayoutParams(-1, -1),
            )
            view.contentDescription =
                if (capsLocked) "大写锁定已开启" else if (shifted) "单次大写已开启" else "大写"
        }
    }

    // Draw the same gaps inside a full-cell touch target, never as untouchable layout margins.
    internal fun KeyboardSession.keyShapeImpl(color: Int, enter: Boolean = false): InsetDrawable {
        val horizontal = (settings.keyHorizontalGap * resources.displayMetrics.density / 2f).roundToInt()
        val vertical = (settings.keyVerticalGap * resources.displayMetrics.density / 2f).roundToInt()
        val face = shape(color, if (enter) settings.enterCornerRadius else settings.keyCornerRadius).apply {
            if (settings.keyBorderWidth > 0) setStroke(dp(settings.keyBorderWidth), muted)
        }
        return InsetDrawable(face, horizontal, vertical, horizontal, vertical)
    }

    internal fun KeyboardSession.addKeyImpl(
        row: LinearLayout,
        text: String,
        weight: Float,
        alternatives: String,
        action: () -> Unit,
        delete: Boolean = false,
        isSpace: Boolean = false,
    ): KeyContainer {
        val container = KeyContainer(this)
        if (text == "⇧") shiftKeyView = container
        row.addView(container, LinearLayout.LayoutParams(0, -1, weight))
        val horizontal = (settings.keyHorizontalGap * resources.displayMetrics.density / 2f).roundToInt()
        val vertical = (settings.keyVerticalGap * resources.displayMetrics.density / 2f).roundToInt()
        container.setPadding(horizontal, vertical, horizontal, vertical)
        val singlePunctuation = symbols && row is SymmetricBottomRow && text in listOf("，", "。", ".", ",")
        val specialKey = singlePunctuation || text in listOf("⇧", "⌫", "?123", "ABC", "=\\<", "↵", "◎", "，。", ". ,")
        val selected = text == "⇧" && shifted
        // Only these three screenshot-marked controls get one larger, bold text step.
        val emphasizedLabel = text == "?123" || text == "ABC" || text == "=\\<"
        val compactLabel = isSpace || text.length > 3 || text == "ABC" || text == "=\\<"
        val labelSize = if (emphasizedLabel) 15 else if (compactLabel) 14 else 23
        val ink =
            if (text == "↵") enterText
            else if (selected) selectedKeyText else if (specialKey) specialText else fg
        container.background =
            keyShape(
                if (text == "↵") accent
                else if (selected) selectedKeyBg else if (specialKey) special else keyBg,
                enter = text == "↵",
            )
        val key: View =
            if (singlePunctuation) PunctuationKeyLabel(this).apply {
                this.text = text
                textSize = 23f
                setTextColor(ink)
                setAutoSizeTextTypeUniformWithConfiguration(9, 23, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            } else when (text) {
                "◎" -> ToolIcon(this, "globe", ink)
                "↵" -> ToolIcon(this, "enter", ink)
                "⌫" -> ToolIcon(this, "backspace", ink)
                "⇧" -> ToolIcon(this, if (capsLocked) "caps" else "shift", ink)
                "，。", ". ," ->
                    PunctuationKeyLabel(this).apply {
                        this.text = if (text == "，。") "，" else "."
                        inkCenterYFraction = 2f / 3f
                        textSize = 23f
                        setTextColor(ink)
                        setAutoSizeTextTypeUniformWithConfiguration(
                            9, 23, 1, android.util.TypedValue.COMPLEX_UNIT_SP,
                        )
                    }
                else ->
                    label(
                            text,
                            labelSize.toFloat(),
                            ink,
                        )
                        .apply {
                            if (emphasizedLabel) setTypeface(typeface, Typeface.BOLD)
                            setSingleLine(true)
                            setAutoSizeTextTypeUniformWithConfiguration(
                                9,
                                labelSize,
                                1,
                                android.util.TypedValue.COMPLEX_UNIT_SP,
                            )
                        }
            }
        container.addView(key, FrameLayout.LayoutParams(-1, -1))
        container.contentDescription =
            when (text) {
                "◎" -> "切换中文和英文"
                "⌫" -> "删除"
                "⇧" -> if (capsLocked) "大写锁定已开启" else if (shifted) "单次大写已开启" else "大写"
                "↵" -> "回车"
                else -> text
            }
        container.isFocusable = true
        KeyboardFeedback.disableAutomatic(container)
        val feedback = pressFeedback()
        var touchClickTime = 0L
        container.setOnClickListener {
            val fromTouch = touchClickTime != 0L
            keyClickTime = if (touchClickTime != 0L) touchClickTime else SystemClock.uptimeMillis()
            touchClickTime = 0L
            keyFeedback(vibrate = !fromTouch)
            action()
        }
        val combinedPunctuation = text in listOf("，。", ". ,")
        val showCornerHint =
            combinedPunctuation ||
                when (settings.symbolHints) {
                    "digits" -> alternatives.firstOrNull()?.isDigit() == true
                    "hidden" -> false
                    else -> true
                }
        if (alternatives.isNotEmpty() && showCornerHint) {
            container.addView(
                if (combinedPunctuation) {
                    PunctuationKeyLabel(this).apply {
                        this.text = alternatives.codePoints().findFirst().orElse(0).let { String(Character.toChars(it)) }
                        inkCenterYFraction = 1f / 3f
                        textSize = 23f
                        setTextColor(ink)
                        setAutoSizeTextTypeUniformWithConfiguration(
                            9, 23, 1, android.util.TypedValue.COMPLEX_UNIT_SP,
                        )
                    }
                } else {
                    label(alternatives.codePoints().findFirst().orElse(0).let { String(Character.toChars(it)) }, 9f, muted).apply {
                        gravity = Gravity.TOP or Gravity.END
                        setPadding(0, dp(3), dp(5), 0)
                    }
                },
                FrameLayout.LayoutParams(-1, -1),
            )
        }
        var downX = 0f
        var spacePointerId = -1
        var spaceDownVersion = -1L
        var spaceHoldTransferred = false
        var spaceHoldEligible = false
        var cursorDownX = 0f
        var cursorDownY = 0f
        var cursorSwiping = false
        var movedCursorY = 0
        var movedCursor = 0
        var movedDelete = 0
        var long = false
        var canceled = false
        var choice = 0
        var choices = listOf<String>()
        var popRow: LinearLayout? = null
        val repeat =
            object : Runnable {
                override fun run() {
                    if (!active || canceled || !container.isAttachedToWindow) return
                    long = true
                    keyFeedback()
                    dispatchKey(QueuedKey.RepeatedBackspace)
                    main.postAtTime(this, "key", SystemClock.uptimeMillis() + 65)
                }
            }
        val hold = Runnable {
            if (!active || canceled || !container.isAttachedToWindow) return@Runnable
            if (isSpace && spaceHoldEligible && !canceled && !long && active &&
                panel == "keys" && container.isAttachedToWindow &&
                spaceHoldTouch.canClaim(spacePointerId, spaceDownVersion)) {
                // Mark consumed before renderBody detaches the key and may synthesize CANCEL.
                long = true
                spaceHoldTransferred = true
                if(translation.enabled) {
                    if(spaceHoldTouch.claim(spacePointerId,spaceDownVersion)) {
                        assistantHeldVoice=true
                        startAssistantVoice(true)
                        if(assistantVoicePanel==null) cancelAiCommandVoice()
                    }
                } else beginHeldVoice(spacePointerId, spaceDownVersion)
            } else if (text == "↵" && translation.enabled) {
                long = true
                insertAssistantResult()
            } else if (delete) {
                long = true
                repeat.run()
            } else if (alternatives.isNotEmpty() && settings.longPressSymbols) {
                keyFeedback()
                long = true
                choices = alternatives.codePoints().toArray().map { String(Character.toChars(it)) }
                // Password fields keep alternate-key input, but never magnify its text.
                // The hidden selector uses the same key anchor for the existing slide gesture.
                if (!secret) {
                    val r =
                        LinearLayout(this).apply {
                            isBaselineAligned = false
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(4), dp(4), dp(4), dp(4))
                            background = shape(special)
                        }
                    popRow = r
                    choices.forEachIndexed { index, s ->
                        r.addView(
                            PunctuationKeyLabel(this).apply {
                                this.text = s
                                textSize = 23f
                                setTextColor(fg)
                                setPadding(dp(6), dp(6), dp(6), dp(6))
                                background = shape(if (index == 0) selectedKeyBg else keyBg)
                                setTextColor(if (index == 0) selectedKeyText else fg)
                            },
                            LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                                if (index < choices.lastIndex) rightMargin = dp(4)
                            },
                        )
                    }
                    popup =
                        PopupWindow(r, dp(choices.size * 44 + (choices.size - 1) * 4 + 8), dp(52), false).apply {
                            isTouchable = false
                            showAsDropDown(container, 0, -container.height - dp(54))
                        }
                }
                choice = 0
            }
        }
        val singleTouch = View.OnTouchListener { _, e ->
            feedback.onTouch(e)
            if (!e.x.isFinite() || !e.y.isFinite() || !e.rawX.isFinite() || !e.rawY.isFinite()) {
                if (e.actionMasked == MotionEvent.ACTION_MOVE) {
                    if (isSpace) {
                        spaceHoldEligible = false
                        main.removeCallbacks(hold)
                    }
                    return@OnTouchListener true
                }
                canceled = true
                main.removeCallbacks(hold)
                main.removeCallbacks(repeat)
                container.alpha = 1f
                popup?.dismiss()
                popup = null
                return@OnTouchListener true
            }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    spacePointerId = e.getPointerId(0)
                    spaceDownVersion = spaceHoldTouch.version
                    spaceHoldTransferred = false
                    spaceHoldEligible = isSpace && (!translation.enabled || activeAssistantEditor()!=null)
                    cursorDownX = e.rawX
                    cursorDownY = e.rawY
                    cursorSwiping = false
                    movedCursorY = 0
                    movedCursor = 0
                    movedDelete = 0
                    long = false
                    canceled = false
                    choice = 0
                    container.alpha = .65f
                    main.postAtTime(
                        hold,
                        "key",
                        SystemClock.uptimeMillis() + settings.longPressDelay,
                    )
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val cursorDx = e.rawX - cursorDownX
                    val cursorDy = e.rawY - cursorDownY
                    // Cancel voice eligibility on an early directional move, before the 18dp
                    // cursor step. Returning to center must never re-arm the hold timer.
                    if (isSpace && spaceHoldEligible &&
                        maxOf(abs(cursorDx), abs(cursorDy)) >= ViewConfiguration.get(this).scaledTouchSlop) {
                        spaceHoldEligible = false
                        main.removeCallbacks(hold)
                    }
                    if (
                        isSpace &&
                            (cursorSwiping ||
                                maxOf(abs(cursorDx), abs(cursorDy)) >= dp(18))
                    ) {
                        main.removeCallbacks(hold)
                        long = true
                        if (!cursorSwiping) {
                            cursorSwiping = true
                            clearPending()
                            renderHeader()
                        }
                        val steps = (cursorDx / dp(18)).toInt()
                        val stepsY = (cursorDy / dp(18)).toInt()
                        val delta = steps - movedCursor
                        val deltaY = stepsY - movedCursorY
                        repeat(abs(delta)) { moveCursor(if (delta < 0) -1 else 1) }
                        if (!translation.enabled || activeAssistantEditor()!=null) repeat(abs(deltaY)) {
                            arrow(if (deltaY < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN)
                        }
                        movedCursor = steps
                        movedCursorY = stepsY
                    } else if (delete && settings.swipeDelete) {
                        val count = ((downX - e.x) / dp(20)).toInt().coerceAtLeast(0)
                        if (count > movedDelete) {
                            main.removeCallbacks(hold)
                            main.removeCallbacks(repeat)
                            long = true
                            repeat(count - movedDelete) { if (container.isAttachedToWindow) dispatchKey(QueuedKey.RepeatedBackspace) }
                            movedDelete = count
                        }
                    } else if (long && choices.isNotEmpty()) {
                        val popupLocation = IntArray(2)
                        (popRow ?: container).getLocationOnScreen(popupLocation)
                        choice =
                            ((e.rawX - popupLocation[0] - dp(4) + dp(2)) / dp(48))
                                .toInt()
                                .coerceIn(0, choices.lastIndex)
                        for (i in 0 until (popRow?.childCount ?: 0)) {
                            (popRow?.getChildAt(i) as? TextView)?.apply {
                                background = shape(if (i == choice) selectedKeyBg else keyBg)
                                setTextColor(if (i == choice) selectedKeyText else fg)
                            }
                        }
                    } else if (
                        abs(e.x - downX) > dp(70) ||
                            e.y < -dp(60) ||
                            e.y > container.height + dp(60)
                    ) {
                        canceled = true
                        main.removeCallbacks(hold)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(hold)
                    main.removeCallbacks(repeat)
                    container.alpha = 1f
                    popup?.dismiss()
                    popup = null
                    if (!canceled) {
                        if (long && !delete && choices.isNotEmpty()) type(choices[choice])
                        else if (!long) {
                            touchClickTime = e.eventTime
                            container.performClick()
                        }
                    }
                    choices = emptyList()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    // A voice panel rebuild cancels the child target, but only the root owns
                    // the real held-finger CANCEL/UP after transfer.
                    canceled = true
                    spaceHoldEligible = false
                    main.removeCallbacks(hold)
                    main.removeCallbacks(repeat)
                    container.alpha = 1f
                    popup?.dismiss()
                    popup = null
                    choices = emptyList()
                    true
                }
                else -> true
            }
        }
        container.setOnTouchListener(KeyTouchRouter(
            singleTouch = singleTouch,
            canCompleteOriginalTap = {
                !canceled && !spaceHoldTransferred && !(long && (delete || cursorSwiping))
            },
            completeTap = { eventTime ->
                touchClickTime = eventTime
                container.performClick()
            },
            horizontalCancelDistance = dp(70).toFloat(),
            verticalCancelSlop = dp(60).toFloat(),
            onAdditionalPress = { feedback.press() },
        ))
        return container
    }

}
