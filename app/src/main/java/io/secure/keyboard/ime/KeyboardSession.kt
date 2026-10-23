package io.secure.keyboard.ime

import android.content.*
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.*
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.*
import java.util.concurrent.Executors
import kotlin.math.*
import org.json.JSONObject
import io.secure.keyboard.concurrent.BoundedTaskQueue
import io.secure.keyboard.concurrent.LatestTaskMailbox
import io.secure.keyboard.data.ContentStore
import io.secure.keyboard.data.DictionaryKind
import io.secure.keyboard.data.SettingsStore
import io.secure.keyboard.engine.OfflineEngine
import io.secure.keyboard.ui.KeyboardPressFeedback
import io.secure.keyboard.ui.CandidateStrip
import io.secure.keyboard.ui.CandidateGrid
import io.secure.keyboard.ui.KeyContainer
import io.secure.keyboard.ui.SpaceHoldTouchTracker
import io.secure.keyboard.ui.QuickEditSelection
import io.secure.keyboard.voice.ModelRepository
import io.secure.keyboard.voice.OfflineVoiceSession
import io.secure.keyboard.voice.VoiceCommitGuard

import io.secure.keyboard.*
import io.secure.keyboard.ime.KeyboardSession.QueuedKey
import io.secure.keyboard.ime.KeyboardSession.PendingConfirmation
import android.inputmethodservice.InputMethodService.Insets

/** Per-service composition root and lifecycle; feature controllers own behavior. */
internal class KeyboardSession(private val host: InputMethodService) : ContextWrapper(host) {

    fun sendDefaultEditorAction(fromEnterKey: Boolean) = host.sendDefaultEditorAction(fromEnterKey)
    fun sendDownUpKeyEvents(key: Int) = host.sendDownUpKeyEvents(key)
    internal val inputState = InputState()
    internal val pinyinComposition = io.secure.keyboard.engine.PinyinComposition()
    internal var pinyinPredictionGeneration: Int? = null
    internal var pinyinCandidates = emptyList<io.secure.keyboard.engine.PinyinDecoder.Option>()
    internal var pinyinContext = ""

    internal val currentInputConnection get() = host.currentInputConnection
    internal val currentInputEditorInfo get() = host.currentInputEditorInfo
    internal val window get() = host.window
    fun setInputView(view: View) = host.setInputView(view)
    fun requestHideSelf(flags: Int) = host.requestHideSelf(flags)
    internal val rootInitialized get() = ::root.isInitialized
    internal val headerInitialized get() = ::header.isInitialized
    internal val profileInitialized get() = ::profile.isInitialized
    internal val candidateStripInitialized get() = ::candidateStrip.isInitialized
    internal val clipsInitialized get() = ::clips.isInitialized
    internal val preeditInitialized get() = ::preedit.isInitialized
    internal val bodyInitialized get() = ::body.isInitialized
    internal val assistantAreaInitialized get() = ::assistantArea.isInitialized
    internal lateinit var settings: SettingsStore
    internal lateinit var content: ContentStore
    internal lateinit var engine: OfflineEngine
    internal val worker = Executors.newSingleThreadExecutor()
    internal val candidateQueries = LatestTaskMailbox(worker)
    internal var predictionSignal: CancellationSignal? = null
    internal fun cancelCandidateQueries() {
        predictionSignal?.cancel(); predictionSignal = null
        candidateQueries.clear()
    }
    internal val clipboardWrites = BoundedTaskQueue(worker, 32, 262_144)
    // Drain accepted tiny history writes on service destruction without retaining its views.
    internal val emojiWorker = java.util.concurrent.ThreadPoolExecutor(1, 1, 0L,
        java.util.concurrent.TimeUnit.MILLISECONDS, java.util.concurrent.ArrayBlockingQueue(64))
    internal val contentQueries = LatestTaskMailbox(worker)
    @Volatile internal var destroyed = false
    internal val main = Handler(Looper.getMainLooper())
    internal var loaded = false
    internal var dictionaryLoadFailed = false
    @Volatile internal var generation = 0
    internal var active = false
    internal var chinese by inputState::chinese
    internal var shifted by inputState::shifted
    internal var capsLocked by inputState::capsLocked
    internal var lastShiftTap by inputState::lastShiftTap
    internal var keyClickTime = 0L
    internal val letterKeyViews = mutableListOf<Pair<KeyContainer, Char>>()
    internal var shiftKeyView: KeyContainer? = null
    internal var symbols by inputState::symbols
    internal var symbolPage by inputState::symbolPage
    internal var code by inputState::code
    internal sealed class QueuedKey {
        data class Text(val value: String, val asCode: Boolean) : QueuedKey()
        data class EnglishPair(val opening: String, val closing: String, val moveBackUnits: Int) : QueuedKey()
        object Space : QueuedKey()
        object Enter : QueuedKey()
        object Backspace : QueuedKey()
        object RepeatedBackspace : QueuedKey()
    }
    internal data class PendingConfirmation(
        val code: String,
        val connection: InputConnection,
        val suffix: String?,
    )
    internal var pendingConfirmation by inputState::pendingConfirmation
    internal var queueReplayPosted by inputState::queueReplayPosted
    internal var englishConfirmation by inputState::englishConfirmation
    internal var spacingPending by inputState::spacingPending
    internal var spacingEpoch by inputState::spacingEpoch
    internal var spacingSelectionVersion by inputState::spacingSelectionVersion
    internal val spacingExpectedCursors by inputState::spacingExpectedCursors
    internal val spacingTexts by inputState::spacingTexts
    internal val queuedKeys by inputState::queuedKeys
    internal var queueSaturationNotified by inputState::queueSaturationNotified
    internal var candidatePager: io.secure.keyboard.engine.CandidatePager<io.secure.keyboard.engine.PinyinDecoder.Option>? = null
    internal var candidatePageLoading = false
    internal var candidateTexts = listOf<String>()
    internal var candidateRuntimeRevision: String? = null
    internal var candidateQuery = ""
    internal var candidateGeneration = -1
    internal var candidateConnection: InputConnection? = null
    internal var candidateDictionaryKind: DictionaryKind? = null
    internal var candidateDictionaryRevision: Long? = null
    internal var panel = "keys"
    internal val translation = io.secure.keyboard.translation.TranslationSession { stopTranslationRequest() }
    internal var translationCall: io.secure.keyboard.translation.GoogleTranslation.Call? = null
    internal var translationTask: Runnable? = null
    // Independent bounded lanes: canceled socket teardown cannot block the next request.
    internal val translationWorker = requestExecutor(2)
    internal val aiWorker = requestExecutor(2)

