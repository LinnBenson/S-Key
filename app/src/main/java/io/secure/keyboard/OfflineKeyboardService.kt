package io.secure.keyboard

import android.content.*
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.inputmethodservice.InputMethodService
import android.os.*
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.*
import java.util.concurrent.Executors
import kotlin.math.*
import org.json.JSONObject
import io.secure.keyboard.concurrent.BoundedTaskQueue
import io.secure.keyboard.data.ClipboardCapturePolicy
import io.secure.keyboard.concurrent.LatestTaskMailbox
import io.secure.keyboard.data.ContentStore
import io.secure.keyboard.data.DictionaryKind
import io.secure.keyboard.data.SettingsStore
import io.secure.keyboard.engine.OfflineEngine
import io.secure.keyboard.engine.CustomCandidateRanking
import io.secure.keyboard.engine.EnglishCustomCandidateRanking
import io.secure.keyboard.engine.Source
import io.secure.keyboard.ui.KeyboardPressFeedback
import io.secure.keyboard.ui.FloatingResize
import io.secure.keyboard.ui.KeyboardHaptics
import io.secure.keyboard.ui.KeyboardFeedback
import io.secure.keyboard.ui.CandidateStrip
import io.secure.keyboard.ui.CandidateGrid
import io.secure.keyboard.ui.CandidateFont
import io.secure.keyboard.ui.ContentEntryRow
import io.secure.keyboard.ui.EmojiGrid
import io.secure.keyboard.ui.IndentedKeyRow
import io.secure.keyboard.ui.KeyContainer
import io.secure.keyboard.ui.KeyTouchRouter
import io.secure.keyboard.ui.SpaceHoldTouchTracker
import io.secure.keyboard.ui.SymbolKeyLayout
import io.secure.keyboard.ui.SymmetricBottomRow
import io.secure.keyboard.ui.PunctuationKeyLabel
import io.secure.keyboard.ui.PreeditCodeView
import io.secure.keyboard.ui.QuickEditSelection
import io.secure.keyboard.ui.ToolIcon
import io.secure.keyboard.ui.styleSystemBars
import io.secure.keyboard.voice.ModelRepository
import io.secure.keyboard.voice.OfflineVoiceSession
import io.secure.keyboard.voice.VoiceCommitGuard
import io.secure.keyboard.voice.VoiceSegmentFormatter

class OfflineKeyboardService : InputMethodService() {
    private lateinit var settings: SettingsStore
    private lateinit var content: ContentStore
    private lateinit var engine: OfflineEngine
    private val worker = Executors.newSingleThreadExecutor()
    private val candidateQueries = LatestTaskMailbox(worker)
    private val clipboardWrites = BoundedTaskQueue(worker, 32, 262_144)
    private val contentQueries = LatestTaskMailbox(worker)
    @Volatile private var destroyed = false
    private val main = Handler(Looper.getMainLooper())
    private var loaded = false
    private var dictionaryLoadFailed = false
    @Volatile private var generation = 0
    private var active = false
    private var chinese = true
    private var shifted = false
    private var capsLocked = false
    private var lastShiftTap = 0L
    private var keyClickTime = 0L
    private val letterKeyViews = mutableListOf<Pair<KeyContainer, Char>>()
    private var shiftKeyView: KeyContainer? = null
    private var symbols = false
    private var symbolPage = false
    private var code = ""
    private sealed class QueuedKey {
        data class Text(val value: String, val asCode: Boolean) : QueuedKey()
        data class EnglishPair(val opening: String, val closing: String) : QueuedKey()
        object Space : QueuedKey()
        object Enter : QueuedKey()
        object Backspace : QueuedKey()
        object RepeatedBackspace : QueuedKey()
    }
    private data class PendingConfirmation(
        val code: String,
        val connection: InputConnection,
        val suffix: String?,
    )
    private var pendingConfirmation: PendingConfirmation? = null
    private var spacingPending = false
    private var spacingEpoch = 0
    private var spacingSelectionVersion = 0
    private val spacingExpectedCursors = ArrayDeque<Int>()
    private val spacingTexts = ArrayDeque<String>()
    private val queuedKeys = ArrayDeque<QueuedKey>()
    private var queueSaturationNotified = false
    private var candidateTexts = listOf<String>()
    private var candidateQuery = ""
    private var candidateGeneration = -1
    private var candidateConnection: InputConnection? = null
    private var candidateDictionaryKind: DictionaryKind? = null
    private var candidateDictionaryRevision: Long? = null
    private var panel = "keys"
    private val translation = io.secure.keyboard.translation.TranslationSession { stopTranslationRequest() }
    private var translationCall: io.secure.keyboard.translation.GoogleTranslation.Call? = null
    private var translationTask: Runnable? = null
    // Independent bounded lanes: canceled socket teardown cannot block the next request.
    private val translationWorker = requestExecutor(2)
    private val aiWorker = requestExecutor(2)
    private val modelCatalogWorker = requestExecutor(1)

