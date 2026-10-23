package io.secure.keyboard

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress

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
    private val builtinModel: BuiltinDictionaryModel by viewModels()
    private var incomingPage by mutableStateOf<String?>(null)
    private val pageHistory = ArrayList<String>()
    private var voiceState by mutableStateOf<ModelImportCoordinator.State>(ModelImportCoordinator.State.Idle)
    private var microphone by mutableStateOf(false)
    private var enabledIme by mutableStateOf(false)
    private var selectedIme by mutableStateOf(false)
    private val voiceObserver: (ModelImportCoordinator.State) -> Unit = { voiceState = it }
    private val micPicker = registerForActivityResult(ActivityResultContracts.RequestPermission()) { microphone = it }
    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { ModelImportTransfer.start(applicationContext, it) }
    }
    private val restorePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val resolver = applicationContext.contentResolver
            val target = model
            var plan: ContentStore.BackupPlan? = null
            target.fileTask(success = { target.pendingBackup = plan }) {
                try {
                    plan = target.content.prepareBackup(resolver.openInputStream(uri) ?: error("无法读取备份"))
                } catch (_: Exception) { error("备份无法读取，请检查文件格式和容量。尚未修改数据。") }
            }
        }
    }
    private val backupPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val resolver = applicationContext.contentResolver
            val target = model
            target.fileTask(success = { target.message = "数据备份完成" }) {
                try {
                    (resolver.openOutputStream(uri, "wt") ?: error("无法写入备份")).use {
                        target.content.writeBackup(it)
                    }
                } catch (_: Exception) { error("备份失败，请重新选择保存位置；请勿使用未完成的文件。") }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingPage = savedInstanceState?.getString("page") ?: intent.getStringExtra("page") ?: initialPage
        pageHistory.addAll(savedInstanceState?.getStringArrayList("pageHistory").orEmpty())
        if (savedInstanceState == null) consumePhraseShortcut(intent)
        setContent { ApplicationUi() }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        pageHistory.clear()
        model.edit = null
        incomingPage = intent.getStringExtra("page") ?: initialPage
        consumePhraseShortcut(intent)
    }
    private fun consumePhraseShortcut(intent: Intent) {
        if (intent.getBooleanExtra("add_phrase", false)) {
            intent.removeExtra("add_phrase")
            incomingPage = "phrases"
            model.edit = ContentEdit("phrases")
        }
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
        outState.putStringArrayList("pageHistory", ArrayList(pageHistory))
        super.onSaveInstanceState(outState)
    }
    private fun launch(action: () -> Unit) {
        try { action() } catch (e: Exception) { model.message = e.message ?: "设备不支持此操作" }
    }
    private fun openPage(page: String) {
        val current = incomingPage ?: initialPage
        if (current == page) return
        pageHistory.add(current)
        incomingPage = page
    }

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
            if (route == "builtin_dictionaries" && builtinModel.selected != null) { builtinModel.back(); return }
            if (pageHistory.isNotEmpty()) {
                incomingPage = pageHistory.removeAt(pageHistory.lastIndex)
                return
            }
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
            model.pendingBackup?.let { plan ->
                Modal("还原预览", { if (!model.busy) model.pendingBackup = null }) {
                    Text(plan.summary, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(text = "取消", enabled = !model.busy, modifier = Modifier.weight(1f), onClick = { model.pendingBackup = null })
                        Button(enabled = !model.busy, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary(), onClick = {
                            model.task(success = { model.pendingBackup = null; model.message = "数据还原完成" }) { model.content.restoreBackup(plan) }
                        }) { Text("确认还原", color = MiuixTheme.colorScheme.onPrimary) }
                    }
                }
            }
            Scaffold(topBar = {
                TopAppBar(title = pageTitle(route), navigationIcon = {
                    IconButton(onClick = { back() }) { UiIcon("back", "返回") }
                })
            }) { padding ->
                if (route == "emoji_shortcuts") {
                    EmojiShortcutScreen(Modifier.padding(padding))
                    return@Scaffold
                }
                if (route == "symbol_keyboard") {
                    SymbolKeyboardScreen(Modifier.padding(padding))
                    return@Scaffold
                }
                if (route == "symbol_closure") {
                    SymbolClosureScreen(Modifier.padding(padding))
                    return@Scaffold
                }
                if (route == "builtin_ai_prompts") {
                    BuiltinAiPromptScreen(Modifier.padding(padding))
                    return@Scaffold
                }
                if (route == "builtin_dictionaries") {
                    BuiltinDictionaryScreen(Modifier.padding(padding), builtinModel)
                    return@Scaffold
                }
                key(route) {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
                        contentPadding = SettingsPagePadding,
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        item {
                            when (route) {
                                "dashboard" -> Dashboard()
                                "home", "settings" -> Home()
                                "input" -> InputSettings()
                                "input_english" -> EnglishSettings()
                                "input_fuzzy" -> FuzzySettings()
                                "keyboard", "feedback" -> KeyboardSettings()
                                "behavior" -> Appearance()
                                "layout" -> Layouts()
                                "toolbar" -> ToolbarSettings()
                                "voice" -> VoiceSettings()
                                "api" -> ApiSettings()
                                "custom_ai" -> Group("我的自定义 AI") {
                                    Info("保存常用指令，在 AI 助手中按名称选择。")
                                    Action("添加自定义 AI", !model.busy) { model.edit = ContentEdit("custom_ai") }
                                    if (model.aiPresets.isEmpty()) Info("暂无自定义 AI")
                                }
                                "cleanup", "recovery" -> Group("本机内容") {
                                    Info("包含：中文和英文自定义词库、常用语、自定义 AI、主题颜色、主题 UI 设计、4 个布局配置、工具栏、符号自动闭合、符号键盘、字母长按符号、内置 AI 提示词、快捷 emoji 表情。")
                                    Info("不含：API 密钥、剪贴板、会话记录、内置词库文件和语音模型。备份文件未加密。")
                                    Action("备份数据", !model.busy) {
                                        launch { backupPicker.launch("S-Key-backup-${java.time.LocalDate.now()}.json") }
                                    }
                                    Action("还原数据", !model.busy) {
                                        launch { restorePicker.launch(arrayOf("application/json", "application/octet-stream", "text/*")) }
                                    }
                                    Info("清理后保留内置词库、主题颜色、主题 UI 设计、布局、输入设置和快捷表情配置。")
                                    Nav("清除用户内容数据", "自定义词库、常用语与剪贴板（含置顶）、最近表情、自定义 AI", "delete") {
                                        ask("确定清除用户内容数据？中文和英文自定义词库、常用语（含置顶）、剪贴板（含置顶）、最近表情、自定义 AI 和 AI 会话将删除。内置词库、主题 UI 设计及其它设置保留。此操作无法撤销。") { model.task { model.content.clearAll() } }
                                    }
                                }
                                "emoji" -> Group("最近表情") {
                                    Info(model.emoji.joinToString("  ").ifEmpty { "还没有最近使用的表情" })
                                    Action("清空最近表情") { ask("清空最近表情？") { model.task { model.content.clearEmoji() } } }
                                }
                                "licenses" -> Licenses()
                                "about" -> AboutApp()
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
                                        model.edit = ContentEdit("custom_ai", preset.id, TextFieldValue(preset.name), TextFieldValue(preset.prompt), preset.reasoningEffort)
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
                Text(text, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                SettingsDialogActions(cancel = { confirm = null }, confirm = { confirm = null; action() }, label = "确认", enabled = !model.busy)
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
            Nav(if (selectedIme) "正在使用 S Key" else "切换到 S Key", icon = "globe") {
                getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            }
            var trial by remember { mutableStateOf("") }
            TextField(value = trial, onValueChange = { trial = it }, label = "在这里试打…", modifier = Modifier.fillMaxWidth().padding(16.dp))
        }
        Group("开始定制") {
            Nav("偏好设置", "输入、键盘、主题与本机内容", "settings") { openPage("home") }
            Nav("输入方式", "五笔、拼音与英文补全", "input") { openPage("input") }
            Nav("主题工作室", "深浅主题与自定义配色", "theme") { openPage("behavior") }
        }
        Group("应用与隐私") {
            Nav("开源许可", "Miuix、图标、词库与语音组件", "dictionary") { openPage("licenses") }
            Nav("关于应用", "版本信息、项目与开发者", "settings") { openPage("about") }
        }
    }
    @Composable private fun Home() {
        model.revision
        Group("输入与外观") {
            Nav("输入方式", "五笔、拼音与英文补全", "input") { openPage("input") }
            Nav("键盘操作", "声音、触感、长按、工具栏", "keyboard") { openPage("keyboard") }
            Nav("主题与外观", "自定义配色、黑白切换、跟随系统", "theme") { openPage("behavior") }
            Nav("布局配置", "横竖屏、折叠屏、悬浮与分离", "layout") { openPage("layout") }
        }
        Group("内置设置") {
            Nav("内置词库", "五笔、拼音、英文 · 编辑与编译", "dictionary") { openPage("builtin_dictionaries") }
            Nav("内置 AI 提示词", "助手指令与自定义 AI 通用指令", "ai") { openPage("builtin_ai_prompts") }
            Nav("符号自动闭合", "输入符号、闭合符号与光标回移", "keyboard") { openPage("symbol_closure") }
            Nav("符号键盘", "中英文符号页与字母长按", "keyboard") { openPage("symbol_keyboard") }
            Nav("快捷 emoji 表情", "表情与快捷输入关键词", "emoji") { openPage("emoji_shortcuts") }
        }
        Group("本机内容") {
            Nav("剪贴板", "${model.clipboard.size} 条", "clipboard") { openPage("clipboard") }
            Nav("常用语", "${model.phrases.size} 条", "phrases") { openPage("phrases") }
            Nav("中文自定义词库", "${model.chinese.size} 条 · 手动短语", "dictionary") { openPage("dictionary") }
            Nav("英文自定义词库", "${model.english.size} 条", "english_dictionary") { openPage("english_dictionary") }
            Nav("最近表情", "${model.emoji.size} / 50", "emoji") { openPage("emoji") }
        }
        Group("应用与隐私") {
            Nav("AI 设置", "Google 翻译 · Grok · 模型选择", "input") { openPage("api") }
            Nav("语音输入", "离线语音 / AI 语音", "voice") { openPage("voice") }
            Nav("内容清理", "保留主题与设置", "delete") { openPage("cleanup") }
            Nav("开源许可", "Miuix、图标、词库与语音组件", "dictionary") { openPage("licenses") }
            Nav("关于应用", "版本信息、项目与开发者", "settings") { openPage("about") }
        }
    }
    @Composable private fun AboutApp() {
        val version = remember { packageManager.getPackageInfo(packageName, 0).versionName.orEmpty() }
        Group("S Key") {
            Column(Modifier.fillMaxWidth().padding(vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AndroidView(factory = { context -> android.widget.ImageView(context).apply {
                    setImageDrawable(context.packageManager.getApplicationIcon(context.packageName))
                    contentDescription = "S Key 应用图标"
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                } }, modifier = Modifier.size(80.dp))
                Text("S Key", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Text("版本 $version", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
            }
        }
        Group("项目与开发者") {
            Nav("项目地址", "github.com/LinnBenson/S-Key", "open_link") {
                launch { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/LinnBenson/S-Key"))) }
            }
            Nav("开发者主页", "github.com/LinnBenson", "open_link") {
                launch { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/LinnBenson"))) }
            }
        }
    }

    @Composable private fun ApiSettings() {
        model.revision
        val draft = model.apiDraft
        var showKeys by remember { mutableStateOf(false) }
        @Composable fun field(label: String, value: String, secret: Boolean = false, change: (String) -> Unit) {
            TextField(value = value, onValueChange = change, label = label,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = if (secret) androidx.compose.ui.text.input.KeyboardType.Password
                        else androidx.compose.ui.text.input.KeyboardType.Text,
                    autoCorrectEnabled = false,
                ),
                visualTransformation = if (secret && !showKeys) androidx.compose.ui.text.input.PasswordVisualTransformation()
                    else androidx.compose.ui.text.input.VisualTransformation.None,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
        }
        Group("使用设置") {
            Nav("自定义 AI", "${model.aiPresets.size} 个 · 提示词与排序", "ai") { openPage("custom_ai") }
            Toggle("AI 生成后自动上屏", model.settings.aiAutoApply, "开启后直接插入结果；有选中文字时替换选中内容", icon = "enter") { model.settings.aiAutoApply = it }
            Toggle("语音自动翻译", model.settings.voiceAutoTranslate, "在英文键盘下，将 AI 语音识别结果译为英文", icon = "translate") { model.settings.voiceAutoTranslate = it }
        }
        Group("Google 翻译 API") {
            field("翻译接口地址", draft.googleUrl) { model.apiDraft = draft.copy(googleUrl = it) }
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
            Info("可获取模型列表，也可填写模型 ID。接口需支持 Responses API。")
            Info("会话可续聊 12 小时；清空原文或切换类型会重新开始。xAI 保留输入与回答 30 天，本机重置不会删除云端记录。其他服务的保存期限以服务商为准。")
        }
        Group("AI 语音 API") {
            field("语音识别端点（完整 HTTPS 地址）", draft.voiceUrl) { model.apiDraft = draft.copy(voiceUrl = it) }
            field("语音 API Key", draft.voiceKey, true) { model.apiDraft = draft.copy(voiceKey = it) }
            field("语音模型 ID", draft.voiceModel) { model.apiDraft = draft.copy(voiceModel = it) }
            Action("使用 Grok 地址与密钥") {
                model.apiDraft = draft.copy(voiceUrl = draft.grokUrl.trimEnd('/') + "/stt", voiceKey = draft.grokKey)
            }
            Info("请填写语音识别模型。默认使用 xAI /stt；其他接口需支持 WAV multipart 上传并返回 text。")
        }
        Group("保存配置") {
            Toggle("显示密钥", showKeys) { showKeys = it }
            Action("保存 API 配置", !model.busy) {
                val snapshot = model.apiDraft
                model.task(refreshContent = false, success = { model.message = "API 配置已保存" }) { model.apiStore.save(snapshot) }
            }
            Action("重新载入已保存配置", !model.busy) { model.loadApi(); model.apiModels = emptyList() }
            Action("清除已保存的 API 配置", !model.busy) {
                model.task(refreshContent = false, success = { model.apiDraft = ApiConfiguration(); model.apiModels = emptyList(); model.message = "API 配置已清除" }) { model.apiStore.clear() }
            }
            Info("密钥在本机加密保存。点击翻译或生成时才发送原文和指令；获取模型列表不发送输入内容。AI 语音在收音结束后上传录音，音频不保存在本机。")
        }
    }

    @Composable private fun InputSettings() {
        model.revision
        val s = model.settings
        Group("输入选项") {
            HorizontalChoices(listOf("wubi" to "五笔", "pinyin" to "拼音"), s.chineseInputMethod.id) {
                s.chineseInputMethod = io.secure.keyboard.engine.ChineseInputMode.fromId(it)
            }
        }
        Group("中文输入偏好") {
            Toggle("简繁转换", s.traditionalOutput) { s.traditionalOutput = it }
            Toggle("自动插入空格", s.autoChineseSpacing) { s.autoChineseSpacing = it }
            if (s.chineseInputMethod == io.secure.keyboard.engine.ChineseInputMode.WUBI) {
                Toggle("五笔四码自动上屏", s.fourCodeAutoCommit) { s.fourCodeAutoCommit = it }
                Toggle("五笔拼音混输入", s.wubiPinyinInput) { s.wubiPinyinInput = it }
            }
            Nav("模糊拼音", if (s.fuzzyPinyin) "已开启 · ${s.fuzzyPairs.size} 项" else "已关闭", "input") { openPage("input_fuzzy") }
        }
        EnglishSettings()
    }
    @Composable private fun FuzzySettings() {
        model.revision
        val s = model.settings
        Group("使用设置") {
            Toggle("模糊拼音", s.fuzzyPinyin, "用于拼音和五笔拼音混输入；关闭后保留各项选择") { s.fuzzyPinyin = it }
        }
        listOf("声母模糊" to SettingsStore.fuzzyInitialIds, "韵母模糊" to SettingsStore.fuzzyFinalIds).forEach { (title, pairs) ->
            Group(title) {
                pairs.forEach { pair ->
                    Toggle(pair.replace("_", " ↔ "), pair in s.fuzzyPairs) {
                        s.fuzzyPairs = if (it) s.fuzzyPairs + pair else s.fuzzyPairs - pair
                    }
                }
            }
        }
    }
    @Composable private fun EnglishSettings() {
        model.revision
        Group("英文输入偏好") {
            Toggle("空格选择首选词", model.settings.englishSpaceSelectsFirst) { model.settings.englishSpaceSelectsFirst = it }
            Toggle("英文单词补全", model.settings.englishCompletion) { model.settings.englishCompletion = it }
        }
    }
    @Composable private fun KeyboardSettings() {
        model.revision
        val s = model.settings
        Group("按键反馈") {
            Toggle("按键声音", s.keySound) { s.keySound = it }
            Toggle("按键振动", s.vibration) { s.vibration = it }
            Range("振动强度", s.vibrationStrength, 1, 100, "%") { s.vibrationStrength = it }
        }
        Group("手势与长按") {
            Toggle("长按输入符号", s.longPressSymbols) { s.longPressSymbols = it }
            Range("长按延迟", s.longPressDelay, 150, 1500, "ms", 10) { s.longPressDelay = it }
            Toggle("滑动退格删除", s.swipeDelete) { s.swipeDelete = it }
            Nav("定制工具栏", "固定工具与排列顺序", "more") { openPage("toolbar") }
        }
        Group("剪贴板设置") {
            Toggle("快捷粘贴", s.quickPaste) { s.quickPaste = it }
        }
    }
    @Composable private fun Appearance() {
        model.revision
        val s = model.settings
        Group("默认主题与切换") {
            HorizontalChoices(listOf("system" to "跟随系统", "light" to "白色主题", "dark" to "黑色主题"), s.theme) {
                if (it != "system") s.manualDark = it == "dark"
                s.theme = it
            }
            Nav("自定义白色主题", "调整键盘与设置页配色", "theme") { openPage("theme_light") }
            Nav("自定义黑色主题", "调整键盘与设置页配色", "theme") { openPage("theme_dark") }
        }
        Group("文字与提示") {
            Range("候选栏字号", s.candidateTextSize, 12, 32, "sp") { s.candidateTextSize = it }
            Range("展开候选字号", s.expandedCandidateTextSize, 12, 32, "sp") { s.expandedCandidateTextSize = it }
            HorizontalChoices(listOf("all" to "全部按键角标", "digits" to "仅数字角标", "hidden" to "隐藏角标"), s.symbolHints) {
                s.symbolHints = it
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
        Group("布局形态") {
            Toggle("悬浮键盘", p.floating) { save(p.copy(floating = it)) }
            if (!outer) {
                Toggle("分离键盘", p.split) { save(p.copy(split = it)) }
                Range("分离间距", p.splitGapDp, 0, 180, "dp") { save(p.copy(splitGapDp = it)) }
            }
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
                KeyboardThemePreview(tokens, s.toolbarPins, s.symbolHints, borderWidth = s.keyBorderWidth,
                    horizontalGap = s.keyHorizontalGap, verticalGap = s.keyVerticalGap,
                    cornerRadius = s.keyCornerRadius, enterCornerRadius = s.enterCornerRadius)
            }
        }
        Group("UI 设计") {
            DesignRange("边框宽度", s.keyBorderWidth, 4) { s.keyBorderWidth = it }
            DesignRange("横向间距", s.keyHorizontalGap, 16) { s.keyHorizontalGap = it }
            DesignRange("竖向间距", s.keyVerticalGap, 16) { s.keyVerticalGap = it }
            DesignRange("按钮圆角", s.keyCornerRadius, 30) { s.keyCornerRadius = it }
            DesignRange("回车键圆角", s.enterCornerRadius, 30) { s.enterCornerRadius = it }
            Action("恢复默认 UI 设计") { s.resetUiDesign() }
        }
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
                        borderWidth = s.keyBorderWidth, horizontalGap = s.keyHorizontalGap, verticalGap = s.keyVerticalGap,
                        cornerRadius = s.keyCornerRadius, enterCornerRadius = s.enterCornerRadius,
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
                HorizontalChoices(listOf("none" to "不保存历史", "timed" to "定时清理", "forever" to "长期保留"), model.settings.clipboardRetention) { id ->
                    model.task { model.settings.clipboardRetention = id; model.content.trimClipboard() }
                }
                if (model.settings.clipboardRetention == "timed") {
                    Range("保留时间", model.settings.clipboardRetentionHours, 1, 720, "小时") { value ->
                        model.settings.clipboardRetentionHours = value
                        model.task { model.content.trimClipboard() }
                    }
                }
                Range("未置顶历史上限", model.settings.clipboardLimit, 1, 1000, "条") { value ->
                    model.settings.clipboardLimit = value
                    model.task { model.content.trimClipboard() }
                }
                Info("置顶内容单独保留，不参与自动清理。不保存历史时，已有未置顶记录会删除；快捷粘贴仍可使用。")
                if (model.settings.clipboardRetention == "timed") Info("按复制时间清理。系统休眠可能延后执行，打开历史时会再次清理。")
                Action("清空剪贴板历史") { ask("清空剪贴板历史？如果系统剪贴板内容也在历史中，将一并清空。") { model.task { model.content.clearClipboard() } } }
                if (model.clipboard.isEmpty()) Info("还没有剪贴板记录")
            } else {
                Action(if (route == "phrases") "添加常用语" else "添加词条", !model.busy) { model.edit = ContentEdit(route) }
                if (route != "phrases") {
                    val kind = if (route == "dictionary") DictionaryKind.CHINESE else DictionaryKind.ENGLISH
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
                Spacer(Modifier.height(8.dp))
                AiEditorField("名称", "例如：邮件润色", edit.first, 40, false) {
                    model.edit = edit.copy(first = it)
                }
                Spacer(Modifier.height(4.dp))
                AiEditorField("提示词", "描述你希望 AI 如何处理原文…", edit.second, ContentStore.MAX_AI_PROMPT_CHARACTERS, true) {
                    model.edit = edit.copy(second = it)
                }
                Spacer(Modifier.height(8.dp))
                Text("推理强度", fontSize = 14.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    io.secure.keyboard.data.AiReasoning.options.forEach { (value, label) ->
                        val selected = edit.reasoningEffort == value
                        Box(Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface.copy(alpha = .06f))
                            .clickable(enabled = !model.busy, role = androidx.compose.ui.semantics.Role.RadioButton) {
                                model.edit = edit.copy(reasoningEffort = value)
                            }, contentAlignment = Alignment.Center) {
                            Text(label, color = if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface)
                        }
                    }
                }
                Text("较高强度可能需要更长时间，部分模型不支持。", fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary)
                Spacer(Modifier.height(8.dp))
                val canSave = !model.busy && edit.first.text.isNotBlank() && edit.second.text.isNotBlank()
                SettingsDialogActions(cancel = { model.edit = null }, confirm = { model.saveEdit() }, enabled = canSave, cancelEnabled = !model.busy)
            }
            return
        }
        Modal(title, { if (!model.busy) model.edit = null }) {
            TextField(value = edit.first, onValueChange = { if (!customAi || it.text.length <= 40) model.edit = edit.copy(first = it) },
                label = if (customAi) "名称，例如：邮件润色" else if (edit.category == "phrases") "常用语" else "词语",
                singleLine = customAi, modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp))
            if (edit.category != "phrases") TextField(value = edit.second, onValueChange = { if (!customAi || it.text.length <= ContentStore.MAX_AI_PROMPT_CHARACTERS) model.edit = edit.copy(second = it) },
                label = if (customAi) "自定义提示词" else "拼音 / 编码",
                modifier = if (customAi) Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 260.dp) else Modifier.fillMaxWidth(), singleLine = !customAi)
            if (customAi) Info("${edit.first.text.length}/40 名称 · ${edit.second.text.length}/${ContentStore.MAX_AI_PROMPT_CHARACTERS} 提示词")
            SettingsDialogActions(cancel = { model.edit = null }, confirm = { model.saveEdit() },
                enabled = !model.busy && edit.first.text.isNotBlank(), cancelEnabled = !model.busy)
        }
    }
    @Composable private fun AiEditorField(title: String, placeholder: String, value: TextFieldValue,
        limit: Int, multiline: Boolean, change: (TextFieldValue) -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface)
                Text("${value.text.length}/$limit", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
            }
            TextField(value = value, onValueChange = { if (it.text.length <= limit) change(it) },
                enabled = !model.busy, singleLine = !multiline, label = placeholder,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = title }
                    .heightIn(min = if (multiline) 164.dp else 48.dp, max = if (multiline) 228.dp else 80.dp))
        }
    }
    @Composable private fun VoiceSettings() {
        model.revision
        val ai = model.settings.voiceMode == "ai"
        Group("识别方式") {
            HorizontalChoices(listOf("offline" to "离线语音", "ai" to "AI 语音"), model.settings.voiceMode) {
                model.settings.voiceMode = it
            }
            Info(if(ai) "录音上传至 AI 识别，需要联网。" else "本地识别普通话，录音和转写均不上传。")
        }
        if(ai) {
            Group("AI 语音") {
                Info("点击“识别语音”上传录音，上屏后继续收音，点击取消结束。也可长按空格说话，松手识别。单次最长 60 秒。")
                Nav("AI 设置", "配置语音端点、密钥与模型", "settings") { openPage("api") }
                Action("允许麦克风权限", !microphone) { micPicker.launch(Manifest.permission.RECORD_AUDIO) }
            }
            return
        }
        val installed = remember(voiceState) { ModelRepository(applicationContext).isInstalled() }
        Group("离线普通话") {
            Info("模型：${if (installed) "已导入" else "未导入"} · 麦克风：${if (microphone) "已允许" else "未允许"}", true)
            Info("点击工具栏语音，或长按空格开始听写。录音不保存，识别结果不上传。")
            Info("下载约 25 MB 的模型并解压，导入含 model.int8.onnx 和 tokens.txt 的文件夹。")
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
                    .joinToString("\n\n") { assets.open("voice-licenses/$it").bufferedReader().use { reader -> reader.readText() } } +
                    "\n\nOpenCC 1.1.9 · 简转繁数据\n" + assets.open("opencc/LICENSE").bufferedReader().use { it.readText() }
            }
        }
        Group("开源组件") {
            Info("Miuix 0.5.1 · Apache-2.0\nMiuix Icons Regular / Material Symbols Rounded · Apache-2.0\nOkHttp 4.12.0、Okio 3.6.0 · Apache-2.0\nKotlin、AndroidX / Compose：随包保留各组件许可。\n词库及字体的来源与许可保留在源码 third_party 与应用 assets。")
            Info(notices)
        }
    }
    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("S Key", text))
        model.message = "已复制"
    }
    @Composable private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
        SettingsSection(title, content = content)
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
    @Composable private fun Toggle(title: String, checked: Boolean, summary: String? = null, icon: String? = null, change: (Boolean) -> Unit) {
        BasicComponent(title = title, summary = summary,
            leftAction = { if (icon != null) Box(Modifier.padding(end = 16.dp)) { UiIcon(icon, null) } },
            onClick = { change(!checked) },
            rightActions = { Switch(checked = checked, onCheckedChange = change) })
    }
    @Composable private fun Choice(title: String, checked: Boolean, action: () -> Unit) {
        BasicComponent(title = title, onClick = action, rightActions = { if (checked) UiIcon("check", "已选择") })
    }
    @Composable private fun DesignRange(title: String, value: Int, maximum: Int, save: (Int) -> Unit) {
        val onSave by rememberUpdatedState(save)
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text("$title  $value dp")
            Slider(progress = value.toFloat(), minValue = 0f, maxValue = maximum.toFloat(),
                decimalPlaces = 0, onProgressChange = { onSave(it.roundToInt().coerceIn(0, maximum)) },
                modifier = Modifier.semantics {
                    contentDescription = title
                    progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(value.toFloat(), 0f..maximum.toFloat(), maximum - 1)
                    setProgress { onSave(it.roundToInt().coerceIn(0, maximum)); true }
                })
        }
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
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
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
            Card(io.secure.keyboard.settings.settingsDialogModifier()) {
                Column(settingsDialogContentModifier(), verticalArrangement = Arrangement.spacedBy(SettingsDialogSpacing)) {
                    Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    content()
                }
            }
        }
    }
    @Composable private fun UiIcon(kind: String, description: String?) {
        if (kind == "script" || kind == "input_mode") {
            Text(if (kind == "input_mode") model.settings.chineseInputMode.glyph else if (model.settings.traditionalOutput) "繁" else "简", modifier = Modifier.size(24.dp))
            return
        }
        Icon(painter = painterResource(io.secure.keyboard.ui.KeyboardIcons.resource(kind)), contentDescription = description,
            modifier = Modifier.size(24.dp), tint = MiuixTheme.colorScheme.onSurface)
    }
    private fun pageTitle(route: String): String = when(route) {
        "symbol_keyboard" -> "符号键盘"
        "symbol_closure" -> "符号自动闭合"
        "builtin_ai_prompts" -> "内置 AI 提示词"
        "builtin_dictionaries" -> "内置词库"
        "dashboard" -> "S Key"
        "input" -> "输入方式"
        "input_english" -> "英文设置"
        "input_fuzzy" -> "模糊拼音"
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
        "emoji_shortcuts" -> "快捷 emoji 表情"
        "emoji" -> "最近表情"
        "ai" -> "AI 助手"
        "translate" -> "翻译"
        "api" -> "AI 设置"
        "voice" -> "语音输入"
        "cleanup", "recovery" -> "内容清理"
        "licenses" -> "开源声明"
        "about" -> "关于应用"
        else -> if (route.startsWith("profile_")) "${if (route.contains("outer")) "外屏" else "内屏"} · ${if (route.endsWith("landscape")) "横屏" else "竖屏"}" else "偏好设置"
    }
    private fun toolName(id: String) = when(id) {
        "input_mode" -> "输入模式"; "script" -> "简繁转换"; "floating" -> "悬浮键盘"; "split" -> "分离键盘"; "theme" -> "切换主题"
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