    fun requestExecutor(threads: Int) = with(KeyboardViewController) { requestExecutorImpl(threads) }

    internal var translationTextView: TextView? = null
    internal var translationResultView: TextView? = null
    internal var translationAction: TextView? = null
    internal var translationInsert: TextView? = null
    internal var aiConversation: io.secure.keyboard.ai.AiConversation? = null
    internal val aiConversationStore by lazy { io.secure.keyboard.data.AiConversationStore(this) }
    internal var aiDraftEpoch = 0L
    internal val aiResults = mutableListOf<String>()
    internal var aiResultIndex = -1
    internal var aiResultNavigation: TextView? = null
    internal var assistantSourceTitle: TextView? = null
    internal var aiCommandVoice: io.secure.keyboard.voice.AiVoiceSession? = null
    internal var aiCommandEpoch = 0
    internal var aiMode = false
    internal var aiTask = "polish"
    internal var aiTone = "自然"
    internal var aiCustom = ""
    internal var aiCustomReasoning = "low"
    internal var aiCustomPresetId: String? = null
    internal var aiCustomPresetName = ""
    internal var aiCustomTab: TextView? = null
    internal var aiCall: io.secure.keyboard.ai.AiWriting.Call? = null
    internal var aiCallFactory: () -> io.secure.keyboard.ai.AiWriting.Call = { io.secure.keyboard.ai.AiWriting.Call() }
    internal var aiConfiguration: () -> io.secure.keyboard.data.ApiConfiguration = { io.secure.keyboard.data.ApiConfigurationStore(this).load() }
    internal var aiTaskRunnable: Runnable? = null
    internal var aiPreviewDelivery: io.secure.keyboard.ai.LatestPreview? = null
    internal var aiCustomModel = ""
    internal var aiPresetLoadGeneration = 0
    internal var aiSelectionEpoch = 0
    internal val aiSelectionTarget = io.secure.keyboard.ai.AiSelectionTarget()
    internal val translationLanguages = linkedMapOf("auto" to "自动检测", "zh-CN" to "简体中文", "zh-TW" to "繁体中文",
        "en" to "英语", "ja" to "日语", "ko" to "韩语", "fr" to "法语", "de" to "德语", "es" to "西班牙语",
        "ru" to "俄语", "pt" to "葡萄牙语", "ar" to "阿拉伯语", "th" to "泰语", "vi" to "越南语", "id" to "印尼语")
    internal val quickEditSelection = QuickEditSelection()
    internal var emojiCategory = 1
    internal var emojiCategoryScroll: Pair<Int, Int>? = null
    internal var secret by inputState::secret
    internal var personalizedLearning = true
    internal lateinit var root: FrameLayout
    internal lateinit var box: LinearLayout
    internal lateinit var header: LinearLayout
    internal lateinit var assistantArea: LinearLayout
    internal lateinit var candidateStrip: CandidateStrip
    internal var candidateGrid: CandidateGrid? = null
    internal var candidatesExpanded = false
    internal lateinit var candidateGroup: LinearLayout
    internal lateinit var toolbarGroup: LinearLayout
    internal lateinit var hideIcon: View
    private val traditionalConverter by lazy {
        io.secure.keyboard.data.TraditionalConverter.shared {
        io.secure.keyboard.data.TraditionalConverter(sequence {
            for (name in listOf("STCharacters.txt", "STPhrases.txt")) {
                assets.open("opencc/$name").bufferedReader().use { reader ->
                    for (line in reader.lineSequence()) yield(line)
                }
            }
        })
        }
    }