    private fun requestExecutor(threads: Int) = java.util.concurrent.ThreadPoolExecutor(
        threads, threads, 15L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(1),
        java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy()
    ).apply { allowCoreThreadTimeOut(true) }
    private var translationTextView: TextView? = null
    private var translationResultView: TextView? = null
    private var translationAction: TextView? = null
    private var translationInsert: TextView? = null
    private var aiConversation: io.secure.keyboard.ai.AiConversation? = null
    private val aiConversationStore by lazy { io.secure.keyboard.data.AiConversationStore(this) }
    private var aiDraftEpoch = 0L
    private val aiResults = mutableListOf<String>()
    private var aiResultIndex = -1
    private var aiResultNavigation: TextView? = null
    private var assistantSourceTitle: TextView? = null
    private var aiCommandVoice: io.secure.keyboard.voice.AiVoiceSession? = null
    private var aiCommandEpoch = 0
    private var aiMode = false
    private var aiTask = "polish"
    private var aiTone = "自然"
    private var aiCustom = ""
    private var aiCustomReasoning = "low"
    private var aiCustomPresetId: String? = null
    private var aiCustomPresetName = ""
    private var aiCustomTab: TextView? = null
    private var aiEditingInstruction = false
    private var aiInstructionView: TextView? = null
    private var aiCall: io.secure.keyboard.ai.AiWriting.Call? = null
    private var aiCallFactory: () -> io.secure.keyboard.ai.AiWriting.Call = { io.secure.keyboard.ai.AiWriting.Call() }
    private var aiConfiguration: () -> io.secure.keyboard.data.ApiConfiguration = { io.secure.keyboard.data.ApiConfigurationStore(this).load() }
    private var aiTaskRunnable: Runnable? = null
    private var aiPreviewDelivery: io.secure.keyboard.ai.LatestPreview? = null
    private var aiModels = emptyList<String>()
    private var aiModel = ""
    private var aiSelectionEpoch = 0
    private val aiSelectionTarget = io.secure.keyboard.ai.AiSelectionTarget()
    private val translationLanguages = linkedMapOf("auto" to "自动检测", "zh-CN" to "简体中文", "zh-TW" to "繁体中文",
        "en" to "英语", "ja" to "日语", "ko" to "韩语", "fr" to "法语", "de" to "德语", "es" to "西班牙语",
        "ru" to "俄语", "pt" to "葡萄牙语", "ar" to "阿拉伯语", "th" to "泰语", "vi" to "越南语", "id" to "印尼语")
    private val quickEditSelection = QuickEditSelection()
    private var emojiCategory = 1
    private var emojiCategoryScroll: Pair<Int, Int>? = null
    private var secret = false
    private lateinit var root: FrameLayout
    private lateinit var box: LinearLayout
    private lateinit var header: LinearLayout
    private lateinit var assistantArea: LinearLayout
    private lateinit var candidateStrip: CandidateStrip
    private var candidateGrid: CandidateGrid? = null
    private var candidatesExpanded = false
    private lateinit var candidateGroup: LinearLayout
    private lateinit var toolbarGroup: LinearLayout
    private lateinit var hideIcon: View
    private var toolbarSignature: Pair<String, List<String>>? = null
    private var headerShowsCandidates: Boolean? = null
    private lateinit var body: FrameLayout
    private lateinit var preedit: TextView
    private var voice: OfflineVoiceSession? = null
    private var voicePreview = ""
    private var aiVoice: io.secure.keyboard.voice.AiVoiceSession? = null
    private var usingAiVoice = false
    private var aiVoiceRoundCommitted = false
    private var aiVoiceRestart: Runnable? = null
    private var voiceState = OfflineVoiceSession.State.IDLE
    private var voiceText: TextView? = null
    private var voiceStatus: TextView? = null
    private var voiceWaveform: io.secure.keyboard.ui.VoiceWaveform? = null
    private val voiceEditorWorker = Executors.newSingleThreadExecutor()
    private val voiceSelectionChecks = LatestTaskMailbox(voiceEditorWorker)
    @Volatile private var voiceEditorGeneration = 0
    @Volatile private var voiceRequest = 0L
    private var voiceStarting = false
    private var voiceGuard: VoiceCommitGuard? = null
    private var voiceConnection: InputConnection? = null
    private val voiceSegments = ArrayDeque<Pair<Long, String>>()
    private var voiceValidationPending = false
    private var heldVoice = false
    private var heldVoiceWaitingKeys = false
    private var heldVoiceFinishRequested = false
    private var heldVoiceSessionFinished = false
    private var assistantHeldVoice = false
    private val spaceHoldTouch = SpaceHoldTouchTracker(
        onRelease = {
            if(assistantHeldVoice) { aiCommandVoice?.stop(); inlineOfflineVoice?.stop() }
            else finishHeldVoice()
        },
        onCancel = { if(assistantHeldVoice) cancelAiCommandVoice() else cancelVoice() },
    )
    private var popup: PopupWindow? = null
    private var dark = false
    private var bg = 0
    private var keyBg = 0
    private var fg = 0
    private var muted = 0
    private var accent = 0
    private var special = 0
    private var toolbarColor = 0
    private var candidateColor = 0
    private var selectedCandidateColor = 0
    private var preeditColor = 0
    private var dividerColor = 0
    private var specialText = 0
    private var selectedKeyBg = 0
    private var selectedKeyText = 0
    private var enterText = 0
    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key?.startsWith("themeTokens_") == true || key == "theme")
                main.post {
                    if (active) {
                        cancelTransient()
                        createView()
                        setInputView(root)
                        refreshCandidates()
                    }
                }
            else if (key == "toolbarPins") main.post {
                if (active) { renderHeader(); if (panel == "more") renderBody() }
            }
            else if (key == "fuzzyPairs" || key == "fuzzyPinyin" || key == "englishCompletion")
                main.post { if (active) refreshCandidates() }
        }
    private lateinit var profile: SettingsStore.LayoutProfile
    private var outer = true
    private var landscape = false
    private lateinit var clips: ClipboardManager
    private val clipListener =
        ClipboardManager.OnPrimaryClipChangedListener { captureClipboard(true) }
    private val tools =
        linkedMapOf(
            "ai" to "AI 助手",
            "translate" to "翻译",
            "clipboard" to "剪贴板",
            "phrases" to "常用语",
            "voice" to "语音",
            "quickedit" to "快捷编辑",
            "emoji" to "表情",
            "layout" to "布局调整",
            "floating" to "悬浮键盘",
            "split" to "分离键盘",
            "theme" to "主题",
            "dictionary" to "中文词库",
            "english_dictionary" to "英文词库",
            "settings" to "设置",
        )
    private val emojiGroups = mutableListOf<Pair<String, List<String>>>()
    private val emojiIcons = listOf("◷", "☺", "☝", "🐾", "🍴", "🚗", "⚽", "💡", "♡", "⚑")

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        loadAssistantWidth()
        chinese = settings.lastChineseLanguage
        content = ContentStore(this)
        engine = OfflineEngine(this)
        clips = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clips.addPrimaryClipChangedListener(clipListener)
        getSharedPreferences("settings", MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(preferenceListener)
        worker.execute {
            runCatching { ModelRepository(this).cleanupInterruptedImports() }
            runCatching { content.cleanupTemporaryFiles() }
            try {
                engine.load()
                if (destroyed) return@execute
                val groups = mutableListOf<Pair<String, List<String>>>()
                val j = JSONObject(assets.open("emoji.json").bufferedReader().use { it.readText() })
                val it = j.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val a = j.getJSONArray(k)
                    if (k != "Component")
                        groups.add(k to (0 until a.length()).map { a.getString(it) })
                }
                main.post {
                    if (destroyed) return@post
                    emojiGroups.addAll(groups)
                    loaded = true
                    if (active && panel == "emoji") renderBody()
                    if (active) refreshCandidates()
                }
            } catch (_: Exception) {
                main.post {
                    if (!destroyed) {
                        dictionaryLoadFailed = true
                        if (active) toast("词库加载失败，请重新打开键盘")
                        releasePendingConfirmation()
                    }
                }
            }
        }
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onCreateInputView(): View {
        createView()
        return root
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        val restoreKeysAfterPanel = active && (candidatesExpanded || panel != "keys" ||
            (::body.isInitialized && body.childCount == 0) || translation.enabled)
        voiceEditorGeneration++
        releaseFinishedInputPresentation()
        clearPending()
        // An editor restart must not carry a held key, popup, or voice preview into its new target.
        cancelTransient()
        panel = "keys"
        val entry = editorEntry(attribute)
        secret = entry.secret
        // Only an opening/new editor applies the field default. A live restart preserves any
        // manual language or symbol-page change made after the keyboard became visible.
        if (!restarting || !active) applyEditorEntry(entry)
        // Restart can keep the current input view alive. Releasing a non-key panel or grid
        // must not leave an empty body before (or without) a subsequent onStartInputView.
        if (restoreKeysAfterPanel && ::body.isInitialized) {
            renderHeader()
            renderBody()
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val entry = editorEntry(info)
        secret = entry.secret
        if (!restarting || !active) applyEditorEntry(entry)
        quickEditSelection.reset()
        active = true
        // Mode/privacy state is final before constructing or attaching the first visible root.
        createView()
        setInputView(root)
        captureClipboard()
        refreshCandidates()
    }

    private fun editorEntry(info: EditorInfo?) = EditorEntryPolicy.resolve(
        inputType = info?.inputType ?: 0,
        imeOptions = info?.imeOptions ?: 0,
        rememberedChinese = settings.lastChineseLanguage,
    )

    private fun applyEditorEntry(entry: EditorEntryPolicy.Entry) {
        // Field defaults are temporary. Only the explicit globe action writes the preference.
        chinese = entry.chinese
        symbols = entry.symbols
        symbolPage = false
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        active = false
        voiceEditorGeneration++
        releaseFinishedInputPresentation()
        cancelTransient()
        clearPending()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        active = false
        voiceEditorGeneration++
        releaseFinishedInputPresentation()
        quickEditSelection.reset()
        cancelTransient()
        clearPending()
        super.onFinishInput()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        cancelTransient()
        if (active) {
            createView()
            setInputView(root)
            refreshCandidates()
        }
    }

    override fun onDestroy() {
        stopAiRequest()
        translation.close()
        translationWorker.shutdownNow()
        aiWorker.shutdownNow()
        modelCatalogWorker.shutdownNow()
        destroyed = true
        active = false
        clipboardWrites.close()
        contentQueries.close()
        candidateQueries.close()
        voiceSelectionChecks.close()
        releaseFinishedInputPresentation()
        clips.removePrimaryClipChangedListener(clipListener)
        getSharedPreferences("settings", MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(preferenceListener)
        cancelTransient()
        clearPending()
        aiVoice?.release(); aiVoice=null
        voice?.release()
        voiceEditorWorker.shutdownNow()
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd,
        )
        val moved = oldSelStart != newSelStart || oldSelEnd != newSelEnd
        if (moved) {
            val expected = if (newSelStart == newSelEnd) spacingExpectedCursors.indexOf(newSelStart) else -1
            if (expected >= 0) repeat(expected + 1) { spacingExpectedCursors.removeFirst() }
            else { spacingSelectionVersion++; spacingExpectedCursors.clear() }
            // This callback describes the HOST editor, not the assistant's local EditText.
            // Delayed host cursor acknowledgements must not discard an inline voice result.
            // Actual editor replacement is still revoked by voiceEditorGeneration/lifecycle;
            // local draft and selection changes are guarded by the inline listener itself.
            aiSelectionEpoch++
            aiSelectionTarget.revoke()
            if (aiMode) updateTranslationPreview()
        }
        if ((voiceStarting && moved) ||
            voiceGuard?.selectionChanged(oldSelStart, oldSelEnd, newSelStart, newSelEnd) == false) {
            stopVoiceForEditorChange()
        }
        if (active && !chinese && !secret) refreshCandidates()
    }

    private fun cancelTransient() {
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun pxToDp(v: Float): Int {
        val value = v / resources.displayMetrics.density
        return if (value.isFinite()) value.roundToInt() else 0
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun palette() {
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

    private fun shape(color: Int, radius: Int = 12) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun label(text: String, size: Float = 16f, color: Int = fg) =
        TextView(this).apply {
            KeyboardFeedback.disableAutomatic(this)
            this.text = text
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }

    private fun button(text: String, action: () -> Unit): TextView =
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

    private fun toolIsSelected(name: String): Boolean = when(name) {
        "ai" -> translation.enabled && aiMode
        "translate" -> translation.enabled && !aiMode
        else -> name == panel
    }

    private fun icon(name: String, description: String, action: () -> Unit) =
        ToolIcon(
            this,
            if (name == "floating") {
                if (profile.floating) "floating_exit" else "floating_enter"
            } else name,
            if (toolIsSelected(name)) accent else toolbarColor,
        ).apply {
            KeyboardFeedback.disableAutomatic(this)
            isSelected = toolIsSelected(name)
            contentDescription = description
            isClickable = true
            isFocusable = true
            val feedback = installPressFeedback()
            setOnClickListener {
                feedback.click()
                action()
            }
        }

    private fun loadProfile() {
        val c = resources.configuration
        landscape = c.orientation == Configuration.ORIENTATION_LANDSCAPE
        outer = c.smallestScreenWidthDp < 600
        profile = settings.profile(outer, landscape)
    }

    private fun saveProfile(p: SettingsStore.LayoutProfile) {
        settings.saveProfile(outer, landscape, p)
        profile = p
    }

    private fun createView() {
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
                    if (::root.isInitialized && this !== root) return true
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

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        if (::profile.isInitialized && profile.floating && ::root.isInitialized) {
            outInsets.contentTopInsets = root.height
            outInsets.visibleTopInsets = root.height
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
            val location = IntArray(2)
            box.getLocationInWindow(location)
            outInsets.touchableRegion.set(
                location[0],
                location[1],
                location[0] + box.width,
                location[1] + box.height,
            )
        }
    }

    private fun createHeaderContents() {
        toolbarSignature = null
        headerShowsCandidates = null
        candidateGroup = LinearLayout(this)
        candidateStrip =
            CandidateStrip(this, candidateColor, selectedCandidateColor,
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
        toolbarGroup = LinearLayout(this)
        header.addView(toolbarGroup, LinearLayout.LayoutParams(0, -1, 1f))
        hideIcon = icon("hide", "收起键盘") {
            if (showsCandidateMode()) {
                candidatesExpanded = !candidatesExpanded
                renderHeader()
                renderKeyboardBody()
            } else {
                releaseFinishedInputPresentation()
                clearPending()
                cancelTransient()
                requestHideSelf(0)
            }
        }
        header.addView(hideIcon, LinearLayout.LayoutParams(0, -1, 1f))
    }

    private fun showsCandidateMode() = !secret && panel == "keys" &&
        ((chinese && code.isNotEmpty()) || (!chinese && candidateTexts.isNotEmpty()))

    private fun renderHeader() {
        if (!::header.isInitialized) return
        val visibleCode = if (secret) "" else code
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
        val signature = "$panel:${translation.enabled}:$aiMode" to settings.toolbarPins.toList()
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
                        aiCustomPresetName = ""
                        aiEditingInstruction = false
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
                LinearLayout.LayoutParams(0, -1, 1f),
            )
            signature.second.forEach { tool ->
                toolbarGroup.addView(
                    icon(tool, tools[tool] ?: tool) { openTool(tool) },
                    LinearLayout.LayoutParams(0, -1, 1f),
                )
            }
            toolbarGroup.layoutParams =
                LinearLayout.LayoutParams(0, -1, (signature.second.size + 1).toFloat())
            toolbarSignature = signature
        }
        if (headerShowsCandidates != hasCandidates) {
            candidateGroup.visibility = if (hasCandidates) View.VISIBLE else View.GONE
            toolbarGroup.visibility = if (hasCandidates) View.GONE else View.VISIBLE
            hideIcon.layoutParams =
                if (hasCandidates) LinearLayout.LayoutParams(dp(44), -1)
                else LinearLayout.LayoutParams(0, -1, 1f)
            headerShowsCandidates = hasCandidates
        }
    }

    private val assistantTools = setOf("clipboard", "phrases", "emoji", "quickedit")

    private fun renderBody() = renderBody(translation.enabled && panel in assistantTools)

    private fun renderBody(preserveAssistant: Boolean) {
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
    private fun renderKeyboardBody() {
        applyLiveLayout()
        letterKeyViews.clear()
        shiftKeyView = null
        body.removeAllViews()
        if (candidatesExpanded && showsCandidateMode()) renderCandidateGrid() else renderKeys()
    }

    private fun renderCandidateGrid() {
        val grid = candidateGrid ?: CandidateGrid(this, candidateColor, selectedCandidateColor, keyBg,
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

    private fun clearPending() {
        spacingEpoch++; spacingPending = false; spacingTexts.clear(); spacingExpectedCursors.clear()
        pendingConfirmation = null
        queuedKeys.clear()
        queueSaturationNotified = false
        clearComposition()
    }

    /** Successful ordered commits clear the code without discarding later queued keystrokes. */
    private fun clearComposition() {
        generation++
        candidateQueries.clear()
        code = ""
        clearCandidates()
        if (candidatesExpanded) {
            candidatesExpanded = false
            if (::body.isInitialized) renderKeyboardBody()
        }
    }

    /** Finished/hidden/editor-invalidated ownership only; never the asynchronous loading path. */
    private fun releaseFinishedInputPresentation() {
        // Native posted clicks/long presses must be revoked while their views are still in
        // the root. cancelTransient separately revokes our key timers and voice session.
        if (::root.isInitialized) root.cancelPendingInputEvents()
        contentQueries.clear()
        val releasePanel = panel != "keys" || translation.enabled
        stopAiRequest()
        clearAiConversation(); aiMode = false; aiCustom = ""; aiCustomReasoning = "low"; aiCustomPresetName = ""; aiEditingInstruction = false; aiSelectionTarget.revoke()
        translation.close()
        releaseAssistantViews()
        if (::assistantArea.isInitialized) assistantArea.removeAllViews()
        // Reset before cancelTransient: held-voice cancellation must not rebuild an obsolete
        // body before a new editor's language/privacy policy has been resolved.
        panel = "keys"
        if (::candidateStrip.isInitialized) candidateStrip.releasePresentation()
        candidateGrid?.let { grid ->
            grid.releasePresentation()
            (grid.parent as? ViewGroup)?.removeView(grid)
        }
        candidateGrid = null
        candidatesExpanded = false
        if (releasePanel && ::body.isInitialized) {
            // Content rows own complete strings and callbacks; emoji/other panels own their
            // models/listeners. Drop only the finished panel, retaining ordinary letter keys.
            body.removeAllViews()
            letterKeyViews.clear()
            shiftKeyView = null
        }
        if (::preedit.isInitialized) {
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

    private fun clearCandidates() {
        candidateTexts = emptyList()
        candidateQuery = ""
        candidateGeneration = -1
        candidateConnection = null
        candidateDictionaryKind = null
        candidateDictionaryRevision = null
        candidateGrid?.updateCandidates(emptyList())
    }

    private fun englishPrefix(connection: InputConnection?): String =
        try {
            readEnglishPrefix(connection)
        } catch (_: RuntimeException) {
            // An unavailable or unsupported host connection must never take down the keyboard.
            ""
        }

    private fun readEnglishPrefix(connection: InputConnection?): String {
        fun continuesToken(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c.isDigit() || c in "_'’"
        if (connection == null || !connection.getSelectedText(0).isNullOrEmpty()) return ""
        // Read the prefix before validating the boundary. An asynchronous host can move the
        // cursor while servicing these calls; never combine an old end-of-word boundary with
        // a newer prefix from the middle of a word. Unknown host state suppresses completion.
        val before = connection.getTextBeforeCursor(41, 0)?.toString() ?: return ""
        val after = connection.getTextAfterCursor(1, 0) ?: return ""
        if (after.firstOrNull()?.let { continuesToken(it) } == true ||
            !connection.getSelectedText(0).isNullOrEmpty()) return ""
        // One extra character distinguishes a complete 40-letter code from a longer token's tail.
        val prefix = before.takeLastWhile { it in 'a'..'z' || it in 'A'..'Z' }
        if (prefix.length > 40) return ""
        val preceding = before.getOrNull(before.length - prefix.length - 1)
        if (preceding != null && (preceding.isDigit() || preceding in "_'’")) return ""
        return prefix
    }

    private fun assistantEnglishPrefix(editor: EditText): String {
        val cursor=editor.selectionStart
        if(cursor<0 || cursor!=editor.selectionEnd) return ""
        val text=editor.text
        val next=text.getOrNull(cursor)
        if(next!=null && (next.isLetterOrDigit() || next in "_'’")) return ""
        val before=text.substring(0,cursor).takeLast(41)
        val prefix=before.takeLastWhile { it in 'a'..'z' || it in 'A'..'Z' }
        if(prefix.length>40) return ""
        val preceding=before.getOrNull(before.length-prefix.length-1)
        return if(preceding!=null && (preceding.isDigit() || preceding in "_'’")) "" else prefix
    }

    private fun refreshCandidates() {
        val seq = ++generation
        val zh = chinese
        val kind = if (zh) DictionaryKind.CHINESE else DictionaryKind.ENGLISH
        val localCode = code
        val connection = currentInputConnection
        val localEditor = activeAssistantEditor()
        val localPrefix = localEditor?.let { assistantEnglishPrefix(it) }
        // Keep the previous row visible while its replacement is computed. Its generation is now
        // stale, so neither a tap nor Space can commit it. Privacy changes are the exception.
        if (candidateDictionaryKind?.let { candidateDictionaryRevision != content.dictionaryRevision(it) } == true)
            clearCandidates()
        if (
            (translation.enabled && !zh && localEditor==null) || (zh && localCode.isEmpty()) || !loaded || secret || (!zh && !settings.englishCompletion)
        ) {
            candidateQueries.clear()
            clearCandidates()
            renderHeader()
            return
        }
        renderHeader()
        val fuzzy = settings.fuzzyPinyin
        val fuzzyPairs = settings.fuzzyPairs
        val candidatePaint = candidateStrip.normalPaintSnapshot()
        candidateQueries.submit(onRejected = { candidateQueryFailed(seq, zh, connection) }) query@{
            try {
            if (destroyed || seq != generation) return@query
            // WebView and other asynchronous editors may need their main thread to answer.
            // Never block the IME/main thread with synchronous surrounding-text requests.
            val query = if (zh) localCode else localPrefix ?: englishPrefix(connection)
            if (destroyed || seq != generation) return@query
            val dictionaryRevision = content.dictionaryRevision(kind)
            val user = readContent(emptyList<ContentStore.DictionaryEntry>()) {
                content.dictionary(kind)
            }
            val results =
                if (zh) {
                    val base = engine.candidates(query, fuzzy, 40, fuzzyPairs)
                    val baseline =
                        base.filter { it.source == Source.WUBI }.map { it.text } +
                            base.filter { it.source != Source.WUBI }.map { it.text }
                    engine.appendEmoji(CustomCandidateRanking.merge(
                        query, baseline, user,
                        reading = { it.reading }, text = { it.word },
                    ))
                } else EnglishCustomCandidateRanking.merge(
                    query, engine.completeEnglish(query, 12).map { it.text }, user,
                    reading = { it.reading }, text = { it.word },
                )
            if (destroyed || seq != generation) return@query
            CandidateFont.prepare(this, results, candidatePaint)
            if (destroyed || seq != generation) return@query
            main.post {
                if (
                    seq == generation && active && chinese == zh &&
                        currentInputConnection === connection &&
                        (zh || localEditor===activeAssistantEditor())
                ) {
                    if (
                        secret || (!zh && !settings.englishCompletion) ||
                            dictionaryRevision != content.dictionaryRevision(kind)
                    ) {
                        // A clear/edit must not resurrect a previously captured user entry.
                        clearCandidates()
                        renderHeader()
                        if (pendingConfirmation != null) refreshCandidates()
                        return@post
                    }
                    candidateTexts = results
                    candidateQuery = query
                    candidateGeneration = seq
                    candidateConnection = connection
                    candidateDictionaryKind = kind
                    candidateDictionaryRevision = dictionaryRevision
                    renderHeader()
                    finishPendingConfirmation()
                }
            }
            } catch (_: Exception) {
                candidateQueryFailed(seq, zh, connection)
            }
        }
    }

    private fun candidateQueryFailed(seq: Int, zh: Boolean, connection: InputConnection?) {
        main.post {
            if (!destroyed && seq == generation && active && chinese == zh &&
                currentInputConnection === connection) {
                clearCandidates()
                renderHeader()
                if (pendingConfirmation != null) {
                    toast("候选查询失败，可修改编码或按回车输入编码")
                    releasePendingConfirmation()
                }
            }
        }
    }

    private fun choose(value: String): Boolean = choose(value) {}

    private fun choose(value: String, feedback: () -> Unit): Boolean {
        val ic = currentInputConnection ?: return false
        val kind = if (chinese) DictionaryKind.CHINESE else DictionaryKind.ENGLISH
        val dictionaryRevision = candidateDictionaryRevision
        if (candidateDictionaryKind != kind || dictionaryRevision != content.dictionaryRevision(kind)) {
            clearCandidates()
            renderHeader()
            return false
        }
        if (
            !active || secret || candidateGeneration != generation ||
                candidateConnection !== ic || value !in candidateTexts
        ) return false
        if (chinese) {
            if (candidateQuery != code || code.isEmpty()) return false
            feedback()
            if (!commitInputText(value)) return false
            clearComposition()
        } else {
            if (candidateQuery.isEmpty() || !settings.englishCompletion) return false
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
            candidateQueries.clear()
            worker.execute {
                val prefix = englishPrefix(ic)
                val following = try { ic.getTextAfterCursor(1, 0)?.toString() }
                    catch (_: RuntimeException) { null }
                if (following == null) return@execute
                val existingSpace = following.startsWith(" ")
                main.post {
                    if (
                        !active ||
                            chinese ||
                            secret ||
                            !settings.englishCompletion ||
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
                        if (!ic.commitText(if (existingSpace) value else "$value ",
                                if (existingSpace) 2 else 1)) return@post
                    } finally {
                        ic.endBatchEdit()
                    }
                    clearPending()
                    renderHeader()
                }
            }
            return true
        }
        renderHeader()
        return true
    }

    private fun commitInputText(value: String): Boolean {
        if (aiCommandVoice != null || inlineOfflineVoice != null) cancelAiCommandVoice()
        if (translation.enabled) {
            activeAssistantEditor()?.let { editor ->
                val start=minOf(editor.selectionStart,editor.selectionEnd).coerceAtLeast(0)
                val end=maxOf(editor.selectionStart,editor.selectionEnd).coerceAtLeast(start)
                val retainedLength = editor.length() - (end - start)
                editor.text.replace(start,end,value)
                // Editable.replace keeps selection spans; explicitly collapse after accepted text.
                val insertedLength = (editor.length() - retainedLength).coerceAtLeast(0)
                editor.setSelection((start + insertedLength).coerceAtMost(editor.length()))
                return true
            }
            if (aiMode) stopAiRequest()
            if (aiMode && aiEditingInstruction) {
                if (aiCustom.length + value.length > 2000) { toast("指令最多2000字符"); return false }
                aiCustom += value; resetAiConversation(); translation.invalidate(); updateTranslationPreview(); return true
            }
            val accepted = translation.edit(translation.text + value)
            if (!accepted) toast("原文最多 5000 字符")
            updateTranslationPreview()
            return accepted
        }
        if (settings.autoChineseSpacing && !secret && value.isNotEmpty()) {
            val type = currentInputEditorInfo?.inputType ?: 0
            val variation = type and android.text.InputType.TYPE_MASK_VARIATION
            if (type and android.text.InputType.TYPE_MASK_CLASS == android.text.InputType.TYPE_CLASS_TEXT &&
                variation !in listOf(android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                    android.text.InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, android.text.InputType.TYPE_TEXT_VARIATION_URI)) {
                if (spacingTexts.size >= 1024) return false
                spacingTexts.addLast(value)
                if (!spacingPending) commitNextSpacedText()
                return true
            }
        }
        return currentInputConnection?.commitText(value, 1) == true
    }

    private fun commitNextSpacedText() {
        val connection = currentInputConnection ?: run { spacingTexts.clear(); spacingPending=false; return }
        val value = spacingTexts.removeFirstOrNull() ?: run { spacingPending=false; replayQueuedKeys(); return }
        spacingPending = true
        val token = spacingEpoch
        val editor = voiceEditorGeneration
        val selectionEpoch = spacingSelectionVersion
        worker.execute {
            val selection = readVoiceSelection(connection)
            val edit = runCatching {
                val before = connection.getTextBeforeCursor(2, 0)?.toString() ?: ""
                val after = connection.getTextAfterCursor(2, 0)?.toString() ?: ""
                // Without absolute selection coordinates avoid adding a suffix we cannot place the cursor before.
                ChineseSpacing.format(value, before, if (selection == null) "" else after)
            }.getOrElse { ChineseSpacing.format(value) }
            main.post {
                if (token != spacingEpoch) return@post
                if (!active || secret || editor != voiceEditorGeneration || connection !== currentInputConnection ||
                    selectionEpoch != spacingSelectionVersion) { spacingPending=false; spacingTexts.clear(); queuedKeys.clear(); return@post }
                if (selection != null) {
                    if (spacingExpectedCursors.size >= 1024) spacingExpectedCursors.removeFirst()
                    spacingExpectedCursors.addLast(minOf(selection.first, selection.second) + edit.text.length - if (edit.trailingSpace) 1 else 0)
                }
                connection.beginBatchEdit()
                try {
                    val accepted = connection.commitText(edit.text, 1)
                    if (accepted && edit.trailingSpace && selection != null) {
                        val cursor = minOf(selection.first, selection.second) + edit.text.length - 1
                        connection.setSelection(cursor, cursor)
                    }
                } finally { connection.endBatchEdit() }
                spacingPending=false
                if (spacingTexts.isNotEmpty()) commitNextSpacedText() else replayQueuedKeys()
                refreshCandidates()
            }
        }
    }

    private fun commit(value: String) {
        commitInputText(value)
        refreshCandidates()
    }

    private fun type(s: String, fromSymbolPage: Boolean = false) {
        val asCode =
            chinese &&
                !symbols &&
                !secret &&
                !shifted &&
                !capsLocked &&
                s.length == 1 &&
                s[0] in 'a'..'z'
        // Capture case/mode at release. A later Shift or symbol-page change must not reinterpret
        // a key that is waiting behind an earlier Chinese confirmation.
        if (shifted && !capsLocked) {
            shifted = false
            updateShiftKeyFaces()
        }
        val closing = if (fromSymbolPage && symbols && !chinese) {
            when (s) {
                "\"" -> "\""
                "'" -> "'"
                "{" -> "}"
                "[" -> "]"
                else -> null
            }
        } else null
        dispatchKey(if (closing != null) QueuedKey.EnglishPair(s, closing) else QueuedKey.Text(s, asCode))
    }

    private fun dispatchKey(key: QueuedKey) {
        if (key != QueuedKey.Backspace) assistantBackspaceTapAt = 0L
        if (pendingConfirmation != null || spacingPending) {
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

    private fun applyKey(key: QueuedKey) {
        when (key) {
            is QueuedKey.Text -> applyText(key.value, key.asCode)
            is QueuedKey.EnglishPair -> applyEnglishPair(key.opening, key.closing)
            QueuedKey.Space -> applySpace()
            QueuedKey.Enter -> applyEnter()
            QueuedKey.Backspace -> applyBackspace()
            QueuedKey.RepeatedBackspace -> performBackspace(false)
        }
    }

    private fun applyEnglishPair(opening: String, closing: String) {
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
            editor.setSelection(start + opening.length)
            sourceCursor = editor.selectionStart
            symbols = false
            symbolPage = false
            renderBody()
            refreshCandidates()
            return
        }
        val connection = currentInputConnection ?: return
        connection.beginBatchEdit()
        val inserted = try {
            // Replace the selection once, then move back over the ASCII closer with an empty
            // edit. A nonempty commit at cursor=0 is unreliable on older BaseInputConnection:
            // its selection marker advances across the newly inserted character. Here -1 is
            // relative to the already-collapsed caret, and no second text insertion can move it.
            // Both operations use the same connection/batch; no text reads or queued key events.
            connection.commitText(opening + closing, 1) && connection.commitText("", -1)
        } finally {
            connection.endBatchEdit()
        }
        if (!inserted) return
        symbols = false
        symbolPage = false
        renderBody()
        refreshCandidates()
    }

    private fun applyText(s: String, asCode: Boolean) {
        if (asCode) {
            if(aiMode) aiDraftEpoch++
            if (translation.enabled && !aiMode) { translation.invalidate(); updateTranslationPreview() }
            if (code.length >= 40) return
            code += s.lowercase()
            refreshCandidates()
            if (settings.fourCodeAutoCommit && code.length == 4 && loaded) {
                engine.exactWubi(code, 1).firstOrNull()?.let {
                    // A detached or temporarily unavailable host may reject the edit.
                    // Retain composition and candidates so the user can retry explicitly.
                    if (commitInputText(it.text)) {
                        clearComposition()
                        renderHeader()
                    }
                }
            }
        } else {
            if (chinese && code.isNotEmpty()) {
                if (!confirmChinese(s)) return
            }
            commit(s)
        }
    }

    /** Returns true only when the candidate has already been committed synchronously. */
    private fun confirmChinese(suffix: String?): Boolean {
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

    private fun finishPendingConfirmation() {
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
        val first = candidateTexts.firstOrNull()
        if (first == null) {
            toast("没有匹配候选，请修改编码或按回车输入编码")
        } else if (choose(first)) {
            pending.suffix?.let { commit(it) }
        } else {
            toast("输入框暂时无法接收文字，请重试")
        }
        replayQueuedKeys()
    }

    /** Failure revokes only the wait; the original code remains editable and later taps replay. */
    private fun releasePendingConfirmation() {
        pendingConfirmation = null
        replayQueuedKeys()
    }

    private fun replayQueuedKeys() {
        while (active && pendingConfirmation == null && !spacingPending && queuedKeys.isNotEmpty())
            applyKey(queuedKeys.removeFirst())
        if (queuedKeys.isEmpty()) queueSaturationNotified = false
        startHeldVoiceWhenKeysSettle()
    }

    private fun backspace() = dispatchKey(QueuedKey.Backspace)

    private var assistantBackspaceTapAt = 0L

    private fun applyBackspace() = performBackspace(true)

    private fun performBackspace(tap: Boolean) {
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
        if (translation.enabled && translation.text.isEmpty() && code.isEmpty() &&
            !(aiMode && aiEditingInstruction)) {
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
            if (aiMode && aiEditingInstruction) {
                if (aiCustom.isNotEmpty()) { aiCustom = aiCustom.substring(0, aiCustom.offsetByCodePoints(aiCustom.length,-1)); resetAiConversation() }
                translation.invalidate(); updateTranslationPreview(); return
            }
            translation.backspace(); updateTranslationPreview(); refreshCandidates()
        } else {
            // Delegate selection and Unicode deletion to the host's standard Backspace handler.
            // Synchronous text reads here can deadlock a same-process WebView renderer.
            arrow(KeyEvent.KEYCODE_DEL)
            refreshCandidates()
        }
    }

    /** Host cursor navigation preserves the editor's own Unicode/grapheme and selection rules. */
    private fun moveCursor(direction: Int) {
        if (translation.enabled && activeAssistantEditor()==null) return
        arrow(if (direction < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
    }

    private fun space() = dispatchKey(QueuedKey.Space)

    private fun applySpace() {
        if (chinese && code.isNotEmpty()) {
            confirmChinese(null)
        } else commit(" ")
    }

    private fun enter() = dispatchKey(QueuedKey.Enter)

    private fun applyEnter() {
        if (chinese && code.isNotEmpty()) {
            val rawCode = code
            if (!commitInputText(rawCode)) return
            clearComposition()
            renderHeader()
            return
        }
        if (translation.enabled) {
            if (aiMode && translation.busy) {
                stopAiRequest(); val token=translation.begin(); translation.complete(token,"已停止生成",true); updateAiPreview()
            } else requestTranslation()
            return
        }
        if (!sendDefaultEditorAction(true)) commit("\n")
    }

    private fun pressFeedback() = KeyboardPressFeedback(
        onPress = {
            if (::root.isInitialized)
                KeyboardHaptics.perform(root, settings.vibration, settings.vibrationStrength)
        },
        onClick = { vibrate -> keyFeedback(vibrate) },
    )

    @android.annotation.SuppressLint("ClickableViewAccessibility") // Return false: View owns click and accessibility dispatch.
    private fun View.installPressFeedback(): KeyboardPressFeedback = pressFeedback().also { feedback ->
        setOnTouchListener { _, event -> feedback.onTouch(event); false }
    }

    private fun keyFeedback(vibrate: Boolean = true) {
        if (::root.isInitialized)
            KeyboardFeedback.perform(
                root,
                settings.keySound,
                settings.vibration && vibrate,
                settings.vibrationStrength,
            )
    }

    private var syncingAssistantEditor = false
    private var sourceCursor = -1
    private var assistantVoicePanel: View? = null
    private var inlineOfflineVoice: OfflineVoiceSession? = null
    private fun activeAssistantEditor(): EditText? =
        if (!translation.enabled || (aiMode && aiEditingInstruction)) null
        else translationTextView as? EditText

    private fun syncAssistantText(view: TextView?, value: String) {
        if (view == null || view.text.toString() == value) return
        syncingAssistantEditor = true
        try {
            val cursor=(view as? EditText)?.selectionStart ?: value.length
            view.text=value
            (view as? EditText)?.setSelection(cursor.coerceIn(0,value.length))
        } finally { syncingAssistantEditor=false }
    }

    private fun assistantEditor(): EditText = object: EditText(this) {
        override fun onSelectionChanged(start:Int,end:Int) {
            super.onSelectionChanged(start,end)
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
            if(aiEditingInstruction) { cancelAiCommandVoice(); clearComposition() }
            aiEditingInstruction=false
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

    private var assistantResultTitle: TextView? = null

    private fun clearAssistantSource() {
        if (aiMode) {
            stopAiRequest(); resetAiConversation(); clearAiConversation(); aiEditingInstruction=false; aiSelectionTarget.revoke()
        }
        translation.edit(""); clearComposition(); renderBody(false); refreshCandidates()
    }

    private fun insertAssistantResult() {
        if (!active || secret || !translation.enabled || translation.busy || code.isNotEmpty() || translation.result.isEmpty()) return
        if (aiMode) applyAiResult()
        else if (currentInputConnection?.commitText(translation.result,1)==true) {
            translation.edit(""); clearComposition(); updateTranslationPreview(); refreshCandidates()
        }
    }

    private var assistantEditConnection: android.view.inputmethod.InputConnection? = null

    private fun quickEditConnection(): android.view.inputmethod.InputConnection? {
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

    private fun quickEditAction(action: Int) {
        if (translation.enabled) activeAssistantEditor()?.let { editor ->
            editor.requestFocus()
            editor.onTextContextMenuItem(action)
        } else currentInputConnection?.performContextMenuAction(action)
    }

    private fun releaseAssistantViews() {
        if (assistantEditConnection != null) quickEditSelection.reset()
        assistantEditConnection = null
        sourceCursor=(translationTextView as? EditText)?.selectionStart ?: sourceCursor
        syncingAssistantEditor=true
        aiCustomTab=null
        assistantResultTitle=null
        aiResultNavigation=null; assistantSourceTitle=null
        translationTextView?.text = ""
        translationResultView?.text = ""
        aiInstructionView?.text = ""
        translationTextView = null
        translationResultView = null
        aiInstructionView = null
        translationAction = null
        translationInsert = null
        syncingAssistantEditor=false
    }

    private fun stopTranslationRequest() {
        translationCall?.cancel(); translationCall = null
        translationTask?.let { translationWorker.remove(it) }; translationTask = null
    }

    private fun stopAiRequest() {
        aiPreviewDelivery?.close(); aiPreviewDelivery = null
        cancelAiCommandVoice()
        aiCall?.cancel(); aiCall = null
        aiTaskRunnable?.let { aiWorker.remove(it) }; aiTaskRunnable = null
    }

    private fun bindSourcePaste(heading: View, title: View, text: TextView) {
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

    private fun bindCardLongPress(view: View, listener: View.OnLongClickListener) {
        view.setOnLongClickListener(listener)
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) bindCardLongPress(view.getChildAt(i), listener)
        }
    }

    private fun bindResultCopy(card: View, text: TextView) {
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

    private fun pasteSourceText(value: CharSequence): Boolean {
        if (!active || secret || !translation.enabled) return false
        val remaining = 5000 - translation.text.length
        var end = minOf(value.length, remaining)
        if (end > 0 && end < value.length && Character.isHighSurrogate(value[end - 1]) && Character.isLowSurrogate(value[end])) end--
        if (end <= 0) return false
        stopAiRequest(); clearComposition()
        translation.edit(translation.text + value.subSequence(0, end))
        aiEditingInstruction = false
        if (aiMode) renderBody(false) else updateTranslationPreview()
        refreshCandidates()
        return true
    }

    private fun updateAiPreview() {
        if(!translation.busy && aiConversation?.valid(System.currentTimeMillis())==false) resetAiConversation()
        aiCustomTab?.text=aiCustomPresetName.ifBlank { "自定义" }
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
        aiInstructionView?.text = if(aiCustom.isEmpty()) "点击输入自定义指令，例如：缩短到两句话" else aiCustom
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

    private var assistantSourceWeight = 0.35f

    private fun loadAssistantWidth() {
        val saved = getSharedPreferences("settings", MODE_PRIVATE).getFloat("assistantSourceWeight", 0.35f)
        assistantSourceWeight = if (saved.isFinite()) saved.coerceIn(0.2f, 0.8f) else 0.35f
    }

    private fun saveAssistantWidth() {
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putFloat("assistantSourceWeight", assistantSourceWeight).apply()
    }

    private fun addAssistantCards(row: LinearLayout, source: View, result: View) {
        val sourceParams = LinearLayout.LayoutParams(0, -1, assistantSourceWeight)
        val resultParams = LinearLayout.LayoutParams(0, -1, 1f - assistantSourceWeight)
        val handle = FrameLayout(this).apply {
            contentDescription = "左右拖动调整原文和结果宽度"
            isFocusable = true
            addView(View(this@OfflineKeyboardService).apply {
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

    private fun renderAiBar(column: LinearLayout) {
        val extra=translationExtraHeightPx()
        val area=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(4),dp(12),dp(4),0) }
        column.addView(area,LinearLayout.LayoutParams(-1,extra))
        val unit=minOf(dp(32),extra/5)
        fun compact(text: String, selected: Boolean=false, action:()->Unit): TextView = button(text,action).apply {
            textSize=12f; setPadding(dp(7),0,dp(7),0); setSingleLine(true)
            ellipsize=android.text.TextUtils.TruncateAt.END
            background=shape(if(selected) accent else keyBg,9); setTextColor(if(selected) enterText else fg)
        }
        val tasks=LinearLayout(this)
        area.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; isFillViewport=true; addView(tasks) },LinearLayout.LayoutParams(-1,unit))
        io.secure.keyboard.ai.AiWriting.tasks.forEach { (id,name) ->
            tasks.addView(compact(if(id=="custom") aiCustomPresetName.ifBlank { name } else name,aiTask==id) {
                stopAiRequest(); clearComposition(); if(aiTask!=id) resetAiConversation(); aiTask=id; aiEditingInstruction=false; translation.invalidate()
                getSharedPreferences("settings",MODE_PRIVATE).edit().putString("aiTask",id).apply(); renderBody(false); fillDefaultCustomAiPreset()
            }.also { if(id=="custom") aiCustomTab=it },LinearLayout.LayoutParams(dp(58),-1,1f).apply { rightMargin=dp(5) })
        }
        val options=LinearLayout(this).apply {
            visibility=if(aiTask=="custom") View.GONE else View.VISIBLE
        }
        area.addView(options,LinearLayout.LayoutParams(-1,unit).apply { topMargin=dp(5) })
        val toneButton=compact(if(aiTask=="translate") (translationLanguages[translation.target] ?: "英语") else "语气 · $aiTone") {}
        toneButton.setOnClickListener {
            PopupMenu(this,toneButton).apply {
                if(aiTask=="translate") translationLanguages.filter { it.key!="auto" }.forEach { (id,name) ->
                    menu.add(name).setOnMenuItemClickListener {
                        stopAiRequest(); if(translation.target!=id) resetAiConversation(); translation.target=id; settings.translationTarget=id; translation.invalidate(); renderBody(false); true
                    }
                } else io.secure.keyboard.ai.AiWriting.tones.forEach { tone ->
                    menu.add(tone).setOnMenuItemClickListener {
                        stopAiRequest(); if(aiTone!=tone) resetAiConversation(); aiTone=tone; translation.invalidate()
                        getSharedPreferences("settings",MODE_PRIVATE).edit().putString("aiTone",tone).apply(); renderBody(false); true
                    }
                }
                show()
            }
        }
        // Tone choices are meaningful for tone adjustment and AI translation.
        toneButton.visibility=if(aiTask in listOf("tone","translate")) View.VISIBLE else View.GONE
        options.addView(toneButton,LinearLayout.LayoutParams(0,-1,1f).apply { rightMargin=dp(6) })
        val modelButton=compact(if(aiModel.isEmpty()) "选择 Grok 模型" else "模型 · $aiModel") {}
        options.addView(modelButton,LinearLayout.LayoutParams(0,-1,2f))
        modelButton.setOnClickListener { showAiModels(modelButton) }
        aiInstructionView=null
        if(aiTask=="custom") {
            aiInstructionView=compact("",aiEditingInstruction) {
                clearComposition(); aiEditingInstruction=!aiEditingInstruction; renderBody(false)
            }
            aiInstructionView?.contentDescription="自定义指令，点击后用键盘输入"
            val instructionRow = LinearLayout(this).apply { background = shape(keyBg, 10) }
            instructionRow.addView(aiInstructionView, LinearLayout.LayoutParams(0, -1, 1f))
            val history = icon("hide", "选择自定义 AI") {}
            history.setOnClickListener { showCustomAiPresets(history) }
            instructionRow.addView(history, LinearLayout.LayoutParams(dp(44), -1))
            area.addView(instructionRow,LinearLayout.LayoutParams(-1,unit).apply { topMargin=dp(5) })
        }
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
                text.setOnTouchListener { _, event -> if(event.actionMasked==MotionEvent.ACTION_DOWN) {
                    if(aiEditingInstruction) { cancelAiCommandVoice(); clearComposition() }
                    aiEditingInstruction=false
                }; false }
                text.contentDescription="AI 原文，点击切换到原文输入"
            } else { translationResultView=text; bindResultCopy(card, text) }
            return card
        }
        addAssistantCards(cards, card(if(aiEditingInstruction) "原文 · 点击继续输入" else "原文",true), card("AI 结果",false))
        if(!aiEditingInstruction) translationTextView?.requestFocus()

        updateAiPreview()
    }

    private fun fillDefaultCustomAiPreset() {
        if (!aiMode || aiTask != "custom" || aiCustom.isNotEmpty() || aiEditingInstruction || !active || secret) return
        val view = aiInstructionView ?: return
        val store = content
        val epoch = store.customAiEpoch()
        worker.execute {
            val entries = readContent(emptyList<io.secure.keyboard.data.ContentStore.AiPreset>()) { store.aiPresets() }
            val first = entries.firstOrNull { it.id == settings.lastCustomAiPresetId } ?: entries.firstOrNull()
            main.post {
                if (first == null || content !== store || epoch != store.customAiEpoch() ||
                    aiInstructionView !== view || !active || secret || !aiMode || aiTask != "custom" ||
                    aiEditingInstruction || aiCustom.isNotEmpty()) return@post
                aiCustomPresetId = first.id
                aiCustomPresetName = first.name
                settings.lastCustomAiPresetId = first.id
                aiCustom = first.prompt
                aiCustomReasoning = first.reasoningEffort
                restoreAiConversation()
                translation.invalidate()
                updateAiPreview()
            }
        }
    }

    private fun showCustomAiPresets(anchor: View) {
        if (!aiMode || aiTask != "custom" || !active || secret) return
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
        window.setOnDismissListener {
            column.removeAllViews()
            if (popup === window) popup = null
        }
        column.addView(label("正在读取…", 13f, muted))
        window.showAsDropDown(anchor, anchor.width - width, dp(4))
        fun current() = popup === window && active && aiMode && aiTask == "custom" && anchor.isAttachedToWindow
        fun refresh() {
            val epoch = content.customAiEpoch()
            worker.execute {
                val entries = readContent(emptyList<io.secure.keyboard.data.ContentStore.AiPreset>()) { content.aiPresets() }
                main.post {
                    if (!current()) return@post
                    if (epoch != content.customAiEpoch()) { window.dismiss(); return@post }
                    column.removeAllViews()
                    if (entries.isEmpty()) column.addView(label("在 AI 设置 → 使用设置 → 自定义 AI 中添加提示词", 13f, muted), LinearLayout.LayoutParams(-1, dp(64)))
                    val selectedId = entries.firstOrNull { it.id == aiCustomPresetId && it.prompt == aiCustom }?.id
                        ?: entries.firstOrNull { it.prompt == aiCustom }?.id
                    entries.chunked(3).forEach { group ->
                        val row = LinearLayout(this)
                        group.forEachIndexed { index, preset ->
                            val selected = preset.id == selectedId
                            row.addView(button(preset.name) {
                                window.dismiss()
                                stopAiRequest(); clearComposition()
                                if(aiCustomPresetId!=preset.id || aiCustom!=preset.prompt || aiCustomReasoning!=preset.reasoningEffort) resetAiConversation()
                                aiCustomPresetId = preset.id
                                aiCustomPresetName = preset.name
                                settings.lastCustomAiPresetId = preset.id
                                aiCustom = preset.prompt; aiCustomReasoning = preset.reasoningEffort; aiEditingInstruction = false
                                translation.invalidate(); renderBody(false)
                            }.apply {
                                gravity = Gravity.CENTER
                                background = shape(if (selected) accent else special, 10)
                                setTextColor(if (selected) enterText else fg)
                                isSelected = selected
                                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                                textSize = 13f; contentDescription = "${if (selected) "已选择" else "选择"}自定义 AI：${preset.name}"
                            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (index < 2) rightMargin = dp(8) })
                        }
                        repeat(3 - group.size) { row.addView(View(this), LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (it < 2 - group.size) rightMargin = dp(8) }) }
                        column.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
                    }
                }
            }
        }
        refresh()
    }

    private fun showAiModels(anchor: View) {
        PopupMenu(this,anchor).apply {
            (listOf(aiModel)+aiModels).filter { it.isNotBlank() }.distinct().forEach { id ->
                menu.add(id).setOnMenuItemClickListener {
                    stopAiRequest(); if(aiModel!=id) resetAiConversation(); aiModel=id; translation.invalidate(); renderBody(false)
                    worker.execute {
                        runCatching {
                            val store=io.secure.keyboard.data.ApiConfigurationStore(this@OfflineKeyboardService)
                            store.save(store.load().copy(grokModel=id))
                        }.onFailure { main.post { if(!destroyed) toast("模型保存失败，请在API配置中重试") } }
                    }
                    true
                }
            }
            menu.add("获取可用模型").setOnMenuItemClickListener {
                val editor=voiceEditorGeneration
                toast("正在获取模型…")
                modelCatalogWorker.execute {
                    val result=runCatching {
                        io.secure.keyboard.data.GrokModelCatalog.fetch(io.secure.keyboard.data.ApiConfigurationStore(this@OfflineKeyboardService).load())
                    }
                    main.post {
                        if(destroyed || !aiMode || editor!=voiceEditorGeneration || !anchor.isAttachedToWindow) return@post
                        result.onSuccess { aiModels=it; if(it.isEmpty()) toast("没有可用模型") else showAiModels(anchor) }
                            .onFailure { toast("获取模型失败，请检查网络、地址与密钥") }
                    }
                }
                true
            }
            menu.add("AI 设置").setOnMenuItemClickListener {
                startActivity(Intent(this@OfflineKeyboardService,SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("page","api")); true
            }
            show()
        }
    }

    private fun aiType() = if(aiTask=="custom") "custom:${aiCustomPresetId.orEmpty()}" else aiTask

    private fun aiScope(config: io.secure.keyboard.data.ApiConfiguration): String =
        io.secure.keyboard.ai.AiConversation.scope(io.secure.keyboard.ai.AiWriting.contextKey(config),
            aiType(),
            io.secure.keyboard.ai.AiWriting.instruction(aiTask,aiTone,translationLanguages[translation.target] ?: "英语",aiCustom) + "\u0000reasoning:" + if(aiTask=="custom") aiCustomReasoning else "low")

    private fun resetAiConversation() {
        aiConversation=null
        aiConversationStore.clear()
        assistantSourceTitle?.text="原文"
    }

    private fun restoreAiConversation(): Long = restoreAiConversation(null)

    private fun restoreAiConversation(expectedScope: String?): Long {
        val (saved, revision)=aiConversationStore.snapshot()
        if (saved==null) { aiConversation=null; assistantSourceTitle?.text="原文"; return revision }
        val scope=expectedScope ?: runCatching { aiScope(aiConfiguration().copy(grokModel=aiModel)) }.getOrNull()
        if(scope==null || !saved.valid(System.currentTimeMillis(),scope)) {
            resetAiConversation()
            return aiConversationStore.revision()
        }
        aiConversation=saved; assistantSourceTitle?.text="续聊 ${saved.id.take(4)}"
        return revision
    }

    private fun requestAi() {
        if(!aiMode || !translation.enabled || !active || secret || translation.busy) return
        if(code.isNotEmpty()) { toast("请先确认候选词再生成"); return }
        val config=try { aiConfiguration().copy(grokModel=aiModel) }
            catch(_:Exception) { toast("无法读取API配置，请重新保存"); return }
        if(config.grokKey.isBlank() || aiModel.isBlank()) { toast("请先配置 Grok 密钥并选择模型"); return }
        val instruction=try { io.secure.keyboard.ai.AiWriting.instruction(aiTask,aiTone,translationLanguages[translation.target] ?: "英语",aiCustom) }
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
                connection===currentInputConnection && aiCall===call) {
                translation.partial(token, partial); updateAiPreview()
            }
        }
        aiPreviewDelivery = preview
        val task = Runnable {
            var failed=false
            var expired=false
            val result=try { call.execute(config,text,if(previousResponseId==null) instruction else "", preview::offer, custom = customRequest, previousResponseId = previousResponseId, reasoningEffort = reasoningEffort) }
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
    private fun clearAiConversation() {
        cancelAiCommandVoice()
        aiConversation=null
        aiResults.clear(); aiResultIndex=-1; aiResultNavigation=null
    }

    private fun selectAiResult(offset: Int) {
        if (translation.busy || aiResults.isEmpty()) return
        aiResultIndex=(aiResultIndex+offset).coerceIn(0,aiResults.lastIndex)
        val token=translation.begin()
        translation.complete(token,aiResults[aiResultIndex]); updateAiPreview()
    }

    private fun cancelAiCommandVoice() {
        if(assistantHeldVoice) spaceHoldTouch.revoke()
        assistantHeldVoice=false
        aiCommandEpoch++
        assistantVoicePanel?.let { (it.parent as? ViewGroup)?.removeView(it) }; assistantVoicePanel=null
        val oldAiVoice=aiCommandVoice; aiCommandVoice=null
        val oldOfflineVoice=inlineOfflineVoice; inlineOfflineVoice=null
        // Release can synchronously notify listeners; detach ownership first.
        oldAiVoice?.release(); oldOfflineVoice?.release()
    }

    private fun toggleAiCommandVoice() = startAssistantVoice(false)

    private fun startAssistantVoice(held: Boolean) {
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

    private var aiApplying=false
    private fun applyAiResult() {
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
                if(connection.commitText(result,1)) {
                    aiSelectionTarget.revoke(); updateAiPreview(); refreshCandidates()
                } else toast("输入框暂时无法接收文字，请重试")
            }
        }
    }

    private fun saveTranslationLanguages() {
        settings.translationSource = translation.source
        settings.translationTarget = translation.target
    }

    private fun fillTranslationFromSelection() {
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

    private fun updateTranslationPreview() {
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

    private fun renderTranslationBar(column: LinearLayout) {
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
                addView(ToolIcon(this@OfflineKeyboardService,"hide",muted,artworkSizeDp=14f),LinearLayout.LayoutParams(dp(18),-1))
                contentDescription = (if (source) "源语言：" else "目标语言：") + (translationLanguages[selected] ?: selected)
                setOnClickListener {
                    PopupMenu(this@OfflineKeyboardService, this).apply {
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
            addView(ToolIcon(this@OfflineKeyboardService,"arrow_right",muted,artworkSizeDp=13f),LinearLayout.LayoutParams(dp(18),dp(10)))
            addView(ToolIcon(this@OfflineKeyboardService,"arrow_left",muted,artworkSizeDp=13f),LinearLayout.LayoutParams(dp(18),dp(10)))
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

    private fun requestTranslation() {
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

    private fun renderKeys() {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(column, FrameLayout.LayoutParams(-1, -1))
        val rows =
            if (symbols) SymbolKeyLayout.rows(chinese, symbolPage)
            else listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        rows.forEachIndexed { i, row ->
            val symbolRow = symbols
            val symbolSpacer =
                if (symbolRow) SymbolKeyLayout.sideSpacerWeight(row.length, i == 2) else 0f
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
                if (profile.split && !outer && idx == row.length / 2)
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
                        if (!symbols) alternate(ch) else "",
                        { type(if (!symbolRow && shifted) ch.uppercase() else ch.toString(),
                            fromSymbolPage = symbolRow) },
                    )
                if (!symbols) letterKeyViews.add(letter to ch)
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
            if (chinese) "，。" else ". ,",
            9f,
            if (chinese) "。" else ",",
            { type(if (chinese) "，" else ".") },
        )
        addKey(bottom, if (chinese) "五笔" else "English", 42f, "", { space() }, isSpace = true)
        addKey(
            bottom,
            "◎",
            9f,
            "",
            {
                clearPending()
                chinese = !chinese
                settings.lastChineseLanguage = chinese
                symbols = false
                renderHeader()
                renderBody()
                refreshCandidates()
            },
        )
        addKey(bottom, "↵", 17f, "", { enter() })
    }

    private fun floatingHandle(column: LinearLayout) {
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

    private fun alternate(c: Char): String {
        val idx = "qwertyuiop".indexOf(c)
        if (idx >= 0) return (idx + 1).let { if (it == 10) "0" else "$it" }
        val map =
            if (chinese)
                mapOf(
                    'a' to "！",
                    's' to "？",
                    'd' to "、",
                    'f' to "；",
                    'g' to "：",
                    'h' to "（",
                    'j' to "）",
                    'k' to "“”",
                    'l' to "‘’",
                    'z' to "～",
                    'x' to "—",
                    'c' to "…",
                    'v' to "《》",
                    'b' to "【】",
                    'n' to "@",
                    'm' to "&",
                )
            else
                mapOf(
                    'a' to "@",
                    's' to "#",
                    'd' to "$",
                    'f' to "%",
                    'g' to "&",
                    'h' to "*",
                    'j' to "(",
                    'k' to ")",
                    'l' to "'",
                    'z' to "-",
                    'x' to "_",
                    'c' to "/",
                    'v' to ":",
                    'b' to ";",
                    'n' to "!",
                    'm' to "?",
                )
        return map[c] ?: ""
    }

    private fun updateShiftKeyFaces() {
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
    private fun keyShape(color: Int) = InsetDrawable(shape(color, 9), dp(2), dp(3), dp(2), dp(3))

    private fun addKey(
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
        container.setPadding(dp(2), dp(3), dp(2), dp(3))
        val specialKey = text in listOf("⇧", "⌫", "?123", "ABC", "=\\<", "↵", "◎", "，。", ". ,")
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
            )
        val key: View =
            when (text) {
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
                        this.text = alternatives.take(1)
                        inkCenterYFraction = 1f / 3f
                        textSize = 23f
                        setTextColor(ink)
                        setAutoSizeTextTypeUniformWithConfiguration(
                            9, 23, 1, android.util.TypedValue.COMPLEX_UNIT_SP,
                        )
                    }
                } else {
                    label(alternatives.take(1), 9f, muted).apply {
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
                choices = alternatives.map { it.toString() }
                // Password fields keep alternate-key input, but never magnify its text.
                // The hidden selector uses the same key anchor for the existing slide gesture.
                if (!secret) {
                    val r =
                        LinearLayout(this).apply {
                            setPadding(dp(4), dp(4), dp(4), dp(4))
                            background = shape(special)
                        }
                    popRow = r
                    choices.forEach { s ->
                        r.addView(
                            label(s, 22f).apply { background = shape(keyBg) },
                            LinearLayout.LayoutParams(dp(44), dp(44)),
                        )
                    }
                    popup =
                        PopupWindow(r, dp(choices.size * 44 + 8), dp(52), false).apply {
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
                            ((e.rawX - popupLocation[0] - dp(4)) / dp(44))
                                .toInt()
                                .coerceIn(0, choices.lastIndex)
                        for (i in 0 until (popRow?.childCount ?: 0)) popRow?.getChildAt(i)?.alpha =
                            if (i == choice) 1f else .5f
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

    private fun openTool(tool: String) {
        assistantBackspaceTapAt = 0L
        if(tool=="voice" && translation.enabled) { toggleAiCommandVoice(); return }
        if (translation.enabled && tool in assistantTools) {
            cancelTransient(); clearComposition()
            panel = if (panel == tool) "keys" else tool
            renderHeader(); renderBody(true)
            return
        }
        if (tool == "ai") {
            if (secret) { toast("密码输入框不支持 AI 助手"); return }
            val opening = !aiMode || !translation.enabled
            cancelTransient(); clearComposition(); stopAiRequest(); translation.close(); aiSelectionTarget.revoke()
            clearAiConversation(); aiMode = opening; aiEditingInstruction = false; aiCustom = ""; aiCustomReasoning = "low"
            aiCustomPresetName = ""
            if (opening) {
                translation.enabled = true
                aiModel = runCatching { aiConfiguration().grokModel }.getOrDefault("")
                aiTask = getSharedPreferences("settings",MODE_PRIVATE).getString("aiTask","polish")?.takeIf { it in io.secure.keyboard.ai.AiWriting.tasks } ?: "polish"
                aiTone = getSharedPreferences("settings",MODE_PRIVATE).getString("aiTone","自然") ?: "自然"
                translation.target = settings.translationTarget
                if(aiTask!="custom") restoreAiConversation()
            }
            panel="keys"; renderHeader(); renderBody()
            if(opening) { fillTranslationFromSelection(); fillDefaultCustomAiPreset() }
            return
        }
        if (aiMode) {
            stopAiRequest(); clearAiConversation(); aiMode=false; aiCustom=""; aiCustomPresetName=""; aiEditingInstruction=false; aiSelectionTarget.revoke(); translation.close()
        }
        if (tool == "translate") {
            if (secret) { toast("密码输入框不支持翻译"); return }
            cancelTransient(); clearComposition()
            val opening = !translation.enabled
            if (!opening) translation.close() else {
                translation.enabled = true
                translation.invalidate()
                translation.source = settings.translationSource.takeIf { it in translationLanguages } ?: "auto"
                translation.target = settings.translationTarget.takeIf { it in translationLanguages && it != "auto" } ?: "en"
            }
            panel = "keys"; renderHeader(); renderBody()
            if (opening) fillTranslationFromSelection()
            return
        }
        if (translation.enabled) translation.close()
        val previousPanel = panel
        cancelTransient()
        // Re-tapping an inline tool is the same safe exit as the left Back control.
        // Cancel before returning so voice cannot deliver a queued segment after closing.
        if (previousPanel == tool && tool in setOf("clipboard", "phrases", "voice", "quickedit", "emoji", "layout")) {
            panel = "keys"
            renderHeader()
            renderBody()
            return
        }
        when (tool) {
            "settings",
            "dictionary",
            "english_dictionary" -> {
                startActivity(
                    Intent(this, SettingsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra("page", tool)
                )
                return
            }
            "floating" -> {
                saveProfile(profile.copy(floating = !profile.floating))
                panel = "keys"
                createView()
                setInputView(root)
                return
            }
            "split" -> {
                if (outer) {
                    toast("分离布局仅在内屏启用")
                    return
                }
                saveProfile(profile.copy(split = !profile.split))
                panel = "keys"
                renderHeader()
                renderBody()
                return
            }
            "theme" -> {
                settings.theme = if (dark) "light" else "dark"
                createView()
                setInputView(root)
                return
            }
        }
        if (tool == "emoji") {
            val smileys = emojiGroups.indexOfFirst { it.first == "Smileys & Emotion" }
            emojiCategory = if (smileys >= 0) smileys + 1 else 1
            emojiCategoryScroll = null
        }
        panel = tool
        renderHeader()
        renderBody()
        if (tool == "voice") startVoice()
    }

    private fun renderMore() {
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val grid =
            GridLayout(this).apply {
                columnCount = 4
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
        scroll.addView(grid)
        body.addView(scroll)
        val pinnedTools = settings.toolbarPins.toSet()
        tools.filterKeys { it !in pinnedTools }.forEach { (id, name) ->
            val cell =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = shape(keyBg)
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                    KeyboardFeedback.disableAutomatic(this)
                    val feedback = installPressFeedback()
                    setOnClickListener {
                        feedback.click()
                        openTool(id)
                    }
                }
            cell.addView(icon(id, name) { openTool(id) }, LinearLayout.LayoutParams(dp(38), dp(38)))
            cell.addView(label(name, 12f), LinearLayout.LayoutParams(-1, dp(24)))
            grid.addView(
                cell,
                GridLayout.LayoutParams().apply {
                    width = 0
                    height = dp(84)
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(5), dp(5), dp(5), dp(5))
                },
            )
        }
    }

    private fun renderQuickEdit() {
        quickEditSelection.validate(quickEditConnection())
        val grid =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(3), dp(4), dp(4))
            }
        body.addView(grid, FrameLayout.LayoutParams(-1, -1))
        val labels = listOf("全选", "向上移动", "剪切", "向左移动", "选择", "向右移动", "复制", "向下移动", "粘贴")
        val actions =
            listOf<() -> Unit>(
                { quickEditAction(android.R.id.selectAll) },
                { quickEditArrow(KeyEvent.KEYCODE_DPAD_UP) },
                { quickEditAction(android.R.id.cut) },
                { quickEditArrow(KeyEvent.KEYCODE_DPAD_LEFT) },
                {
                    quickEditSelection.toggle(quickEditConnection())
                    renderBody()
                },
                { quickEditArrow(KeyEvent.KEYCODE_DPAD_RIGHT) },
                { quickEditAction(android.R.id.copy) },
                { quickEditArrow(KeyEvent.KEYCODE_DPAD_DOWN) },
                { quickEditAction(android.R.id.paste) },
            )
        for (r in 0..2) {
            val row = LinearLayout(this)
            grid.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
            for (c in 0..2) {
                val i = r * 3 + c
                val glyph =
                    mapOf(
                        1 to "arrow_up",
                        3 to "arrow_left",
                        5 to "arrow_right",
                        7 to "arrow_down",
                    )[i]
                val v =
                    if (glyph == null) button(labels[i], actions[i]).apply {
                        if (i == 4) {
                            isSelected = quickEditSelection.enabled
                            background = shape(if (isSelected) accent else keyBg)
                            setTextColor(if (isSelected) enterText else fg)
                            contentDescription = "选择"
                            if (Build.VERSION.SDK_INT >= 30)
                                stateDescription = if (isSelected) "已开启，再点退出" else "已关闭"
                            isEnabled = quickEditConnection() != null
                            if (!isEnabled) alpha = .4f
                            val feedback = pressFeedback()
                            val swipe = io.secure.keyboard.ui.SelectionKeySwipe(dp(18).toFloat()) { direction ->
                                if (isAttachedToWindow && panel == "quickedit") quickEditArrow(direction)
                            }
                            setOnClickListener { feedback.click(); actions[i]() }
                            setOnTouchListener { view, event ->
                                feedback.onTouch(event)
                                swipe.onTouch(view, event)
                            }
                        }
                    }
                    else
                        icon(glyph, labels[i], actions[i]).apply {
                            background = shape(keyBg)
                        }
                row.addView(
                    v,
                    LinearLayout.LayoutParams(0, -1, 1f).apply {
                        setMargins(dp(5), dp(5), dp(5), dp(5))
                    },
                )
            }
        }
    }

    private fun quickEditArrow(key: Int) {
        val wasSelecting = quickEditSelection.enabled
        quickEditSelection.move(quickEditConnection(), key)
        if (wasSelecting != quickEditSelection.enabled) renderBody()
    }

    private fun arrow(key: Int) {
        activeAssistantEditor()?.let { editor ->
            editor.onKeyDown(key,KeyEvent(KeyEvent.ACTION_DOWN,key))
            editor.onKeyUp(key,KeyEvent(KeyEvent.ACTION_UP,key))
            return
        }
        // The platform IME helper marks both events SOFT_KEYBOARD and KEEP_TOUCH_MODE.
        // Cursor swipes/backspace must not put the host into hardware-keyboard focus mode.
        sendDownUpKeyEvents(key)
    }

    private fun captureClipboard(newCopyEvent: Boolean = false) {
        if (!::clips.isInitialized || (!active && !newCopyEvent) || secret) return
        try {
            val clip = clips.primaryClip ?: return
            val text = ClipboardCapturePolicy.textForHistory(clip) ?: return
            val timestamp = clip.description.timestamp
            val epoch = content.clipboardGeneration()
            if (worker.isShutdown) return
            clipboardWrites.submit(text.length) {
                try {
                    val saved = content.captureClipboard(text, timestamp, newCopyEvent, epoch)
                    if (saved) main.post { if (active && panel == "clipboard") renderBody() }
                } catch (_: Exception) {
                    reportContentError()
                }
            }
        } catch (_: SecurityException) {
            /* Android blocks clipboard while this IME is not eligible. */
        } catch (_: Exception) {
            reportContentError()
        }
    }

    private var contentErrorShown = false

    private fun reportContentError() {
        main.post {
            if (active && !contentErrorShown) {
                contentErrorShown = true
                toast("本机保存内容无法读取或写入，可在设置中清除全部内容后重试")
            }
        }
    }

    private fun <T> readContent(fallback: T, action: () -> T): T =
        try {
            action()
        } catch (_: Exception) {
            reportContentError()
            fallback
        }

    private fun renderContent() {
        val isClipboard = panel == "clipboard"
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(column, FrameLayout.LayoutParams(-1, -1))
        if (isClipboard && secret) {
            // Do not read or render saved/system clipboard contents in a password context.
            // Explicit paste stays available and is performed by the host editor itself.
            column.addView(label("密码输入框不显示剪贴板内容", 14f, muted).apply {
                setPadding(dp(16), dp(24), dp(16), dp(16))
            })
            column.addView(button("粘贴") {
                currentInputConnection?.performContextMenuAction(android.R.id.paste)
            }, LinearLayout.LayoutParams(-1, dp(52)).apply {
                setMargins(dp(4), dp(3), dp(4), dp(3))
            })
            return
        }
        val loading = label("正在读取…", 14f, muted)
        column.addView(loading)
        val expectedPanel = panel
        fun current() = !destroyed && active && panel == expectedPanel &&
            body.indexOfChild(column) >= 0 && !(isClipboard && secret)
        // Parsing and the shared storage lock never block the input/main thread.
        contentQueries.submit {
            val entries = readContent(emptyList<ContentStore.ClipboardItem>()) {
                if (isClipboard) content.clipboardForDisplay()
                else content.phrasesForDisplay().map { ContentStore.ClipboardItem(it.id, it.text, it.pinned) }
            }
            main.post {
                if (!current()) return@post
                column.removeAllViews()
                if (entries.isEmpty()) {
                    column.addView(label(if (isClipboard) "复制的文字会保存在这里" else "在设置中添加常用语", 14f, muted))
                    return@post
                }
                var changing = false
                fun changeContent(action: () -> Unit) {
                    if (!current() || changing) return
                    changing = true
                    column.cancelPendingInputEvents()
                    column.removeAllViews()
                    column.addView(label("正在保存…", 14f, muted))
                    worker.execute {
                        readContent(Unit) { action() }
                        main.post { if (current()) renderBody() }
                    }
                }
                val list = ListView(this).apply {
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    divider = null
                    dividerHeight = dp(6)
                    setPadding(dp(10), 0, dp(10), 0)
                    clipToPadding = true
                }
                list.adapter = object : BaseAdapter() {
                    override fun getCount() = entries.size
                    override fun getItem(position: Int) = entries[position]
                    override fun getItemId(position: Int) = position.toLong()
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val entry = entries[position]
                        val insert: (String) -> Unit = { if (current() && !changing) { commit(it) } }
                        val pin: (Boolean) -> Unit = { pinned -> changeContent {
                            if (isClipboard) content.setClipboardPinned(entry.id, pinned)
                            else content.setPhrasePinned(entry.id, pinned)
                        } }
                        val delete: () -> Unit = { changeContent {
                            if (isClipboard) content.deleteClipboard(entry.id)
                            else content.deletePhrase(entry.id)
                        } }
                        return (convertView as? ContentEntryRow)?.apply {
                            bind(entry.text, entry.pinned, insert, pin, delete)
                        } ?: ContentEntryRow(
                            this@OfflineKeyboardService, entry.text, entry.pinned,
                            textColor = fg, surfaceColor = keyBg, actionColor = toolbarColor,
                            pinnedColor = accent,
                            deleteColor = if (dark) 0xFFFF7B83.toInt() else 0xFFC94A54.toInt(),
                            categoryLabel = if (isClipboard) "剪贴板记录" else "常用语",
                            onInsert = insert, onPin = pin, onDelete = delete,
                            feedback = { pressFeedback() },
                        ).apply { layoutParams = AbsListView.LayoutParams(-1, dp(52)) }
                    }
                }
                column.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
            }
        }
    }

    private fun renderEmoji() {
        val groups =
            listOf("Recent" to readContent(emptyList<String>()) { content.recentEmoji() }) +
                emojiGroups
        val selectedCategory = emojiCategory.coerceIn(0, (groups.size - 1).coerceAtLeast(0))
        val savedScroll = emojiCategoryScroll
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(column, FrameLayout.LayoutParams(-1, -1))
        val cats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val bar =
            object : HorizontalScrollView(this) {
                private var restoredScroll = false

                override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                    super.onLayout(changed, l, t, r, b)
                    if (restoredScroll) return
                    restoredScroll = true
                    scrollTo(savedScroll?.second ?: 0, 0)
                    // A category tap retains its viewport. A grid swipe reveals the newly
                    // selected shortcut only if it lies beyond that viewport.
                    if (savedScroll?.first != selectedCategory) {
                        val selected = cats.getChildAt(selectedCategory)
                        if (selected != null) {
                            when {
                                selected.left < scrollX -> scrollTo(selected.left, 0)
                                selected.right > scrollX + width ->
                                    scrollTo(selected.right - width, 0)
                            }
                        }
                    }
                    emojiCategoryScroll = selectedCategory to scrollX
                }

                override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
                    super.onScrollChanged(l, t, oldl, oldt)
                    if (restoredScroll && column.parent === body)
                        emojiCategoryScroll = selectedCategory to l
                }
            }.apply {
                isFillViewport = true
                isHorizontalScrollBarEnabled = false
                addView(cats, FrameLayout.LayoutParams(-2, -1))
            }
        column.addView(bar, LinearLayout.LayoutParams(-1, dp(40)))
        column.addView(
            View(this).apply { setBackgroundColor(dividerColor) },
            LinearLayout.LayoutParams(-1, dp(1)),
        )
        groups.forEachIndexed { i, g ->
            val categoryView =
                if (i == 0) ToolIcon(this, "recent", if (i == selectedCategory) accent else muted)
                else
                    label(
                        emojiIcons.getOrElse(i) { "☺" },
                        23f,
                        if (i == selectedCategory) accent else muted,
                    )
            cats.addView(
                categoryView.apply {
                    KeyboardFeedback.disableAutomatic(this)
                    minimumWidth = dp(42)
                    contentDescription =
                        listOf(
                                "最近使用",
                                "笑脸与情感",
                                "人物与身体",
                                "动物与自然",
                                "食物与饮料",
                                "旅行与地点",
                                "活动",
                                "物品",
                                "符号",
                                "旗帜",
                            )
                            .getOrElse(i) { g.first }
                    val feedback = installPressFeedback()
                    setOnClickListener {
                        feedback.click()
                        emojiCategory = i
                        renderBody()
                    }
                },
                LinearLayout.LayoutParams(dp(42), -1, 1f),
            )
        }
        val grid =
            EmojiGrid(this).apply {
                KeyboardFeedback.disableAutomatic(this)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                numColumns = GridView.AUTO_FIT
                columnWidth = dp(46)
                stretchMode = GridView.STRETCH_COLUMN_WIDTH
                verticalSpacing = dp(2)
            }
        column.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        val items = groups.getOrNull(selectedCategory)?.second ?: emptyList()
        grid.adapter =
            object : BaseAdapter() {
                override fun getCount() = items.size

                override fun getItem(position: Int) = items[position]

                override fun getItemId(position: Int) = position.toLong()

                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    (convertView as? TextView
                            ?: label("", 27f).apply {
                                layoutParams = AbsListView.LayoutParams(-1, dp(46))
                            })
                        .apply { text = items[position] }
            }
        val emojiFeedback = pressFeedback()
        grid.setOnItemClickListener { _, _, position, _ ->
            val emoji = items.getOrNull(position) ?: return@setOnItemClickListener
            emojiFeedback.click()
            commit(emoji)
            readContent(Unit) { content.useEmoji(emoji) }
        }
        var down = 0f
        var startY = 0f
        var swiping = false
        grid.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_CANCEL) emojiFeedback.onTouch(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (grid.pointToPosition(e.x.toInt(), e.y.toInt()) != AdapterView.INVALID_POSITION)
                        emojiFeedback.onTouch(e)
                    down = e.x
                    startY = e.y
                    swiping = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.x - down) > dp(40) && abs(e.x - down) > abs(e.y - startY) * 1.4f)
                        swiping = true
                    return@setOnTouchListener swiping
                }
                MotionEvent.ACTION_UP -> {
                    if (swiping) {
                        emojiFeedback.cancel()
                        emojiCategory =
                            (emojiCategory + if (e.x < down) 1 else -1).coerceIn(
                                0,
                                groups.lastIndex,
                            )
                        renderBody()
                        return@setOnTouchListener true
                    }
                    grid.performClick()
                }
            }
            false
        }
        if (items.isEmpty())
            column.addView(label("还没有最近使用的表情", 14f, muted), LinearLayout.LayoutParams(-1, dp(40)))
    }

    private fun renderLayout() {
        val scroll = ScrollView(this)
        val col =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(2), dp(12), dp(4))
            }
        scroll.addView(col)
        body.addView(scroll)
        col.addView(
            label("${if(outer)"外屏" else "内屏"} · ${if(landscape)"横屏" else "竖屏"}", 13f, muted)
        )
        fun slider(name: String, min: Int, max: Int, value: Int, change: (Int) -> Unit) {
            val title = label("$name  $value", 13f)
            col.addView(title)
            col.addView(
                SeekBar(this).apply {
                    this.max = max - min
                    progress = value - min
                    setOnSeekBarChangeListener(
                        object : SeekBar.OnSeekBarChangeListener {
                            override fun onStartTrackingTouch(b: SeekBar) {}

                            override fun onProgressChanged(b: SeekBar, p: Int, user: Boolean) {
                                if (user) {
                                    title.text = "$name  ${p+min}"
                                    change(p + min)
                                    applyLiveLayout()
                                }
                            }

                            override fun onStopTrackingTouch(b: SeekBar) {}
                        }
                    )
                },
                LinearLayout.LayoutParams(-1, dp(32)),
            )
        }
        if (profile.floating) {
            slider(
                "宽度",
                220,
                resources.configuration.screenWidthDp,
                profile.floatingWidthDp.coerceAtMost(resources.configuration.screenWidthDp),
            ) {
                saveProfile(profile.copy(floatingWidthDp = it))
            }
            slider("高度", 200, 500, profile.floatingHeightDp.coerceAtMost(500)) {
                saveProfile(profile.copy(floatingHeightDp = it))
            }
        } else {
            slider("高度", 200, 500, profile.heightDp.coerceAtMost(500)) {
                saveProfile(profile.copy(heightDp = it))
            }
            slider(
                "两侧留白",
                0,
                (resources.configuration.screenWidthDp - 220).coerceAtLeast(0) / 2,
                profile.sideGapDp,
            ) {
                saveProfile(profile.copy(sideGapDp = it))
            }
            slider("底部留白", 0, 160, profile.bottomGapDp.coerceAtMost(160)) {
                saveProfile(profile.copy(bottomGapDp = it))
            }
        }
    }

    /** Screen bounds independent of the IME height; exclude system bars, never the IME itself. */
    private fun floatingScreenHeightPx(): Int {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = getSystemService(WindowManager::class.java).maximumWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return (metrics.bounds.height() - insets.top - insets.bottom).coerceAtLeast(1)
        }
        val statusId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusHeight = if (statusId != 0) resources.getDimensionPixelSize(statusId) else 0
        return (resources.displayMetrics.heightPixels - statusHeight).coerceAtLeast(1)
    }

    private fun baseKeyboardHeightPx(): Int =
        dp(if (profile.floating) profile.floatingHeightDp else profile.heightDp)
            .coerceAtMost(resources.displayMetrics.heightPixels * 3 / 4)

    /** A temporary panel allowance, never written to the user's layout profile. */
    private fun translationExtraHeightPx(): Int =
        if (translation.enabled && (panel == "keys" || panel in assistantTools))
            minOf(dp(if (aiMode) 198 else 138), (floatingScreenHeightPx() - baseKeyboardHeightPx()).coerceAtLeast(0))
        else 0

    private fun applyLiveLayout() {
        val lp = box.layoutParams as FrameLayout.LayoutParams
        val usable = resources.displayMetrics.widthPixels
        val h = baseKeyboardHeightPx() + translationExtraHeightPx()
        // Match createView bounds: stored profiles may target a much larger display.
        val side = dp(profile.sideGapDp).coerceIn(0, (usable - dp(220)).coerceAtLeast(0) / 2)
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
        lp.height = h
        lp.width = width
        lp.leftMargin = left
        lp.bottomMargin = bottom
        box.layoutParams = lp
        // A rebuilt root can receive a theme/layout update before the IME window attaches it.
        root.layoutParams?.height = h + bottom
        root.requestLayout()
    }

    private fun renderVoice() {
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

    private fun updateVoiceLabels() {
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
    private fun cancelVoice() {
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
            if (active && ::body.isInitialized) {
                renderHeader()
                renderBody()
            }
        }
    }

    private fun beginHeldVoice(pointerId: Int, downVersion: Long) {
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

    private fun startHeldVoiceWhenKeysSettle() {
        if (!heldVoice || !heldVoiceWaitingKeys || heldVoiceFinishRequested ||
            !active || panel != "voice" || pendingConfirmation != null || queuedKeys.isNotEmpty()) return
        heldVoiceWaitingKeys = false
        startVoice()
    }

    /** A normal UP flushes the recognizer; a real CANCEL/editor change uses cancelVoice instead. */
    private fun finishHeldVoice() {
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

    private fun completeHeldVoiceIfDrained() {
        if (heldVoice && heldVoiceFinishRequested && heldVoiceSessionFinished &&
            !voiceStarting && !voiceValidationPending && voiceSegments.isEmpty()) {
            // Final callbacks and guarded commits finished. Only now may panel==keys invalidate
            // the voice target. Already-inserted endpoint segments are never undone.
            cancelVoice()
        }
    }

    /** Restart only after a successful host commit AND capture/network cleanup have finished. */
    private fun restartAiVoiceIfDrained() {
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

    private fun stopVoiceForEditorChange() {
        cancelVoice()
        voicePreview = ""
        voiceText?.text = "输入位置已改变，语音已停止"
        updateVoiceLabels()
    }

    /** Never call this synchronous host API from the IME/main thread (WebView can deadlock). */
    private fun readVoiceSelection(connection: InputConnection): Pair<Int, Int>? =
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
    private fun voiceRequestIsCurrent(request: Long, editor: Int) =
        !destroyed && voiceRequest == request && voiceEditorGeneration == editor

    private fun voiceTargetIsCurrent(request: Long, editor: Int, connection: InputConnection) =
        !destroyed && active && panel == "voice" && voiceRequest == request &&
            voiceEditorGeneration == editor && currentInputConnection === connection

    private fun startVoice() {
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

    private fun checkVoiceStart(request: Long, editor: Int, connection: InputConnection) {
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

    private fun createVoiceSession() = OfflineVoiceSession(this, voiceListener())

    private fun voiceListener() = object : OfflineVoiceSession.Listener {
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

    private fun failVoice(message: String) {
        val wasHeld = heldVoice
        cancelVoice()
        if (wasHeld) toast(message) else voiceText?.text = message
        updateVoiceLabels()
    }

    /** Only finalized segments enter this bounded queue; partial previews never reach the host. */
    private fun queueVoiceSegment(segmentId: Long, text: String) {
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

    private fun drainVoiceSegments() {
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
                val decision = guard.reserve(
                    segmentId, text.length, voiceEditorGeneration,
                    selection?.first ?: -1, selection?.second ?: -1,
                )
                when (decision) {
                    VoiceCommitGuard.Decision.STOP -> stopVoiceForEditorChange()
                    VoiceCommitGuard.Decision.IGNORE -> drainVoiceSegments()
                    VoiceCommitGuard.Decision.COMMIT -> {
                        val inserted = try { connection.commitText(text, 1) }
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
