package io.secure.keyboard

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.window.DialogProperties
import io.secure.keyboard.data.*
import io.secure.keyboard.settings.*
import io.secure.keyboard.voice.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

open class SettingsActivity : ComponentActivity() {
    protected open val initialPage = "home"
    private val model: SettingsModel by viewModels()
    private var incomingPage by mutableStateOf<String?>(null)
    private var voiceState by mutableStateOf<ModelImportCoordinator.State>(ModelImportCoordinator.State.Idle)
    private var microphone by mutableStateOf(false)
    private var enabledIme by mutableStateOf(false)
    private var selectedIme by mutableStateOf(false)
    private val voiceObserver: (ModelImportCoordinator.State) -> Unit = { voiceState = it }
    private val micPicker = registerForActivityResult(ActivityResultContracts.RequestPermission()) { microphone = it }
    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { ModelImportTransfer.start(applicationContext, it) }
    }
    private val importPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val kind = model.pendingKind
            val epoch = model.importEpoch
            val resolver = applicationContext.contentResolver
            model.task(success = { model.message = "词库导入完成" }) {
                val input = resolver.openInputStream(uri) ?: error("无法读取文件")
                model.content.importDictionary(DictionaryTextReader.read(input), epoch, kind)
            }
        }
    }
    private val exportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("text/tab-separated-values")) { uri ->
        val snapshot = model.exportText
        model.exportText = null
        if (uri != null && snapshot != null) {
            val resolver = applicationContext.contentResolver
            model.task(success = { model.message = "词库导出完成" }) {
                (resolver.openOutputStream(uri, "wt") ?: error("无法写入文件")).bufferedWriter(Charsets.UTF_8).use { it.write(snapshot) }
            }
        } else if (uri != null) model.message = "导出已中断，请重新选择导出。"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingPage = savedInstanceState?.getString("page") ?: intent.getStringExtra("page") ?: initialPage
        model.pendingKind = savedInstanceState?.getString("pending_kind")?.let { runCatching { DictionaryKind.valueOf(it) }.getOrNull() }
            ?: model.pendingKind
        if (savedInstanceState?.containsKey("import_epoch") == true) model.importEpoch = savedInstanceState.getLong("import_epoch")
        setContent { ApplicationUi() }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        incomingPage = intent.getStringExtra("page") ?: initialPage
    }
    override fun onStart() { super.onStart(); ModelImportTransfer.attach(voiceObserver) }
    override fun onStop() { ModelImportTransfer.detach(voiceObserver); super.onStop() }
    override fun onResume() {
        super.onResume()
        microphone = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val manager = getSystemService(InputMethodManager::class.java)
        enabledIme = manager.enabledInputMethodList.any { it.packageName == packageName }
        selectedIme = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/') == packageName
        model.refresh()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", incomingPage)
        outState.putString("pending_kind", model.pendingKind.name)
        model.importEpoch?.let { outState.putLong("import_epoch", it) }
        super.onSaveInstanceState(outState)
    }
    private fun launch(action: () -> Unit) {
        try { action() } catch (e: Exception) { model.message = e.message ?: "设备不支持此操作" }
    }
    private fun openPage(page: String) { incomingPage = page }

    @Composable private fun ApplicationUi() {
        val revision = model.revision
        val config = model.settings
        val systemDark = LocalConfiguration.current.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val dark = remember(revision, systemDark) { config.isDark(systemDark) }
        val tokens = remember(revision, dark) { config.themeTokens(dark) }
        val route = incomingPage ?: initialPage
        var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
        fun ask(message: String, action: () -> Unit) { confirm = message to action }
        fun back() {
            incomingPage = when {
                route.startsWith("input_") -> "input"
                route.startsWith("profile_") -> "layout"
                route.startsWith("theme_") -> "behavior"
                route == "custom_ai" -> "api"
                route == "feedback" -> "keyboard"
                route == "home" && initialPage == "dashboard" -> "dashboard"
                route == "dashboard" || route == "home" || (route == "voice" && initialPage == "voice") -> { finish(); return }
                else -> "home"
            }
        }
        BackHandler { if (model.edit != null) model.edit = null else back() }
        SKeyTheme(tokens, dark) {
            SideEffect {
                val background = android.graphics.Color.parseColor(tokens.keyboardBackground)
                window.statusBarColor = background
                io.secure.keyboard.ui.styleSystemBars(window, android.graphics.Color.luminance(background) < .5f, background)
            }
            Scaffold(topBar = {
                TopAppBar(title = pageTitle(route), navigationIcon = {
                    IconButton(onClick = { back() }) { UiIcon("back", "返回") }
                })
            }) { padding ->
                key(route) {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        item {
                            when (route) {
                                "dashboard" -> Dashboard()
                                "home", "settings" -> Home()
                                "input" -> InputSettings()
                                "input_english" -> EnglishSettings()
                                "keyboard", "feedback" -> KeyboardSettings()
                                "behavior" -> Appearance()
                                "layout" -> Layouts()
                                "toolbar" -> ToolbarSettings()
                                "voice" -> VoiceSettings()
                                "api" -> ApiSettings()
                                "custom_ai" -> Group("我的自定义 AI") {
                                    Info("为提示词取名，在键盘的自定义模式中按名称选择。上下箭头决定选择列表的顺序。")
                                    Action("添加自定义 AI", !model.busy) { model.edit = ContentEdit("custom_ai") }
                                    if (model.aiPresets.isEmpty()) Info("还没有自定义 AI，例如：邮件润色、简短回复。")
                                }
                                "cleanup", "recovery" -> Group("本机内容") {
                                    Info("清理不会删除主题、布局或输入偏好，也不会修改 Android 系统剪贴板。")
                                    Nav("清空全部用户内容", "剪贴板、常用语、词库、最近表情和 自定义 AI", "delete") {
                                        ask("确定清空全部用户内容？此操作无法撤销。") { model.task { model.content.clearAll() } }
                                    }
                                }
                                "emoji" -> Group("最近表情") {
                                    Info(model.emoji.joinToString("  ").ifEmpty { "还没有最近使用的表情" })
                                    Action("清空最近表情") { ask("清空最近表情？") { model.task { model.content.clearEmoji() } } }
                                }
                                "licenses" -> Licenses()
                                else -> when {
                                    route.startsWith("profile_") -> ProfileSettings(route)
                                    route.startsWith("theme_") -> ThemeSettings(route == "theme_dark") { message, action -> ask(message, action) }
                                    route in listOf("clipboard", "phrases", "dictionary", "english_dictionary") -> ContentHeader(route) { message, action -> ask(message, action) }
                                    else -> Home()
                                }
                            }
                        }
                        if (route == "clipboard") items(model.clipboard, key = { it.id }) { item ->
                            ContentCard(item.text, if (item.pinned) "已置顶" else "剪贴板", listOf(
                                (if (item.pinned) "取消置顶" else "置顶") to { model.task { model.content.setClipboardPinned(item.id, !item.pinned) } },
                                "复制" to { copy(item.text) },
                                "删除" to { ask("删除这条剪贴板记录？") { model.task { model.content.deleteClipboard(item.id) } } }))
                        }
                        if (route == "phrases") items(model.phrases, key = { it.id }) { item ->
                            ContentCard(item.text, if (item.pinned) "已置顶" else "常用语", listOf(
                                "编辑" to { model.edit = ContentEdit(route, item.id, TextFieldValue(item.text)) },
                                (if (item.pinned) "取消置顶" else "置顶") to { model.task { model.content.setPhrasePinned(item.id, !item.pinned) } },
                                "上移" to { model.task { val index = model.content.phrases().indexOfFirst { it.id == item.id }; model.content.movePhrase(item.id, index - 1) } },
                                "删除" to { ask("删除这条常用语？") { model.task { model.content.deletePhrase(item.id) } } }))
                        }
                        if (route == "custom_ai") items(model.aiPresets, key = { it.id }) { preset ->
                            Card(Modifier.fillMaxWidth()) {
                                val index = model.aiPresets.indexOfFirst { it.id == preset.id }
                                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(preset.name, modifier = Modifier.weight(1f), maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        color = MiuixTheme.colorScheme.onSurface)
                                    IconButton(modifier = Modifier.size(36.dp), enabled = !model.busy && index > 0,
                                        onClick = { model.task { model.content.moveAiPreset(preset.id, -1) } }) { UiIcon("arrow_up", "上移 ${preset.name}") }
                                    IconButton(modifier = Modifier.size(36.dp), enabled = !model.busy && index < model.aiPresets.lastIndex,
                                        onClick = { model.task { model.content.moveAiPreset(preset.id, 1) } }) { UiIcon("arrow_down", "下移 ${preset.name}") }
                                    IconButton(modifier = Modifier.size(36.dp), enabled = !model.busy, onClick = {
                                        model.edit = ContentEdit("custom_ai", preset.id, TextFieldValue(preset.name), TextFieldValue(preset.prompt))
                                    }) { UiIcon("edit", "编辑 ${preset.name}") }
                                    IconButton(modifier = Modifier.size(36.dp), enabled = !model.busy, onClick = {
                                        ask("删除自定义 AI「${preset.name}」？") { model.task { model.content.deleteAiPreset(preset.id) } }
                                    }) { UiIcon("delete", "删除 ${preset.name}") }
                                }
                                Text(preset.prompt.replace('\n', ' ').replace('\r', ' '), maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, fontSize = 13.sp,
                                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp),
                                    color = MiuixTheme.colorScheme.onSurfaceSecondary)
                            }
                        }
                        if (route in listOf("dictionary", "english_dictionary")) {
                            val kind = if (route == "dictionary") DictionaryKind.CHINESE else DictionaryKind.ENGLISH
                            items(if (kind == DictionaryKind.CHINESE) model.chinese else model.english, key = { it.id }) { item ->
                                ContentCard(item.word, item.reading, listOf(
                                    "编辑" to { model.edit = ContentEdit(route, item.id, TextFieldValue(item.word), TextFieldValue(item.reading)) },
                                    "删除" to { ask("删除此词条？") { model.task { model.content.deleteDictionary(item.id, kind) } } }))
                            }
                        }
                        item { if (model.busy) Info("正在处理…") else Spacer(Modifier.height(8.dp)) }
                    }
                }
            }
            confirm?.let { (text, action) -> Modal("确认操作", { confirm = null }) {
                Info(text)
                Action("取消") { confirm = null }
                Action("确认", enabled = !model.busy) { confirm = null; action() }
            } }
            model.edit?.let { edit -> EditDialog(edit) }
            model.message?.let { message -> Modal("提示", { model.message = null }) {
                Info(message); Action("知道了") { model.message = null }
            } }
        }
    }

    @Composable private fun Dashboard() {
        model.revision
        Group("S Key") {
            Nav(if (enabledIme) "输入法已启用" else "启用输入法", "在 Android 设置中管理", "settings") {
                launch { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
            }
            Nav(if (selectedIme) "当前正在使用 S Key" else "切换到 S Key", icon = "globe") {
                getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            }
            var trial by remember { mutableStateOf("") }
            TextField(value = trial, onValueChange = { trial = it }, label = "试试您的 S Key 输入法 ...", modifier = Modifier.fillMaxWidth().padding(16.dp))
        }
        Spacer(Modifier.height(16.dp))
        Group("开始定制") {
            Nav("偏好设置", "输入、键盘、主题与本机内容", "settings") { openPage("home") }
            Nav("输入方式", "原版混输偏好与英文补全", "input") { openPage("input") }
            Nav("主题工作室", "黑白默认主题与自定义配色", "theme") { openPage("behavior") }
        }
    }
    @Composable private fun Home() {
        model.revision
        Group("输入与外观") {
            Nav("输入方式", "原版混输偏好与英文补全", "input") { openPage("input") }
            Nav("键盘操作", "声音、触感、长按、工具栏", "keyboard") { openPage("keyboard") }
            Nav("主题与外观", "自定义配色、黑白切换、跟随系统", "theme") { openPage("behavior") }
            Nav("布局配置", "横竖屏、折叠屏、悬浮与分离", "layout") { openPage("layout") }
        }
        Spacer(Modifier.height(16.dp))
        Group("本机内容") {
            Nav("剪贴板", "${model.clipboard.size} 条", "clipboard") { openPage("clipboard") }
            Nav("常用语", "${model.phrases.size} 条", "phrases") { openPage("phrases") }
            Nav("中文自定义词库", "${model.chinese.size} 条 · 手动短语", "dictionary") { openPage("dictionary") }
            Nav("英文自定义词库", "${model.english.size} 条", "english_dictionary") { openPage("english_dictionary") }
            Nav("最近表情", "${model.emoji.size} / 50", "emoji") { openPage("emoji") }
        }
        Spacer(Modifier.height(16.dp))
        Group("应用与隐私") {
            Nav("AI 设置", "Google 翻译 · Grok · 模型选择", "input") { openPage("api") }
            Nav("语音输入", "离线语音 / AI 语音", "voice") { openPage("voice") }
            Nav("Android 输入法设置", icon = "settings") { launch { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) } }
            Nav("内容清理", "保留主题与设置", "delete") { openPage("cleanup") }
            Nav("开源许可", "Miuix、图标、词库与语音组件", "dictionary") { openPage("licenses") }
            Info("普通输入离线处理，不学习输入历史。翻译、AI 生成、AI 语音和获取模型列表时使用网络。剪贴板、常用语、自定义词库、最近表情和自定义 AI 提示词仅保存在本机；只保存手动添加的名称和提示词，不记录原文或 AI 结果。")
        }
    }
    @Composable private fun ApiSettings() {
        val draft = model.apiDraft
        var showKeys by remember { mutableStateOf(false) }
        @Composable fun field(label: String, value: String, secret: Boolean = false, change: (String) -> Unit) {
            TextField(value = value, onValueChange = change, label = label,
                visualTransformation = if (secret && !showKeys) androidx.compose.ui.text.input.PasswordVisualTransformation()
                    else androidx.compose.ui.text.input.VisualTransformation.None,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
        }
        Group("使用设置") {
            Nav("自定义 AI", "${model.aiPresets.size} 个 · 提示词与排序", "ai") { openPage("custom_ai") }
            Toggle("AI 生成后自动上屏", model.settings.aiAutoApply, "关闭时先预览；开启时自动插入或替换仍有效的选中文字") { model.settings.aiAutoApply = it }
            Toggle("语音自动翻译", model.settings.voiceAutoTranslate, "英文键盘下将 AI 语音译为英文；关闭后直接上屏识别原文") { model.settings.voiceAutoTranslate = it }
        }
        Group("Google 翻译 API") {
            field("接口地址（完整翻译端点）", draft.googleUrl) { model.apiDraft = draft.copy(googleUrl = it) }
            field("Google API Key", draft.googleKey, true) { model.apiDraft = draft.copy(googleKey = it) }
            Choice("默认 NMT 模型", draft.googleModel == "nmt") { model.apiDraft = draft.copy(googleModel = "nmt") }
            field("模型 ID / 完整模型资源名", draft.googleModel) { model.apiDraft = draft.copy(googleModel = it) }
            Info("默认使用 Cloud Translation Basic v2。其他模型可填写账号支持的完整资源名。")
        }
        Group("Grok API") {
            field("接口地址（包含 /v1）", draft.grokUrl) { model.apiModels = emptyList(); model.apiDraft = draft.copy(grokUrl = it) }
            field("Grok API Key", draft.grokKey, true) { model.apiModels = emptyList(); model.apiDraft = draft.copy(grokKey = it) }
            field("模型 ID", draft.grokModel) { model.apiDraft = draft.copy(grokModel = it) }
            Action("获取可用模型", !model.busy) { model.fetchApiModels() }
            model.apiModels.forEach { id -> Choice(id, draft.grokModel == id) { model.apiDraft = draft.copy(grokModel = id) } }
            Info("使用当前填写的地址和密钥获取模型。也可手动输入模型 ID，修改后点击保存。")
        }
        Group("AI 语音 API") {
            field("语音识别端点（完整 HTTPS 地址）", draft.voiceUrl) { model.apiDraft = draft.copy(voiceUrl = it) }
            field("语音 API Key", draft.voiceKey, true) { model.apiDraft = draft.copy(voiceKey = it) }
            field("语音模型 ID", draft.voiceModel) { model.apiDraft = draft.copy(voiceModel = it) }
            Action("使用 Grok 地址与密钥") {
                model.apiDraft = draft.copy(voiceUrl = draft.grokUrl.trimEnd('/') + "/stt", voiceKey = draft.grokKey)
            }
            Info("使用语音转文字接口，不能使用文字聊天模型。默认适配 xAI /stt；兼容服务需支持 WAV multipart 上传和 text 响应。结束收音后上传录音，不在本机保存音频。")
        }
        Group("保存配置") {
            Toggle("显示密钥", showKeys) { showKeys = it }
            Action("保存 API 配置", !model.busy) {
                val snapshot = model.apiDraft
                model.task(success = { model.message = "API 配置已保存" }) { model.apiStore.save(snapshot) }
            }
            Action("重新载入已保存配置", !model.busy) { model.loadApi(); model.apiModels = emptyList() }
            Action("清除已保存的 API 配置", !model.busy) {
                model.task(success = { model.apiDraft = ApiConfiguration(); model.apiModels = emptyList(); model.message = "API 配置已清除" }) { model.apiStore.clear() }
            }
            Info("密钥仅在本机加密保存。获取模型只发送接口鉴权信息，不发送输入内容。键盘“更多 → 翻译”使用 Google 配置，只有点击翻译才发送原文；键盘“更多 → AI 助手”使用 Grok 配置，点击生成才发送原文和指令。选择 AI 语音后，结束收音时上传录音进行识别。")
        }
    }

    @Composable private fun InputSettings() {
        model.revision
        val s = model.settings
        Group("中文输入偏好") {
            Toggle("自动插入空格", s.autoChineseSpacing, "在中文与字母、数字之间插入空格，如：中 aa 文") { s.autoChineseSpacing = it }
            Toggle("五笔四码自动上屏", s.fourCodeAutoCommit) { s.fourCodeAutoCommit = it }
            Toggle("模糊拼音", s.fuzzyPinyin, "用于原版混输中的拼音候选") { s.fuzzyPinyin = it }
            if (s.fuzzyPinyin) SettingsStore.fuzzyPairIds.forEach { pair ->
                Toggle(pair.replace("_", " ↔ "), pair in s.fuzzyPairs) {
                    s.fuzzyPairs = if (it) s.fuzzyPairs + pair else s.fuzzyPairs - pair
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        EnglishSettings()
    }
    @Composable private fun EnglishSettings() {
        model.revision
        Group("英文输入偏好") {
            Toggle("英文当前词补全", model.settings.englishCompletion) { model.settings.englishCompletion = it }
        }
    }
    @Composable private fun KeyboardSettings() {
        model.revision
        val s = model.settings
        Group("按键反馈") {
            Toggle("按键声音", s.keySound) { s.keySound = it }
            Toggle("按键振动", s.vibration) { s.vibration = it }
            Range("振动强度", s.vibrationStrength, 1, 100, "%") { s.vibrationStrength = it }
            Info("声音和触感遵循系统设置，实际效果由设备决定。")
        }
        Spacer(Modifier.height(16.dp))
        Group("手势与长按") {
            Toggle("长按输入符号", s.longPressSymbols) { s.longPressSymbols = it }
            Range("长按延迟", s.longPressDelay, 150, 1500, "ms", 10) { s.longPressDelay = it }
            Toggle("滑动退格删除", s.swipeDelete) { s.swipeDelete = it }
            Info("空格左右滑动移动光标，长按空格离线听写。")
            Nav("定制工具栏", "固定工具与排列顺序", "more") { openPage("toolbar") }
        }
    }
    @Composable private fun Appearance() {
        model.revision
        val s = model.settings
        Group("默认主题与切换") {
            Toggle("跟随系统深浅色", s.theme == "system", "键盘与应用设置同步切换") {
                s.theme = if (it) "system" else if (s.manualDark) "dark" else "light"
            }
            Choice("白色主题", s.theme == "light") { s.manualDark = false; s.theme = "light" }
            Choice("黑色主题", s.theme == "dark") { s.manualDark = true; s.theme = "dark" }
            Nav("自定义白色主题", "以默认黑白配色为基础，独立保存15项颜色", "theme") { openPage("theme_light") }
            Nav("自定义黑色主题", "以默认黑白配色为基础，独立保存15项颜色", "theme") { openPage("theme_dark") }
            Info("默认使用原有黑白配色。选择黑色或白色会关闭跟随系统；两套颜色可分别自定义，键盘和设置同步应用。恢复默认只重置所选主题。")
        }
        Spacer(Modifier.height(16.dp))
        Group("文字与提示") {
            Range("候选栏字号", s.candidateTextSize, 12, 32, "sp") { s.candidateTextSize = it }
            Range("展开候选字号", s.expandedCandidateTextSize, 12, 32, "sp") { s.expandedCandidateTextSize = it }
            listOf("all" to "全部按键角标", "digits" to "仅数字角标", "hidden" to "隐藏角标").forEach { (id, label) ->
                Choice(label, s.symbolHints == id) { s.symbolHints = id }
            }
        }
    }
    @Composable private fun Layouts() {
        model.revision
        Group("独立布局") {
            for (outer in listOf(true, false)) for (landscape in listOf(false, true)) {
                val id = "profile_${if (outer) "outer" else "inner"}_${if (landscape) "landscape" else "portrait"}"
                Nav("${if (outer) "外屏" else "内屏"} · ${if (landscape) "横屏" else "竖屏"}", icon = "layout") { openPage(id) }
            }
        }
    }
    @Composable private fun ProfileSettings(route: String) {
        model.revision
        val outer = route.contains("outer")
        val landscape = route.endsWith("landscape")
        val s = model.settings
        val p = s.profile(outer, landscape)
        fun save(value: SettingsStore.LayoutProfile) = s.saveProfile(outer, landscape, value)
        Group("尺寸与留白") {
            Range("键盘高度", p.heightDp, 200, 700, "dp") { save(p.copy(heightDp = it)) }
            Range("两侧留白", p.sideGapDp, 0, 300, "dp") { save(p.copy(sideGapDp = it)) }
            Range("底部留白", p.bottomGapDp, 0, 300, "dp") { save(p.copy(bottomGapDp = it)) }
        }
        Spacer(Modifier.height(16.dp))
        Group("布局形态") {
            Toggle("悬浮键盘", p.floating) { save(p.copy(floating = it)) }
            if (!outer) {
                Toggle("分离键盘", p.split) { save(p.copy(split = it)) }
                Range("分离间距", p.splitGapDp, 0, 180, "dp") { save(p.copy(splitGapDp = it)) }
            }
            Info("悬浮键盘可在键盘内拖动和缩放。内外屏按窗口宽度识别。")
        }
    }
    @Composable private fun ToolbarSettings() {
        model.revision
        val s = model.settings
        Group("固定工具") {
            Info("开关控制是否固定；使用上下箭头调整顺序。")
            s.toolbarPins.forEachIndexed { index, id ->
                BasicComponent(title = toolName(id), leftAction = { UiIcon(id, null) }, rightActions = {
                    IconButton(onClick = { val pins = s.toolbarPins.toMutableList(); if (index > 0) { java.util.Collections.swap(pins, index, index - 1); s.toolbarPins = pins } }) { UiIcon("arrow_up", "上移") }
                    IconButton(onClick = { val pins = s.toolbarPins.toMutableList(); if (index < pins.lastIndex) { java.util.Collections.swap(pins, index, index + 1); s.toolbarPins = pins } }) { UiIcon("arrow_down", "下移") }
                })
            }
            SettingsStore.availablePins.forEach { id -> Toggle(toolName(id), id in s.toolbarPins) {
                s.toolbarPins = if (it) s.toolbarPins + id else s.toolbarPins - id
            } }
        }
    }
    @Composable private fun ThemeSettings(dark: Boolean, ask: (String, () -> Unit) -> Unit) {
        model.revision
        val s = model.settings
        val tokens = s.themeTokens(dark)
        var editing by remember { mutableStateOf<String?>(null) }
        Group("主题预览") {
            SKeyTheme(tokens, dark) {
                KeyboardThemePreview(tokens, s.toolbarPins, s.symbolHints)
                Info("完整26键配色预览；可切换工具栏/候选栏，点击大写键查看选中配色。")
            }
        }
        Spacer(Modifier.height(16.dp))
        Group("颜色") {
            themeLabels.forEach { (id, label) ->
                BasicComponent(title = label, summary = tokens.asMap().getValue(id), onClick = { editing = id },
                    rightActions = { Box(Modifier.size(28.dp).background(hexColor(tokens.asMap().getValue(id)), RoundedCornerShape(8.dp)).border(1.dp, MiuixTheme.colorScheme.outline, RoundedCornerShape(8.dp))) })
            }
            Action("恢复默认${if (dark) "黑色" else "白色"}主题") {
                ask("恢复此主题的默认颜色？另一套主题不会改变。") { s.resetThemeTokens(dark) }
            }
        }
        editing?.let { id ->
            ColorEditor(themeLabels.getValue(id), tokens.asMap().getValue(id), { editing = null }, preview = { color ->
                val draft = tokens.previewColor(id, color)
                SKeyTheme(draft, dark) {
                    KeyboardThemePreview(draft, s.toolbarPins, s.symbolHints,
                        initialCandidates = id in listOf("candidate", "candidateSelected", "preedit", "divider"),
                        initialShifted = id in listOf("selectedKeyBackground", "selectedKeyText"))
                }
            }) { color ->
                s.setThemeToken(dark, id, color); editing = null
            }
        }
    }
    @Composable private fun ColorEditor(label: String, initial: String, close: () -> Unit, preview: @Composable (String) -> Unit, save: (String) -> Unit) {
        var hex by remember { mutableStateOf(initial) }
        val initialHsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(initial), it) } }
        var hue by remember { mutableFloatStateOf(initialHsv[0]) }
        var saturation by remember { mutableFloatStateOf(initialHsv[1]) }
        var brightness by remember { mutableFloatStateOf(initialHsv[2]) }
        fun update() { hex = String.format("#%06X", android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness)) and 0xFFFFFF) }
        var lastValid by remember { mutableStateOf(initial) }
        val valid = hex.matches(Regex("#[0-9a-fA-F]{6}"))
        Modal(label, close) {
            val display = if (valid) hex else lastValid
            SideEffect { if (valid) lastValid = hex }
            preview(display)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).height(24.dp).background(hexColor(initial), RoundedCornerShape(6.dp)))
                Box(Modifier.weight(1f).height(24.dp).background(hexColor(display), RoundedCornerShape(6.dp)))
            }
            Text("左：原颜色　右：当前选择", fontSize = 12.sp)
            Text("调色盘：拖动选择饱和度和明度", fontSize = 14.sp)
            AndroidView(factory = { context -> ColorPickerView(context, ColorPickerView.Component.SATURATION_VALUE) },
                update = { view ->
                    view.setSelection(hue, saturation, brightness)
                    view.onChange = { h, s, v, _ -> hue = h; saturation = s; brightness = v; update() }
                }, modifier = Modifier.fillMaxWidth().height(160.dp))
            Text("色相", fontSize = 14.sp)
            AndroidView(factory = { context -> ColorPickerView(context, ColorPickerView.Component.HUE) },
                update = { view ->
                    view.setSelection(hue, saturation, brightness)
                    view.onChange = { h, s, v, _ -> hue = h; saturation = s; brightness = v; update() }
                }, modifier = Modifier.fillMaxWidth().height(40.dp))
            listOf("#FFFFFF", "#EFEFF1", "#171717", "#2B2B2B", "#637A91", "#336699",
                "#F06292", "#EF5350", "#FFB74D", "#66BB6A", "#42A5F5", "#AB47BC").chunked(6).forEach { colors ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    colors.forEach { color ->
                        Box(Modifier.weight(1f).height(32.dp).background(hexColor(color), RoundedCornerShape(8.dp))
                            .border(1.dp, MiuixTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                            .clickable {
                                hex = color
                                val hsv = FloatArray(3); android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(color), hsv)
                                hue = hsv[0]; saturation = hsv[1]; brightness = hsv[2]
                            }.semantics { contentDescription = "选择颜色 $color" })
                    }
                }
            }
            TextField(value = hex, onValueChange = { value ->
                hex = value
                if (value.matches(Regex("#[0-9a-fA-F]{6}"))) {
                    val hsv = FloatArray(3); android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(value), hsv)
                    hue = hsv[0]; saturation = hsv[1]; brightness = hsv[2]
                }
            }, label = "颜色 HEX，例如 #2B2B2B", singleLine = true, modifier = Modifier.fillMaxWidth())
            Info("文字与背景请保持足够对比度。取消不会保存修改。")
            Action("保存颜色", valid) { save(hex.uppercase()) }
            Action("取消", action = close)
        }
    }
    @Composable private fun ContentHeader(route: String, ask: (String, () -> Unit) -> Unit) {
        model.revision
        Group(pageTitle(route)) {
            if (route == "clipboard") {
                Range("历史条数上限", model.settings.clipboardLimit, 1, 1000, "条") { value ->
                    model.settings.clipboardLimit = value
                }
                Info("新复制内容按上限淘汰旧记录；置顶内容优先保留。敏感标记内容不采集。")
                Action("清空剪贴板历史") { ask("清空剪贴板历史？系统剪贴板不受影响。") { model.task { model.content.clearClipboard() } } }
                if (model.clipboard.isEmpty()) Info("还没有剪贴板记录")
            } else {
                Action(if (route == "phrases") "添加常用语" else "添加词条", !model.busy) { model.edit = ContentEdit(route) }
                if (route != "phrases") {
                    val kind = if (route == "dictionary") DictionaryKind.CHINESE else DictionaryKind.ENGLISH
                    Info("UTF-8 文本，每行：词语 + 制表符 + 编码。可选表头 word + 制表符 + reading。中文手动短语支持拼音或五笔编码。")
                    Action("导入词库", !model.busy) {
                        model.pendingKind = kind
                        model.task(success = { launch { importPicker.launch(arrayOf("text/*", "application/octet-stream")) } }) {
                            model.importEpoch = model.content.captureDictionaryEpoch(kind)
                        }
                    }
                    Action("导出词库", !model.busy) {
                        model.pendingKind = kind
                        model.task(success = { launch { exportPicker.launch("S-Key-2-${kind.name.lowercase()}.tsv") } }) {
                            model.exportText = model.content.exportDictionary(kind)
                        }
                    }
                    Action("清空本词库") { ask("清空${pageTitle(route)}？") { model.task { model.content.clearDictionary(kind) } } }
                } else Action("清空常用语") { ask("清空全部常用语？") { model.task { model.content.clearPhrases() } } }
            }
        }
    }
    @Composable private fun EditDialog(edit: ContentEdit) {
        val customAi = edit.category == "custom_ai"
        val title = if (customAi) { if (edit.id == null) "添加自定义 AI" else "编辑自定义 AI" }
            else if (edit.id == null) "添加内容" else "编辑内容"
        if (customAi) {
            Modal(title, { if (!model.busy) model.edit = null }) {
                Text("给常用指令取个名字，在键盘中一键使用", fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary)
                Spacer(Modifier.height(8.dp))
                AiEditorField("名称", "例如：邮件润色", edit.first, 40, false) {
                    model.edit = edit.copy(first = it)
                }
                Spacer(Modifier.height(4.dp))
                AiEditorField("提示词", "描述你希望 AI 如何处理原文…", edit.second, 2000, true) {
                    model.edit = edit.copy(second = it)
                }
                Spacer(Modifier.height(8.dp))
                val canSave = !model.busy && edit.first.text.isNotBlank() && edit.second.text.isNotBlank()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (save in listOf(false, true)) {
                        val enabled = if (save) canSave else !model.busy
                        Box(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (save) MiuixTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else .35f)
                                else MiuixTheme.colorScheme.onSurface.copy(alpha = .06f))
                            .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button) {
                                if (save) model.saveEdit() else model.edit = null
                            }, contentAlignment = Alignment.Center) {
                            Text(if (save) "保存" else "取消", fontSize = 16.sp, fontWeight = FontWeight.Medium,
                                color = if (save) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
            return
        }
        Modal(title, { if (!model.busy) model.edit = null }) {
            TextField(value = edit.first, onValueChange = { if (!customAi || it.text.length <= 40) model.edit = edit.copy(first = it) },
                label = if (customAi) "名称，例如：邮件润色" else if (edit.category == "phrases") "常用语" else "词语",
                singleLine = customAi, modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp))
            if (edit.category != "phrases") TextField(value = edit.second, onValueChange = { if (!customAi || it.text.length <= 2000) model.edit = edit.copy(second = it) },
                label = if (customAi) "自定义提示词" else "拼音 / 编码",
                modifier = if (customAi) Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 260.dp) else Modifier.fillMaxWidth(), singleLine = !customAi)
            if (customAi) Info("${edit.first.text.length}/40 名称 · ${edit.second.text.length}/2000 提示词")
            Action("保存", !model.busy && (!customAi || (edit.first.text.isNotBlank() && edit.second.text.isNotBlank()))) { model.saveEdit() }
            Action("取消", !model.busy) { model.edit = null }
        }
    }
    @Composable private fun AiEditorField(title: String, placeholder: String, value: TextFieldValue,
        limit: Int, multiline: Boolean, change: (TextFieldValue) -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface)
                Text("${value.text.length}/$limit", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
            }
            BasicTextField(value = value, onValueChange = { if (it.text.length <= limit) change(it) },
                enabled = !model.busy, singleLine = !multiline,
                textStyle = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, color = MiuixTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = title }
                    .clip(RoundedCornerShape(14.dp))
                    .background(MiuixTheme.colorScheme.onSurface.copy(alpha = .045f))
                    .border(1.dp, MiuixTheme.colorScheme.onSurface.copy(alpha = .09f), RoundedCornerShape(14.dp))
                    .padding(14.dp).heightIn(min = if (multiline) 136.dp else 24.dp, max = if (multiline) 200.dp else 48.dp),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.TopStart) {
                        if (value.text.isEmpty()) Text(placeholder, fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                        inner()
                    }
                })
        }
    }
    @Composable private fun VoiceSettings() {
        model.revision
        val ai = model.settings.voiceMode == "ai"
        Group("识别方式") {
            Choice("离线语音", !ai) { model.settings.voiceMode = "offline" }
            Choice("AI 语音", ai) { model.settings.voiceMode = "ai" }
            Info(if(ai) "录音交给 AI 识别，无需下载本地模型。需要联网，结束收音后会上传录音。" else "本地识别普通话，录音和转写均不上传。")
        }
        if(ai) {
            Group("AI 语音") {
                Info("点击工具栏语音开始，点击“识别语音”后上传识别，成功上屏后清空预览并继续聆听，点击取消结束。也可长按空格说话、松手识别；长按模式不会自动继续。单次录音最多 60 秒。")
                Nav("AI 设置", "配置语音端点、密钥与模型", "settings") { openPage("api") }
                Action("允许麦克风权限", !microphone) { micPicker.launch(Manifest.permission.RECORD_AUDIO) }
            }
            return
        }
        val installed = remember(voiceState) { ModelRepository(applicationContext).isInstalled() }
        Group("离线普通话") {
            Info("模型：${if (installed) "已导入" else "未导入"} · 麦克风：${if (microphone) "已允许" else "未允许"}", true)
            Info("不保存录音，不上传转写。工具栏点击语音，或长按空格开始；松开空格结束收音。")
            Info("下载指定约25 MB模型，解压后选择包含 model.int8.onnx 和 tokens.txt 的文件夹。")
            Action("浏览器下载模型") { launch { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ModelRepository.DOWNLOAD_URL))) } }
            Action("选择模型文件夹", voiceState !is ModelImportCoordinator.State.Running) { launch { modelPicker.launch(null) } }
            Action("允许麦克风权限", !microphone) { micPicker.launch(Manifest.permission.RECORD_AUDIO) }
            when (val state = voiceState) {
                is ModelImportCoordinator.State.Running -> Info("正在复制并校验模型…")
                is ModelImportCoordinator.State.Failure -> Info(state.message)
                else -> Unit
            }
            Nav("开源声明", icon = "dictionary") { openPage("licenses") }
        }
    }
    @Composable private fun Licenses() {
        var notices by remember { mutableStateOf("正在读取声明…") }
        LaunchedEffect(Unit) {
            notices = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                listOf("SHERPA_ONNX_LICENSE", "ONNXRUNTIME_LICENSE", "ONNXRUNTIME_THIRD_PARTY_NOTICES")
                    .joinToString("\n\n") { assets.open("voice-licenses/$it").bufferedReader().use { reader -> reader.readText() } }
            }
        }
        Group("开源组件") {
            Info("Miuix 0.5.1 · Apache-2.0\nMiuix Icons Regular / Material Symbols Rounded · Apache-2.0\nKotlin、AndroidX / Compose：随包保留各组件许可。\n词库及字体的来源与许可保留在源码 third_party 与应用 assets。")
            Info(notices)
        }
    }
    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("S Key", text))
        model.message = "已复制"
    }
    @Composable private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
        Column {
            SmallTitle(text = title)
            Card(modifier = Modifier.fillMaxWidth(), content = content)
        }
    }
    @Composable private fun Info(value: String, strong: Boolean = false) {
        Text(value, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            fontSize = if (strong) 19.sp else 14.sp,
            fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal,
            color = if (strong) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceSecondary)
    }
    @Composable private fun Nav(title: String, summary: String? = null, icon: String = "settings", action: () -> Unit) {
        BasicComponent(title = title, summary = summary, leftAction = { Box(Modifier.padding(end = 16.dp)) { UiIcon(icon, null) } },
            rightActions = { UiIcon("forward", null) }, onClick = action)
    }
    @Composable private fun Toggle(title: String, checked: Boolean, summary: String? = null, change: (Boolean) -> Unit) {
        BasicComponent(title = title, summary = summary, onClick = { change(!checked) },
            rightActions = { Switch(checked = checked, onCheckedChange = change) })
    }
    @Composable private fun Choice(title: String, checked: Boolean, action: () -> Unit) {
        BasicComponent(title = title, onClick = action, rightActions = { if (checked) UiIcon("check", "已选择") })
    }
    @Composable private fun Range(title: String, value: Int, minimum: Int, maximum: Int, unit: String, step: Int = 1, save: (Int) -> Unit) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text("$title  $value $unit")
            Slider(progress = value.coerceIn(minimum, maximum).toFloat(), minValue = minimum.toFloat(), maxValue = maximum.toFloat(),
                decimalPlaces = 0, onProgressChange = { save((minimum + ((it - minimum) / step).roundToInt() * step).coerceIn(minimum, maximum)) })
        }
    }
    @Composable private fun Action(text: String, enabled: Boolean = true, action: () -> Unit) {
        TextButton(text = text, onClick = action, enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
    }
    @Composable private fun ContentCard(text: String, summary: String, actions: List<Pair<String, () -> Unit>>) {
        var preview by remember(text) { mutableStateOf(false) }
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).heightIn(min = 40.dp).clickable { preview = true },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text.replace('\n', ' ').replace('\r', ' '), modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        color = MiuixTheme.colorScheme.onSurface)
                    if (summary.isNotBlank() && summary !in listOf("剪贴板", "常用语", "已置顶")) {
                        Text(summary, modifier = Modifier.widthIn(max = 72.dp), fontSize = 13.sp,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary)
                    }
                }
                actions.forEach { (label, action) ->
                    val icon = when (label) {
                        "编辑" -> "edit"
                        "删除" -> "delete"
                        "置顶" -> "pin"
                        "取消置顶" -> "unpin"
                        "上移" -> "arrow_up"
                        "复制" -> "show"
                        else -> "more"
                    }
                    IconButton(modifier = Modifier.size(36.dp), enabled = !model.busy, onClick = { if (label == "复制") preview = true else action() }) {
                        UiIcon(icon, if (label == "复制") "查看完整内容" else label)
                    }
                }
            }
        }
        if (preview) Modal("内容", { preview = false }) {
            Text(text, color = MiuixTheme.colorScheme.onSurface)
            if (summary.isNotBlank()) Info(summary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(text = "关闭", onClick = { preview = false }, modifier = Modifier.weight(1f))
                TextButton(text = "复制", onClick = { copy(text); preview = false }, modifier = Modifier.weight(1f))
            }
        }
    }
    @Composable private fun Modal(title: String, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
        Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Card(Modifier.fillMaxWidth().padding(20.dp).widthIn(max = 560.dp).heightIn(max = (LocalConfiguration.current.screenHeightDp * .85f).dp)) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                    content()
                }
            }
        }
    }
    @Composable private fun UiIcon(kind: String, description: String?) {
        Icon(painter = painterResource(io.secure.keyboard.ui.KeyboardIcons.resource(kind)), contentDescription = description,
            modifier = Modifier.size(24.dp), tint = hexColor(model.settings.themeTokens(model.settings.isDark(LocalConfiguration.current.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)).toolbarIcon))
    }
    private fun pageTitle(route: String): String = when(route) {
        "dashboard" -> "S Key"
        "input" -> "输入方式"
        "input_english" -> "英文设置"
        "keyboard", "feedback" -> "键盘操作"
        "behavior" -> "主题与外观"
        "theme_light" -> "自定义白色主题"
        "theme_dark" -> "自定义黑色主题"
        "layout" -> "布局配置"
        "toolbar" -> "定制工具栏"
        "clipboard" -> "剪贴板"
        "phrases" -> "常用语"
        "custom_ai" -> "自定义 AI"
        "dictionary" -> "中文自定义词库"
        "english_dictionary" -> "英文自定义词库"
        "emoji" -> "最近表情"
        "ai" -> "AI 助手"
        "translate" -> "翻译"
        "api" -> "AI 设置"
        "voice" -> "语音输入"
        "cleanup", "recovery" -> "内容清理"
        "licenses" -> "开源声明"
        else -> if (route.startsWith("profile_")) "${if (route.contains("outer")) "外屏" else "内屏"} · ${if (route.endsWith("landscape")) "横屏" else "竖屏"}" else "偏好设置"
    }
    private fun toolName(id: String) = when(id) {
        "floating" -> "悬浮键盘"; "split" -> "分离键盘"; "theme" -> "切换主题"
        "quickedit" -> "快捷编辑"; "more" -> "更多"; else -> pageTitle(id)
    }
    companion object {
        private val themeLabels = linkedMapOf(
            "keyboardBackground" to "页面与键盘背景", "normalKeyBackground" to "卡片与普通键背景",
            "specialKeyBackground" to "功能面与功能键背景", "text" to "主要文字", "hint" to "次要文字与角标",
            "toolbarIcon" to "键盘与设置图标", "candidate" to "候选文字", "candidateSelected" to "首选候选",
            "accent" to "强调色与回车背景", "enterText" to "强调色上的文字", "preedit" to "编码文字",
            "divider" to "分隔线", "specialKeyText" to "功能键文字", "selectedKeyBackground" to "选中键背景",
            "selectedKeyText" to "选中键文字")
    }
}