    internal fun outputText(value: String): String =
        if (settings.traditionalOutput && !secret) traditionalConverter.convert(value) else value

    internal val quickPasteState = QuickPasteState()
    internal val quickPasteExpiry = object : Runnable {
        override fun run() {
            if (quickPasteState.expireIfNeeded()) {
                if (active && headerInitialized) renderHeader()
            } else if (active && quickPasteState.text != null) {
                main.postDelayed(this, quickPasteState.remainingMillis)
            }
        }
    }

    internal fun dismissQuickPasteOnInput() {
        if (quickPasteState.text == null) return
        quickPasteState.clear()
        renderHeader()
    }

    private fun prepareQuickPaste() {
        main.removeCallbacks(quickPasteExpiry)
        if (secret || !settings.quickPaste) { quickPasteState.clear(); return }
        val clip = try { clips.primaryClip } catch (_: SecurityException) { null }
        val token = clip?.description?.timestamp ?: 0L
        val text = if (!secret && settings.quickPaste && clip != null)
            io.secure.keyboard.data.ClipboardCapturePolicy.textForHistory(clip) else null
        quickPasteState.open(token, text, settings.quickPasteConsumedToken) {
            settings.quickPasteConsumedToken = it
        }
        if (quickPasteState.text != null) main.postDelayed(quickPasteExpiry, quickPasteState.remainingMillis)
    }

    internal var toolbarSignature: Pair<String, List<String>>? = null
    internal var headerShowsCandidates: Boolean? = null
    internal lateinit var body: FrameLayout
    internal lateinit var preedit: TextView
    internal var voice: OfflineVoiceSession? = null
    internal var voicePreview = ""
    internal var aiVoice: io.secure.keyboard.voice.AiVoiceSession? = null
    internal var usingAiVoice = false
    internal var aiVoiceRoundCommitted = false
    internal var aiVoiceRestart: Runnable? = null
    internal var voiceState = OfflineVoiceSession.State.IDLE
    internal var voiceText: TextView? = null
    internal var voiceStatus: TextView? = null
    internal var voiceWaveform: io.secure.keyboard.ui.VoiceWaveform? = null
    internal val voiceEditorWorker = Executors.newSingleThreadExecutor()
    internal val voiceSelectionChecks = LatestTaskMailbox(voiceEditorWorker)
    @Volatile internal var voiceEditorGeneration = 0
    @Volatile internal var voiceRequest = 0L
    internal var voiceStarting = false
    internal var voiceGuard: VoiceCommitGuard? = null
    internal var voiceConnection: InputConnection? = null
    internal val voiceSegments = ArrayDeque<Pair<Long, String>>()
    internal var voiceValidationPending = false
    internal var heldVoice = false
    internal var heldVoiceWaitingKeys = false
    internal var heldVoiceFinishRequested = false
    internal var heldVoiceSessionFinished = false
    internal var assistantHeldVoice = false
    internal val spaceHoldTouch = SpaceHoldTouchTracker(
        onRelease = {
            if(assistantHeldVoice) { aiCommandVoice?.stop(); inlineOfflineVoice?.stop() }
            else finishHeldVoice()
        },
        onCancel = { if(assistantHeldVoice) cancelAiCommandVoice() else cancelVoice() },
    )
    internal var popup: PopupWindow? = null
    internal var dark = false
    internal var bg = 0
    internal var keyBg = 0
    internal var fg = 0
    internal var muted = 0
    internal var accent = 0
    internal var special = 0
    internal var toolbarColor = 0
    internal var candidateColor = 0
    internal var selectedCandidateColor = 0
    internal var preeditColor = 0
    internal var dividerColor = 0
    internal var specialText = 0
    internal var selectedKeyBg = 0
    internal var selectedKeyText = 0
    internal var enterText = 0
    private val refreshLetterSymbols = Runnable { if (active && !symbols) renderBody() }
    private val refreshTheme = Runnable {
        if (active) {
            cancelTransient()
            createView()
            setInputView(root)
            refreshCandidates()
        }
    }
    private var glossarySelectionStart = -1
    private var glossarySelectionEnd = -1
    private var glossaryExpectedCursors: java.util.ArrayDeque<Int>? = null
    internal var spaceGlossary: io.secure.keyboard.learning.SpaceGlossary? = null
    internal var spaceGlossLabel: android.widget.TextView? = null

    internal fun spaceDefaultLabel() = if (!chinese) "English" else if (settings.chineseInputMode == io.secure.keyboard.engine.ChineseInputMode.PINYIN) "拼音" else "五笔"

    internal fun updateSpaceGlossLabel() {
        spaceGlossLabel?.text = spaceGlossary?.text ?: spaceDefaultLabel()
    }

    internal fun releaseSpaceGlossary() {
        glossaryExpectedCursors = null
        spaceGlossary?.close(); spaceGlossary = null
        updateSpaceGlossLabel()
    }

    internal fun spaceGlossaryCommitted(units: Int, eligible: Boolean, cursor: Int? = null, committed: String? = null) {
        if (!settings.spaceEnglishGloss || !active || destroyed || secret || !personalizedLearning || !chinese || translation.enabled) return
        val previous = glossaryExpectedCursors?.peekLast() ?: minOf(glossarySelectionStart, glossarySelectionEnd)
        val expected = cursor ?: previous.takeIf { it >= 0 }?.plus(units)
        if (expected != null) {
            val cursors = glossaryExpectedCursors ?: java.util.ArrayDeque<Int>().also { glossaryExpectedCursors = it }
            if (cursors.size >= 32) { releaseSpaceGlossary(); return }
            cursors.addLast(expected)
        }
        if (!eligible) { spaceGlossary?.close(); spaceGlossary = null; updateSpaceGlossLabel(); return }
        val connection = currentInputConnection ?: return
        val current = spaceGlossary ?: io.secure.keyboard.learning.SpaceGlossary(main,
            io.secure.keyboard.learning.SpaceGlossary.SqliteBackend(this), display = { _ ->
            if (active && !destroyed && !secret && personalizedLearning && chinese && !translation.enabled && settings.spaceEnglishGloss) updateSpaceGlossLabel()
            else releaseSpaceGlossary()
        }).also { spaceGlossary = it }
        current.queryBeforeCursor(committed) { connection.getTextBeforeCursor(4, 0) }
    }

    internal val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key?.startsWith("themeTokens_") == true || key?.startsWith("keyDesign_") == true || key == "theme") {
                // A single reset can notify once per token. Rebuild once with the final values.
                main.removeCallbacks(refreshTheme)
                main.post(refreshTheme)
            }
            else if (key?.startsWith("symbol_keyboard_") == true) main.post {
                if (active && symbols) renderBody()
            }
            else if (key?.startsWith("letter_symbols_") == true) {
                // Restoring one language may remove 26 overrides in the same preference edit.
                main.removeCallbacks(refreshLetterSymbols)
                main.post(refreshLetterSymbols)
            }
            else if (key == "clipboard_clear_revision") main.post {
                quickPasteState.clear()
                if (active) renderHeader()
            }
            else if (key == "quickPaste") main.post {
                if (!settings.quickPaste) quickPasteState.clear()
                if (active) renderHeader()
            }
            else if (key == "spaceEnglishGloss" && !settings.spaceEnglishGloss) {
                // Revoke this generation immediately, even if the switch is turned back on.
                if (Looper.myLooper() == main.looper) releaseSpaceGlossary()
                else main.post { releaseSpaceGlossary() }
            }
            else if (key == "chineseInputMode") main.post {
                releaseSpaceGlossary()
                clearPending(); pinyinContext = ""
                if (active) { renderBody(); refreshCandidates() }
            }
            else if (key == "toolbarPins" || key == "traditionalOutput") main.post {
                if (active) { renderHeader(); if (panel == "more") renderBody() }
            }
            else if (key == "builtin_dictionary_revision") main.post {
                generation++
                cancelCandidateQueries()
                clearCandidates()
                if (active) { renderHeader(); refreshCandidates() }
            }
            else if (key == "fuzzyPairs" || key == "fuzzyPinyin" || key == "englishCompletion" || key == "emojiShortcuts")
                main.post { if (active) refreshCandidates() }
        }
    internal lateinit var profile: SettingsStore.LayoutProfile
    internal var outer = true
    internal var landscape = false
    internal lateinit var clips: ClipboardManager
    internal val clipListener =
        ClipboardManager.OnPrimaryClipChangedListener { captureClipboard(true) }
    internal val tools =
        linkedMapOf(
            "input_mode" to "输入模式",
            "script" to "简繁转换",
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
    internal val emojiGroups = mutableListOf<Pair<String, List<String>>>()
    internal val emojiIcons = listOf("◷", "☺", "☝", "🐾", "🍴", "🚗", "⚽", "💡", "♡", "⚑")

    fun onCreate() {
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
                    if (k != "Component") {
                        groups.add(k to (0 until a.length()).map { a.getString(it) })
                    }
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

    fun onEvaluateFullscreenMode() = false

    fun onCreateInputView(): View {
        createView()
        return root
    }

    fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        val restoreKeysAfterPanel = active && (candidatesExpanded || panel != "keys" ||
            (::body.isInitialized && body.childCount == 0) || translation.enabled)
        val retainedSpaceLabel = spaceGlossLabel.takeIf { active && !restoreKeysAfterPanel }
        voiceEditorGeneration++
        releaseFinishedInputPresentation()
        clearPending()
        // An editor restart must not carry a held key, popup, or voice preview into its new target.
        cancelTransient()
        panel = "keys"
        val entry = editorEntry(attribute)
        glossarySelectionStart = attribute?.initialSelStart ?: -1
        glossarySelectionEnd = attribute?.initialSelEnd ?: -1
        secret = entry.secret
        personalizedLearning = EditorEntryPolicy.allowsPersonalizedLearning(attribute?.imeOptions ?: 0)
        // Only an opening/new editor applies the field default. A live restart preserves any
        // manual language or symbol-page change made after the keyboard became visible.
        if (!restarting || !active) applyEditorEntry(entry)
        // Restart can keep the current input view alive. Releasing a non-key panel or grid
        // must not leave an empty body before (or without) a subsequent onStartInputView.
        if (restoreKeysAfterPanel && ::body.isInitialized) {
            renderHeader()
            renderBody()
        } else if (retainedSpaceLabel != null) {
            // A live editor restart may retain the ordinary key views without onStartInputView.
            spaceGlossLabel = retainedSpaceLabel
            updateSpaceGlossLabel()
        }
    }

    fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        releaseSpaceGlossary()
        val entry = editorEntry(info)
        secret = entry.secret
        personalizedLearning = EditorEntryPolicy.allowsPersonalizedLearning(info?.imeOptions ?: 0)
        if (!restarting || !active) applyEditorEntry(entry)
        quickEditSelection.reset()
        if (!active) prepareQuickPaste()
        active = true
        // Mode/privacy state is final before constructing or attaching the first visible root.
        createView()
        setInputView(root)
        captureClipboard()
        refreshCandidates()
    }

    fun editorEntry(info: EditorInfo?) = with(KeyboardViewController) { editorEntryImpl(info) }

    fun applyEditorEntry(entry: EditorEntryPolicy.Entry) = with(KeyboardViewController) { applyEditorEntryImpl(entry) }

    fun onFinishInputView(finishingInput: Boolean) {
        active = false
        voiceEditorGeneration++
        releaseFinishedInputPresentation()
        cancelTransient()
        clearPending()
    }

    fun onFinishInput() {
        active = false
        voiceEditorGeneration++
        releaseFinishedInputPresentation()
        quickEditSelection.reset()
        cancelTransient()
        clearPending()
    }

    fun onConfigurationChanged(newConfig: Configuration) {
        cancelTransient()
        if (active) {
            createView()
            setInputView(root)
            refreshCandidates()
        }
    }

    fun onDestroy() {
        stopAiRequest()
        translation.close()
        translationWorker.shutdownNow()
        aiWorker.shutdownNow()
        destroyed = true
        active = false
        clipboardWrites.close()
        emojiWorker.shutdown()
        contentQueries.close()
        cancelCandidateQueries()
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
    }

    fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        glossarySelectionStart = newSelStart
        glossarySelectionEnd = newSelEnd
        val moved = oldSelStart != newSelStart || oldSelEnd != newSelEnd
        if (moved) {
            val pending = glossaryExpectedCursors
            if (newSelStart == newSelEnd && pending?.contains(newSelStart) == true) {
                while (!pending.isEmpty() && pending.removeFirst() != newSelStart) { }
            } else releaseSpaceGlossary()
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
        if (active && !secret && (!chinese || (moved && (settings.chineseInputMode == io.secure.keyboard.engine.ChineseInputMode.PINYIN || pinyinContext.isNotEmpty())))) refreshCandidates()
    }

    fun cancelTransient() = with(KeyboardViewController) { cancelTransientImpl() }

    fun dp(v: Int) = with(KeyboardViewController) { dpImpl(v) }

    fun pxToDp(v: Float) : Int = with(KeyboardViewController) { pxToDpImpl(v) }

    fun toast(s: String) = with(KeyboardViewController) { toastImpl(s) }

    fun palette() = with(KeyboardViewController) { paletteImpl() }

    fun shape(color: Int, radius: Int = 12) = with(KeyboardViewController) { shapeImpl(color, radius) }

    fun label(text: String, size: Float = 16f, color: Int = fg) = with(KeyboardViewController) { labelImpl(text, size, color) }

    fun button(text: String, action: () -> Unit) : TextView = with(KeyboardViewController) { buttonImpl(text, action) }

    fun toolIsSelected(name: String) : Boolean = with(KeyboardViewController) { toolIsSelectedImpl(name) }

    fun icon(name: String, description: String, action: () -> Unit) = with(KeyboardViewController) { iconImpl(name, description, action) }

    fun loadProfile() = with(KeyboardViewController) { loadProfileImpl() }

    fun saveProfile(p: SettingsStore.LayoutProfile) = with(KeyboardViewController) { saveProfileImpl(p) }

    fun createView() = with(KeyboardViewController) { createViewImpl() }

    fun onComputeInsets(outInsets: Insets) {
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

    fun createHeaderContents() = with(KeyboardViewController) { createHeaderContentsImpl() }

    fun showsCandidateMode() = with(KeyboardViewController) { showsCandidateModeImpl() }

    fun renderHeader() = with(KeyboardViewController) { renderHeaderImpl() }

    internal val assistantTools = setOf("clipboard", "phrases", "emoji", "quickedit")

    fun renderBody() = with(KeyboardViewController) { renderBodyImpl() }

    fun renderBody(preserveAssistant: Boolean) = with(KeyboardViewController) { renderBodyImpl(preserveAssistant) }

    fun renderKeyboardBody() = with(KeyboardViewController) { renderKeyboardBodyImpl() }

    fun renderCandidateGrid() = with(KeyboardViewController) { renderCandidateGridImpl() }

    fun clearPending() = with(InputController) { clearPendingImpl() }

    fun clearComposition() = with(InputController) { clearCompositionImpl() }

    fun releaseFinishedInputPresentation() = with(InputController) { releaseFinishedInputPresentationImpl() }

    fun clearCandidates() = with(CandidateController) { clearCandidatesImpl() }

    fun englishPrefix(connection: InputConnection?) : String = with(CandidateController) { englishPrefixImpl(connection) }

    fun readEnglishPrefix(connection: InputConnection?) : String = with(CandidateController) { readEnglishPrefixImpl(connection) }

    fun assistantEnglishPrefix(editor: EditText) : String = with(CandidateController) { assistantEnglishPrefixImpl(editor) }

    fun refreshCandidates() = with(CandidateController) { refreshCandidatesImpl() }

    fun candidateQueryFailed(seq: Int, zh: Boolean, connection: InputConnection?) = with(CandidateController) { candidateQueryFailedImpl(seq, zh, connection) }

    fun choose(value: String) : Boolean = with(InputController) { chooseImpl(value) }

    fun choose(value: String, feedback: () -> Unit) : Boolean = with(InputController) { chooseImpl(value, feedback) }

    @JvmOverloads
    fun commitInputText(value: String, glossary: Boolean = false) : Boolean = with(InputController) { commitInputTextImpl(value, glossary) }

    fun commitNextSpacedText() = with(InputController) { commitNextSpacedTextImpl() }

    fun commit(value: String) = with(InputController) { commitImpl(value) }

    fun type(s: String, fromSymbolPage: Boolean = false) = with(InputController) { typeImpl(s, fromSymbolPage) }

    fun dispatchKey(key: QueuedKey) = with(InputController) { dispatchKeyImpl(key) }

    fun applyKey(key: QueuedKey) = with(InputController) { applyKeyImpl(key) }

    fun applyEnglishPair(opening: String, closing: String, moveBackUnits: Int) = with(InputController) { applyEnglishPairImpl(opening, closing, moveBackUnits) }

    fun applyText(s: String, asCode: Boolean) = with(InputController) { applyTextImpl(s, asCode) }

    fun confirmChinese(suffix: String?) : Boolean = with(InputController) { confirmChineseImpl(suffix) }

    fun finishPendingConfirmation() = with(InputController) { finishPendingConfirmationImpl() }

    fun releasePendingConfirmation() = with(InputController) { releasePendingConfirmationImpl() }

    fun replayQueuedKeys() = with(InputController) { replayQueuedKeysImpl() }

    fun backspace() = with(InputController) { backspaceImpl() }

    internal var assistantBackspaceTapAt = 0L

    fun applyBackspace() = with(InputController) { applyBackspaceImpl() }

    fun performBackspace(tap: Boolean) = with(InputController) { performBackspaceImpl(tap) }

    fun moveCursor(direction: Int) = with(InputController) { moveCursorImpl(direction) }

    fun space() = with(InputController) { spaceImpl() }

    fun applySpace() = with(InputController) { applySpaceImpl() }

    fun enter() = with(InputController) { enterImpl() }

    fun applyEnter() = with(InputController) { applyEnterImpl() }

    fun pressFeedback() = with(KeyboardViewController) { pressFeedbackImpl() }

    fun View.installPressFeedback(): KeyboardPressFeedback = pressFeedback().also { feedback ->
        setOnTouchListener { _, event -> feedback.onTouch(event); false }
    }

    fun keyFeedback(vibrate: Boolean = true) = with(KeyboardViewController) { keyFeedbackImpl(vibrate) }

    internal var syncingAssistantEditor = false
    internal var sourceCursor = -1
    internal var assistantVoicePanel: View? = null
    internal var inlineOfflineVoice: OfflineVoiceSession? = null
    fun activeAssistantEditor() : EditText? = with(AssistantController) { activeAssistantEditorImpl() }

    fun syncAssistantText(view: TextView?, value: String) = with(AssistantController) { syncAssistantTextImpl(view, value) }

    fun assistantEditor() : EditText = with(AssistantController) { assistantEditorImpl() }

    internal var assistantResultTitle: TextView? = null

    fun clearAssistantSource() = with(AssistantController) { clearAssistantSourceImpl() }

    fun insertAssistantResult() = with(AssistantController) { insertAssistantResultImpl() }

    internal var assistantEditConnection: android.view.inputmethod.InputConnection? = null

    fun quickEditConnection() : android.view.inputmethod.InputConnection? = with(AssistantController) { quickEditConnectionImpl() }

    fun quickEditAction(action: Int) = with(AssistantController) { quickEditActionImpl(action) }

    fun releaseAssistantViews() = with(AssistantController) { releaseAssistantViewsImpl() }

    fun stopTranslationRequest() = with(AssistantController) { stopTranslationRequestImpl() }

    fun stopAiRequest() = with(AssistantController) { stopAiRequestImpl() }

    fun bindSourcePaste(heading: View, title: View, text: TextView) = with(AssistantController) { bindSourcePasteImpl(heading, title, text) }

    fun bindCardLongPress(view: View, listener: View.OnLongClickListener) = with(AssistantController) { bindCardLongPressImpl(view, listener) }

    fun bindResultCopy(card: View, text: TextView) = with(AssistantController) { bindResultCopyImpl(card, text) }

    fun pasteSourceText(value: CharSequence) : Boolean = with(AssistantController) { pasteSourceTextImpl(value) }

    fun updateAiPreview() = with(AssistantController) { updateAiPreviewImpl() }

    internal var assistantSourceWeight = 0.35f

    fun loadAssistantWidth() = with(AssistantController) { loadAssistantWidthImpl() }

    fun saveAssistantWidth() = with(AssistantController) { saveAssistantWidthImpl() }

    fun addAssistantCards(row: LinearLayout, source: View, result: View) = with(AssistantController) { addAssistantCardsImpl(row, source, result) }

    fun renderAiBar(column: LinearLayout) = with(AssistantController) { renderAiBarImpl(column) }

    fun fillDefaultCustomAiPreset() = with(AssistantController) { fillDefaultCustomAiPresetImpl() }

    fun showCustomAiPresets(anchor: View) = with(AssistantController) { showCustomAiPresetsImpl(anchor) }

    fun effectiveAiConfiguration() = with(AssistantController) { effectiveAiConfigurationImpl() }

    fun aiType() = with(AssistantController) { aiTypeImpl() }

    fun aiScope(config: io.secure.keyboard.data.ApiConfiguration) : String = with(AssistantController) { aiScopeImpl(config) }

    fun resetAiConversation() = with(AssistantController) { resetAiConversationImpl() }

    fun restoreAiConversation() : Long = with(AssistantController) { restoreAiConversationImpl() }

    fun restoreAiConversation(expectedScope: String?) : Long = with(AssistantController) { restoreAiConversationImpl(expectedScope) }

    fun requestAi() = with(AssistantController) { requestAiImpl() }

    fun clearAiConversation() = with(AssistantController) { clearAiConversationImpl() }

    fun selectAiResult(offset: Int) = with(AssistantController) { selectAiResultImpl(offset) }

    fun cancelAiCommandVoice() = with(AssistantController) { cancelAiCommandVoiceImpl() }

    fun toggleAiCommandVoice() = with(AssistantController) { toggleAiCommandVoiceImpl() }

    fun startAssistantVoice(held: Boolean) = with(AssistantController) { startAssistantVoiceImpl(held) }

    internal var aiApplying=false
    fun applyAiResult() = with(AssistantController) { applyAiResultImpl() }

    fun saveTranslationLanguages() = with(AssistantController) { saveTranslationLanguagesImpl() }

    fun fillTranslationFromSelection() = with(AssistantController) { fillTranslationFromSelectionImpl() }

    fun updateTranslationPreview() = with(AssistantController) { updateTranslationPreviewImpl() }

    fun renderTranslationBar(column: LinearLayout) = with(AssistantController) { renderTranslationBarImpl(column) }

    fun requestTranslation() = with(AssistantController) { requestTranslationImpl() }

    fun renderKeys() = with(KeyboardViewController) { renderKeysImpl() }

    fun floatingHandle(column: LinearLayout) = with(KeyboardViewController) { floatingHandleImpl(column) }

    fun alternate(c: Char) : String = with(KeyboardViewController) { alternateImpl(c) }

    fun updateShiftKeyFaces() = with(KeyboardViewController) { updateShiftKeyFacesImpl() }

    fun keyShape(color: Int, enter: Boolean = false) = with(KeyboardViewController) { keyShapeImpl(color, enter) }

    fun addKey(
        row: LinearLayout,
        text: String,
        weight: Float,
        alternatives: String,
        action: () -> Unit,
        delete: Boolean = false,
        isSpace: Boolean = false,
    ) : KeyContainer = with(KeyboardViewController) { addKeyImpl(row, text, weight, alternatives, action, delete, isSpace) }

    fun openTool(tool: String) = with(PanelController) { openToolImpl(tool) }

    fun renderMore() = with(PanelController) { renderMoreImpl() }

    fun renderQuickEdit() = with(PanelController) { renderQuickEditImpl() }

    fun quickEditArrow(key: Int) = with(PanelController) { quickEditArrowImpl(key) }

    fun arrow(key: Int) = with(PanelController) { arrowImpl(key) }

    fun captureClipboard(newCopyEvent: Boolean = false) = with(PanelController) { captureClipboardImpl(newCopyEvent) }

    internal var contentErrorShown = false

    fun reportContentError() = with(PanelController) { reportContentErrorImpl() }

    fun <T> readContent(fallback: T, action: () -> T): T =
        try {
            action()
        } catch (_: Exception) {
            reportContentError()
            fallback
        }

    fun renderContent() = with(PanelController) { renderContentImpl() }

    fun renderEmoji() = with(PanelController) { renderEmojiImpl() }

    fun renderLayout() = with(PanelController) { renderLayoutImpl() }

    fun floatingScreenHeightPx() : Int = with(PanelController) { floatingScreenHeightPxImpl() }

    fun baseKeyboardHeightPx() : Int = with(PanelController) { baseKeyboardHeightPxImpl() }

    fun translationExtraHeightPx() : Int = with(PanelController) { translationExtraHeightPxImpl() }

    fun applyLiveLayout() = with(PanelController) { applyLiveLayoutImpl() }

    fun renderVoice() = with(VoiceController) { renderVoiceImpl() }

    fun updateVoiceLabels() = with(VoiceController) { updateVoiceLabelsImpl() }

    fun cancelVoice() = with(VoiceController) { cancelVoiceImpl() }

    fun beginHeldVoice(pointerId: Int, downVersion: Long) = with(VoiceController) { beginHeldVoiceImpl(pointerId, downVersion) }

    fun startHeldVoiceWhenKeysSettle() = with(VoiceController) { startHeldVoiceWhenKeysSettleImpl() }

    fun finishHeldVoice() = with(VoiceController) { finishHeldVoiceImpl() }

    fun completeHeldVoiceIfDrained() = with(VoiceController) { completeHeldVoiceIfDrainedImpl() }

    fun restartAiVoiceIfDrained() = with(VoiceController) { restartAiVoiceIfDrainedImpl() }

    fun stopVoiceForEditorChange() = with(VoiceController) { stopVoiceForEditorChangeImpl() }

    fun readVoiceSelection(connection: InputConnection) : Pair<Int, Int>? = with(VoiceController) { readVoiceSelectionImpl(connection) }

    fun voiceRequestIsCurrent(request: Long, editor: Int) = with(VoiceController) { voiceRequestIsCurrentImpl(request, editor) }

    fun voiceTargetIsCurrent(request: Long, editor: Int, connection: InputConnection) = with(VoiceController) { voiceTargetIsCurrentImpl(request, editor, connection) }

    fun startVoice() = with(VoiceController) { startVoiceImpl() }

    fun checkVoiceStart(request: Long, editor: Int, connection: InputConnection) = with(VoiceController) { checkVoiceStartImpl(request, editor, connection) }

    fun createVoiceSession() = with(VoiceController) { createVoiceSessionImpl() }

    fun voiceListener() = with(VoiceController) { voiceListenerImpl() }

    fun failVoice(message: String) = with(VoiceController) { failVoiceImpl(message) }

    fun queueVoiceSegment(segmentId: Long, text: String) = with(VoiceController) { queueVoiceSegmentImpl(segmentId, text) }

    fun drainVoiceSegments() = with(VoiceController) { drainVoiceSegmentsImpl() }

}
