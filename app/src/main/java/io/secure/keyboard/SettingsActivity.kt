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
    private var settingsVisible by mutableStateOf(false)
    private var incomingPage by mutableStateOf<String?>(null)
    private val pageHistory = ArrayList<String>()
    private var voiceState by mutableStateOf<ModelImportCoordinator.State>(ModelImportCoordinator.State.Idle)
    private var microphone by mutableStateOf(false)
    private var enabledIme by mutableStateOf(false)
    private var selectedIme by mutableStateOf(false)
    private val voiceObserver: (ModelImportCoordinator.State) -> Unit = { voiceState = it }
    // Keep document callbacks registered while the privacy-sensitive Compose UI is hidden.
    private val dictionaryPackagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { builtinModel.importPackage(it) }
    }
    private val dictionaryPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { builtinModel.importFile(it) }
    }
    private val dictionaryExporter = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        builtinModel.exportFile(uri)
    }
    private val micPicker = registerForActivityResult(ActivityResultContracts.RequestPermission()) { microphone = it }
    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { ModelImportTransfer.start(applicationContext, it) }
    }
    private var backupRequest by mutableStateOf<Pair<Uri, Boolean>?>(null)
    private val restorePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) backupRequest = uri to false
    }
    private val backupPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) backupRequest = uri to true
    }

    private fun discardBackupRequest() {
        val pending = backupRequest
        backupRequest = null
        if (pending?.second == true) CanceledBackupDocument.discard(applicationContext, pending.first)
    }

    override fun onDestroy() {
        discardBackupRequest()
        super.onDestroy()
    }

    @Composable private fun BackupPasswordDialog(request: Pair<Uri, Boolean>) {
        var password by remember { mutableStateOf("") }
        var confirmation by remember { mutableStateOf("") }
        val exporting = request.second
        val protect = remember(model.revision) { model.settings.screenshotProtection }
        Dialog(onDismissRequest = { discardBackupRequest() }, properties = DialogProperties(
            usePlatformDefaultWidth = false, securePolicy = if (protect) androidx.compose.ui.window.SecureFlagPolicy.SecureOn else androidx.compose.ui.window.SecureFlagPolicy.SecureOff)) {
            Card(settingsDialogModifier()) {
                Column(settingsDialogContentModifier(), verticalArrangement = Arrangement.spacedBy(SettingsDialogSpacing)) {
                    Text(if (exporting) "设置备份密码" else "输入备份密码", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    if (!exporting) Text("验证密码后预览备份内容，确认后才会还原。新备份会还原截屏保护等偏好；旧备份缺失的偏好保持当前值。")
                    @Composable fun secret(label: String, value: String, update: (String) -> Unit) {
                        TextField(value = value, onValueChange = { if (it.length <= 1024) update(it) }, label = label,
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, autoCorrectEnabled = false))
                    }
                    secret("密码", password) { password = it }
                    if (exporting) secret("再次输入密码", confirmation) { confirmation = it }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(text = "取消", modifier = Modifier.weight(1f), onClick = { discardBackupRequest() })
                        Button(modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary(),
                            enabled = password.isNotEmpty() && (!exporting || password == confirmation), onClick = {
                                val secret = password.toCharArray()
                                password = ""; confirmation = ""; backupRequest = null
                                var plan: ContentStore.BackupPlan? = null
                                model.fileTask(success = {
                                    if (exporting) model.message = "加密备份已导出" else model.pendingBackup = plan
                                }) {
                                    try {
                                        model.awaitApiWrites()
                                        if (exporting) {
                                            (contentResolver.openOutputStream(request.first, "wt") ?: error("无法写入备份")).use {
                                                model.content.writeEncryptedBackup(it, secret)
                                            }
                                        } else {
                                            (contentResolver.openInputStream(request.first) ?: error("无法读取备份")).use {
                                                plan = model.content.prepareEncryptedBackup(it, secret)
                                            }
                                        }
                                    } catch (_: Exception) {
                                        error(if (exporting) "备份失败，请重新导出；请勿使用未完成的文件。"
                                            else "密码错误、文件损坏或格式不支持。尚未修改数据，请重新选择文件并输入密码。")
                                    } finally { secret.fill('\u0000') }
                                }
                            }) { Text(if (exporting) "加密导出" else "验证密码", color = MiuixTheme.colorScheme.onPrimary) }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingPage = savedInstanceState?.getString("page") ?: intent.getStringExtra("page") ?: initialPage
        pageHistory.addAll(savedInstanceState?.getStringArrayList("pageHistory").orEmpty())
        io.secure.keyboard.ui.SensitiveWindows.apply(window, io.secure.keyboard.ui.SensitiveWindows.settingsPage(incomingPage ?: initialPage))
        setContent { ApplicationUi() }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        pageHistory.clear()
        model.edit = null
        incomingPage = intent.getStringExtra("page") ?: initialPage
        model.showPage(incomingPage ?: initialPage)
    }
    override fun onStart() {
        super.onStart()
        io.secure.keyboard.ui.SensitiveWindows.apply(window,
            io.secure.keyboard.ui.SensitiveWindows.settingsPage(incomingPage ?: initialPage) ||
                model.edit != null || model.pendingBackup != null || backupRequest != null)
        settingsVisible = true
        ModelImportTransfer.attach(voiceObserver)
    }
    override fun onStop() { settingsVisible = false; model.hide(); ModelImportTransfer.detach(voiceObserver); super.onStop() }
    override fun onResume() {
        super.onResume()
        // A covering Activity can finish before this Activity reaches onStop. In that case
        // Android resumes us without another onStart; reclaim the currently visible observer.
        ModelImportTransfer.attach(voiceObserver)
        microphone = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val manager = getSystemService(InputMethodManager::class.java)
        enabledIme = manager.enabledInputMethodList.any { it.packageName == packageName }
        selectedIme = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/') == packageName
        model.showPage(incomingPage ?: initialPage)
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
        if (!settingsVisible) return
        val revision = model.revision
        val config = model.settings
        val screenshotProtection = remember(revision) { config.screenshotProtection }
        val backgroundClipboard = remember(revision) { config.backgroundClipboard }
        val systemDark = LocalConfiguration.current.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val dark = remember(revision, systemDark) { config.isDark(systemDark) }
        val tokens = remember(revision, dark) { config.themeTokens(dark) }
        val route = incomingPage ?: initialPage
        SideEffect { io.secure.keyboard.ui.SensitiveWindows.apply(window,
            io.secure.keyboard.ui.SensitiveWindows.settingsPage(route) || model.edit != null || model.pendingBackup != null || backupRequest != null) }
        LaunchedEffect(route) { model.showPage(route) }
        var editorBack by remember(route) { mutableStateOf<(() -> Unit)?>(null) }
        var confirm by remember(route) { mutableStateOf<Pair<String, () -> Unit>?>(null) }
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
                route == "symbol_keyboard" -> "layout"
                route == "feedback" || route == "key_sound" -> "keyboard"
                route == "home" && initialPage == "dashboard" -> "dashboard"
                route == "dashboard" || route == "home" || (route == "voice" && initialPage == "voice") -> { finish(); return }
                else -> "home"
            }
        }
        BackHandler { if (model.edit != null) model.edit = null else editorBack?.invoke() ?: back() }
        SKeyTheme(tokens, dark) {
            SideEffect {
                val background = android.graphics.Color.parseColor(tokens.keyboardBackground)
                window.statusBarColor = background
                io.secure.keyboard.ui.styleSystemBars(window, android.graphics.Color.luminance(background) < .5f, background)
            }
            backupRequest?.let { request -> key(request) { BackupPasswordDialog(request) } }
            model.pendingBackup?.let { plan ->
                Modal("还原预览", { if (!model.busy) model.pendingBackup = null }) {
                    Text(plan.summary, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(text = "取消", enabled = !model.busy, modifier = Modifier.weight(1f), onClick = { model.pendingBackup = null })
                        Button(enabled = !model.busy, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary(), onClick = {
                            model.task(success = { model.pendingBackup = null; model.message = "数据还原完成" }) { model.awaitApiWrites(); model.content.restoreBackup(plan) }
                        }) { Text("确认还原", color = MiuixTheme.colorScheme.onPrimary) }
                    }
                }
            }
            Scaffold(topBar = {
                if (route == "dashboard" || route.startsWith("theme_")) {
                    Spacer(Modifier.fillMaxWidth().statusBarsPadding())
                } else {
                    Row(
                        Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 56.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { editorBack?.invoke() ?: back() }) { UiIcon("previous", "返回") }
                        Text(
                            pageTitle(route),
                            modifier = Modifier.weight(1f).padding(start = 4.dp, end = 16.dp),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
            }) { padding ->
                if (route == "licenses") {
                    Licenses(Modifier.padding(padding))
                    return@Scaffold
                }
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
                    BuiltinDictionaryScreen(Modifier.padding(padding), builtinModel,
                        onImport={ launch { dictionaryPicker.launch(arrayOf("*/*")) } },
                        onImportPackage={ launch { dictionaryPackagePicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) } },
                        onExport={ filename -> launch { dictionaryExporter.launch(filename) } })
                    return@Scaffold
                }
                if (route == "toolbar") {
                    ToolbarEditor(model.settings, Modifier.padding(padding), ::toolName)
                    return@Scaffold
                }
                if (route.startsWith("theme_")) {
                    key(route) {
                        ThemeSettings(route, Modifier.padding(padding), { back() }, { editorBack = it })
                    }
                    return@Scaffold
                }
                key(route) {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
                        contentPadding = SettingsPagePadding,
                        verticalArrangement = Arrangement.spacedBy(if (route in listOf("phrases", "dictionary", "english_dictionary")) 0.dp else 16.dp)) {
                        item {
                            when (route) {
                                "dashboard" -> Dashboard()
                                "home", "settings" -> Home()
                                "input" -> InputSettings()
                                "input_english" -> EnglishSettings()
                                "input_fuzzy" -> FuzzySettings()
                                "keyboard", "feedback" -> KeyboardSettings()
                                "key_sound" -> KeySoundSettings()
                                "behavior" -> Appearance()
                                "layout" -> Layouts()
                                "voice" -> VoiceSettings()
                                "api" -> ApiSettings()
                                "custom_ai" -> Group("") {
                                    Action("添加自定义 AI", !model.busy) { model.edit = ContentEdit("custom_ai", retentionHours = model.aiConversationHours) }
                                    if (model.aiPresets.isEmpty()) Info("暂无自定义 AI")
                                }
                                "cleanup", "recovery" -> {
                                  Group("本机内容") {
                                    Info("包含：输入、语音、声音振动及隐私偏好、AI 接口地址、密钥和模型配置、中文和英文自定义词库、常用语、自定义 AI、主题颜色、主题 UI 设计、4 个布局配置、剪贴板保留设置、工具栏、符号自动闭合、符号键盘、字母长按符号、内置 AI 提示词、快捷 emoji 表情。")
                                    Action("备份数据", !model.busy) {
                                        launch { backupPicker.launch("S-Key-backup-${java.time.LocalDate.now()}.skeybackup") }
                                    }
                                    Action("还原数据", !model.busy) {
                                        launch { restorePicker.launch(arrayOf("*/*")) }
                                    }
                                    Nav("清除用户内容数据", "自定义词库、常用语与剪贴板（含置顶）、最近表情、自定义 AI", "delete") {
                                        ask("确定清除用户内容数据？中文和英文自定义词库、常用语（含置顶）、剪贴板（含置顶）、最近表情、自定义 AI 和 AI 会话将删除。内置词库、主题 UI 设计及其它设置保留。此操作无法撤销。") { model.task { model.content.clearAll() } }
                                    }
                                }
                                  Group("隐私保护") {
                                      Toggle("截屏保护", screenshotProtection) {
                                          config.screenshotProtection = it
                                      }
                                      Toggle("允许后台读取复制", backgroundClipboard) {
                                          config.backgroundClipboard = it
                                      }
                                  }
                                }
                                "emoji" -> Group("最近使用 · ${model.emoji.size} / 100") {
                                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Text("按最近使用排序", modifier = Modifier.weight(1f), fontSize = 14.sp,
                                            color = MiuixTheme.colorScheme.onSurfaceSecondary)
                                        IconButton(enabled = model.emoji.isNotEmpty(), onClick = {
                                            ask("清空最近表情？") { model.task { model.content.clearEmoji() } }
                                        }) { UiIcon("delete", "清空最近表情") }
                                    }
                                    if (model.emoji.isEmpty()) {
                                        Column(Modifier.fillMaxWidth().padding(vertical = 32.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("☺", fontSize = 40.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                                            Spacer(Modifier.height(12.dp))
                                            Text("还没有最近使用的表情", color = MiuixTheme.colorScheme.onSurfaceSecondary)
                                        }
                                    } else BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 16.dp)) {
                                        val columns = (maxWidth / 56.dp).toInt().coerceAtLeast(1)
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            model.emoji.chunked(columns).forEach { row ->
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    row.forEach { emoji ->
                                                        Box(Modifier.weight(1f).height(56.dp)
                                                            .clip(RoundedCornerShape(14.dp))
                                                            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.035f)),
                                                            contentAlignment = Alignment.Center) {
                                                            Text(emoji, fontSize = 32.sp)
                                                        }
                                                    }
                                                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                                }
                                            }
                                        }
                                    }
                                }
                                "about" -> AboutApp()
                                "privacy_data" -> PrivacyDataStatement()
                                else -> when {
                                    route.startsWith("profile_") -> ProfileSettings(route)
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
                                "删除" to { ask("删除这条常用语？") { model.task { model.content.deletePhrase(item.id) } } }), compact = true, first = item.id == model.phrases.firstOrNull()?.id, last = item.id == model.phrases.lastOrNull()?.id)
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
                                        model.edit = ContentEdit("custom_ai", preset.id, TextFieldValue(preset.name), TextFieldValue(preset.prompt), preset.reasoningEffort, preset.modelId, preset.retentionHours, preset.webSearch, preset.xSearch, preset.provider)
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
                                    "删除" to { ask("删除此词条？") { model.task { model.content.deleteDictionary(item.id, kind) } } }), compact = true, first = item.id == (if (kind == DictionaryKind.CHINESE) model.chinese else model.english).firstOrNull()?.id, last = item.id == (if (kind == DictionaryKind.CHINESE) model.chinese else model.english).lastOrNull()?.id)
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
        AppIdentity(spacious = true)
        Group("S Key") {
            Nav(if (enabledIme) "输入法已启用" else "启用输入法", "在 Android 设置中管理", "settings") {
                launch { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
            }
            Nav(if (selectedIme) "正在使用 S Key" else "切换到 S Key", icon = "globe") {
                getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            }
            var trial by remember { mutableStateOf("") }
            TextField(value = trial, onValueChange = { trial = it }, label = "尝试您的 S Key 输入法 ...", modifier = Modifier.fillMaxWidth().padding(16.dp))
        }
        Group("开始定制") {
            Nav("偏好设置", "输入、键盘、主题与本机内容", "settings") { openPage("home") }
            Nav("主题与外观", "配色、按键样式与文字提示", "theme") { openPage("behavior") }
            Nav("AI 设置", "API / 模型 / 提示词设置", "input") { openPage("api") }
            Nav("语音输入", "离线语音 / AI 语音", "voice") { openPage("voice") }
        }
        Group("应用与隐私") {
            Nav("备份与清理", "备份、还原与清理本机内容", "delete") { openPage("cleanup") }
            Nav("开源声明", "Miuix、图标、词库、音效与语音组件", "dictionary") { openPage("licenses") }
            Nav("关于应用", "版本信息、项目与隐私声明", "settings") { openPage("about") }
        }
    }
    @Composable private fun Home() {
        model.revision
        Group("输入与外观") {
            Nav("输入方式", "五笔、拼音与英文补全", "input") { openPage("input") }
            Nav("键盘操作", "声音、触感、长按、工具栏", "keyboard") { openPage("keyboard") }
            Nav("主题与外观", "配色、按键样式与文字提示", "theme") { openPage("behavior") }
            Nav("布局配置", "横竖屏、折叠屏、悬浮与分离", "layout") { openPage("layout") }
        }
        Group("内置设置") {
            Nav("内置词库", "五笔、拼音、英文 · 编辑与编译", "dictionary") { openPage("builtin_dictionaries") }
            Nav("符号自动闭合", "输入符号、闭合符号与光标回移", "keyboard") { openPage("symbol_closure") }
            Nav("快捷 emoji 表情", "表情与快捷输入关键词", "emoji") { openPage("emoji_shortcuts") }
        }
        Group("本机内容") {
            Nav("剪贴板", "管理复制记录", "clipboard") { openPage("clipboard") }
            Nav("常用语", "管理常用语", "phrases") { openPage("phrases") }
            Nav("中文自定义词库", "管理手动短语", "dictionary") { openPage("dictionary") }
            Nav("英文自定义词库", "管理英文词条", "english_dictionary") { openPage("english_dictionary") }
            Nav("最近表情", "管理最近使用的表情", "emoji") { openPage("emoji") }
        }
        Group("应用与隐私") {
            Nav("AI 设置", "API / 模型 / 提示词设置", "input") { openPage("api") }
            Nav("语音输入", "离线语音 / AI 语音", "voice") { openPage("voice") }
            Nav("备份与清理", "备份、还原与清理本机内容", "delete") { openPage("cleanup") }
            Nav("开源声明", "Miuix、图标、词库、音效与语音组件", "dictionary") { openPage("licenses") }
            Nav("关于应用", "版本信息、项目与隐私声明", "settings") { openPage("about") }
        }
    }
    @Composable private fun AppIdentity(spacious: Boolean = false) {
        val version = remember { packageManager.getPackageInfo(packageName, 0).versionName.orEmpty() }
            Column(Modifier.fillMaxWidth().padding(vertical = if (spacious) 36.dp else 20.dp),
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
    @Composable private fun AboutApp() {
        Group("S Key") { AppIdentity() }
        Group("项目声明") {
            Nav("项目地址", "github.com/LinnBenson/S-Key", "open_link") {
                launch { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/LinnBenson/S-Key"))) }
            }
            Nav("开发者主页", "github.com/LinnBenson", "open_link") {
                launch { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/LinnBenson"))) }
            }
            Nav("隐私数据声明", "数据用途、联网范围与管理方式", "settings") { openPage("privacy_data") }
        }
        CurrentInformation(applicationContext)
    }

    @Composable private fun PrivacyDataStatement() {
        Group("适用范围") {
            Info("本声明说明当前版本 S Key 的数据处理方式。普通键盘输入和本地词库查询在设备上完成；AI、在线翻译和 AI 语音会向你配置的服务发送相应内容。请在使用前核对接口地址及服务提供方。")
        }
        Group("键盘输入与本地功能") {
            Info("输入法会处理按键、组合中的文字、候选词、光标与选区；编辑、上下文辅助、AI 和翻译功能可能通过 Android 输入框接口读取选中文本或周边文本。上屏结果会交给当前输入框所属的应用，由该应用继续处理。普通输入不会自动作为 AI 请求上传。")
            Info("中文和英文自定义词库、常用语、最近表情、自定义 AI 提示词及界面设置用于本地查询、快捷输入和恢复你的偏好。常用语和最近表情记录在本机加密保存；最近表情会记录使用过的表情，不会因此上传整个输入历史。")
        }
        Group("剪贴板") {
            Info("系统允许时，S Key 会读取新复制的文本，用于快捷粘贴和剪贴板历史。“允许后台读取复制”默认开启，输入法服务运行且系统允许读取时，收起键盘也可能记录复制内容；关闭后，仅在键盘显示期间记录新复制内容，不补录隐藏期间的内容。")
            Info("剪贴板历史（包括置顶内容）在本机加密保存。你可以选择不保存历史、定时清理或长期保留，并受数量上限限制。置顶内容单独保留，不参与自动清理；系统休眠可能延迟清理。系统标记为敏感的剪贴板内容会被过滤。复制内容不会仅因进入历史而发送到在线服务；当你将其用于 AI 或翻译时，相应文本会被发送。")
        }
        Group("AI 助手与会话") {
            Info("执行润色、扩写、AI 翻译或自定义 AI 时，会向配置的 AI 接口发送待处理文本、提示词、模型与推理设置；选中图片时也会发送处理后的图片。继续会话会发送新要求和会话标识，服务端可关联此前上下文。API 密钥随请求用于鉴权。")
            Info("当前 AI 请求会要求服务端保存响应，以支持多轮会话。启用自定义 AI 的网页搜索或 X 搜索时，服务方还可能调用相关检索工具处理内容。本机加密保存首条消息、会话标题、会话标识及相关任务设置，用于历史列表和续聊，并按会话有效期管理；这不是完整的逐轮聊天记录。清除本机历史或会话过期不代表服务端数据已删除。")
        }
        Group("在线翻译") {
            Info("使用翻译模式时，待翻译文本、源语言、目标语言和模型设置会发送到配置的翻译接口，API 密钥用于鉴权。翻译模式内的输入可能自动触发请求，无需每次再点发送。默认接口为 Google 翻译，也可能是你自行配置的服务。")
        }
        Group("语音与离线模型") {
            Info("语音功能需麦克风权限。离线语音使用本机模型处理音频；AI 语音会把本次录音发送到配置的语音识别接口。音频用于当前识别流程，不作为应用录音历史保存。识别文字可用于当前输入，若随后使用 AI 或在线翻译，则会按相应功能发送。")
            Info("开启“语音自动翻译”后，在英文键盘使用语音可能进一步把识别文本发送到翻译接口。离线模型安装需要从模型下载服务器联网获取文件，或读取你选择的本地模型文件；下载模型不需要上传你的录音或输入文字。")
        }
        Group("图片与文件权限") {
            Info("图片功能会在授权范围内读取相册列表、缩略图和选中的图片；系统支持时可只授权部分照片。用于 AI 的图片在本机解码、调整方向并重新编码后，随你发起的 AI 请求上传。应用不因此自动上传整个相册，也不把这些图片作为会话图片缓存写入磁盘。")
            Info("导入词库、恢复备份和安装本地模型会读取你通过系统文件选择器指定的文件。导出和备份会向你选择的位置写文件；若位置来自云盘提供方，后续同步由该提供方负责。")
        }
        Group("存储、密钥与备份") {
            Info("本机加密保存：API 配置（含接口地址、密钥和模型配置）、剪贴板历史与置顶内容、常用语、最近表情记录，以及 AI 会话列表中的首条消息、标题、会话标识和相关任务设置。这些数据使用 AES-GCM 加密，加密密钥由 Android Keystore 管理。")
            Info("仅在应用私有区域保存、未额外进行应用层加密：自定义中文和英文词库、自定义 AI 提示词及一般偏好设置。应用私有目录隔离不等于数据文件已加密。")
            Info("本地加密保护的是设备上保存的文件，功能使用时仍需读取数据；不能代替联网服务的数据保护。API 密钥会用于你配置的对应接口，请勿把密钥填入不信任的地址。")
            Info("导出的完整备份使用你设置的密码派生密钥，并以 AES-GCM 加密；恢复时需要该密码。备份包含 API 地址、密钥和模型配置、自定义词库、常用语、自定义 AI 及支持导出的设置。当前备份不包含剪贴板历史、最近表情记录、AI 会话历史或图片。词库单独导出是可读文本文件。请妥善保管备份、密码和导出文件。应用已禁用 Android 自动备份。")
        }
        Group("联网对象与外部链接") {
            Info("AI、翻译和语音请求直接连接所配置的 HTTPS 服务；获取模型列表和接口可用性检测也会联网，可能发送鉴权信息或检测用文本。模型下载连接模型托管及下载服务；打开项目、开发者主页或剪贴板链接会交给浏览器等外部应用。")
            Info("接收请求的服务及其网络基础设施可获知连接 IP、请求时间和必要的协议数据。服务端的日志、保存期限、训练用途、检索服务调用及数据所在地区取决于该服务方的政策和你的账户设置，S Key 无法代其保证，也无法通过清理本机数据删除其副本。当前应用代码未集成广告或用户行为统计上传 SDK。")
        }
        Group("隐私限制与管理") {
            Info("对于系统标记的密码等敏感输入框，以及应用要求不进行个性化学习的场景，S Key 会按对应限制停止或限制内容记录；密码输入框不支持 AI 和翻译。此判断依赖输入框向 Android 提供的标记，不能保证识别所有敏感内容。")
            Info("你可以关闭后台读取复制、选择不保存剪贴板历史、调整会话有效期、清除或逐条删除本地内容，并在 Android 设置中撤销麦克风或照片权限。截屏保护用于限制受保护页面的系统截图，不影响已经发送到第三方服务的数据。")
            Info("卸载应用会移除应用私有数据，但你自行导出的文件、外部应用接收的内容及第三方服务留存的数据不会随之删除。若不希望内容离开设备，请使用本地输入与离线识别，并避免启用会发送内容的 AI、翻译和 AI 语音功能。")
        }
    }

    @Composable private fun ApiSettings() {
        model.revision
        val draft = model.apiDraft
        var apiProvider by rememberSaveable { mutableStateOf("google") }
        @Composable fun field(label: String, value: String, secret: Boolean = false, change: (String) -> Unit) {
            TextField(value = value, onValueChange = change, label = label, enabled = model.apiReady,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = if (secret) androidx.compose.ui.text.input.KeyboardType.Password
                        else androidx.compose.ui.text.input.KeyboardType.Text,
                    autoCorrectEnabled = false,
                ),
                visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation()
                    else androidx.compose.ui.text.input.VisualTransformation.None,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
        }
        Group("使用设置") {
            Nav("自定义 AI", null, "ai") { openPage("custom_ai") }
            Toggle("AI 生成后自动上屏", model.settings.aiAutoApply, "结果生成完成后自动上屏", icon = "enter") { model.settings.aiAutoApply = it }
            Toggle("语音自动翻译", model.settings.voiceAutoTranslate, "在英文键盘时使用语音启用自动翻译", icon = "translate") { model.settings.voiceAutoTranslate = it }
            Nav("内置 AI 提示词", null, "ai") { openPage("builtin_ai_prompts") }
            Info("默认 AI 提供商")
            HorizontalChoices(AiProviders.assistantOptions, draft.aiProvider, enabled = model.apiReady, equalWidth = true) {
                model.updateApi(model.apiDraft.copy(aiProvider = it))
            }
            Range("会话有效期", model.aiConversationHours, 0, 720, "小时") { model.updateAiConversationHours(it) }
        }
        Column(Modifier.fillMaxWidth()) {
            Text("API KEY 设置", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp))
            HorizontalChoices(listOf("google" to "Google") + AiProviders.options.filter { it.first != "gemini" }, apiProvider,
                modifier = Modifier.padding(bottom = 16.dp), equalWidth = true) {
                apiProvider = it
            }
            Group(if (apiProvider == "google") "翻译接口" else "对话接口") {
                key(apiProvider) {
                    if (apiProvider == "google") {
                        field("翻译接口地址", draft.googleUrl) { model.updateApi(model.apiDraft.copy(googleUrl = it)) }
                        field("Google API Key", draft.googleKey, true) { model.updateApi(model.apiDraft.copy(googleKey = it)) }
                        Choice("默认 NMT 模型", draft.googleModel == "nmt", enabled = model.apiReady) { model.updateApi(model.apiDraft.copy(googleModel = "nmt")) }
                        field("模型 ID / 完整模型资源名", draft.googleModel) { model.updateApi(model.apiDraft.copy(googleModel = it)) }
                    } else if (apiProvider == "grok") {
                        field("接口地址（包含 /v1）", draft.grokUrl) { model.apiModels = emptyList(); model.updateApi(model.apiDraft.copy(grokUrl = it)) }
                        field("Grok API Key", draft.grokKey, true) { model.apiModels = emptyList(); model.updateApi(model.apiDraft.copy(grokKey = it)) }
                        field("模型 ID", draft.grokModel) { model.updateApi(model.apiDraft.copy(grokModel = it)) }
                        Action("获取可用模型", model.apiReady) { model.fetchApiModels("grok") }
                        model.apiModels.filter { model.apiModelsProvider == "grok" }.forEach { id -> Choice(id, draft.grokModel == id, enabled = model.apiReady) { model.updateApi(model.apiDraft.copy(grokModel = id)) } }
                        Info("可获取模型列表，也可填写模型 ID。接口需支持 Responses API。")
                    } else {
                        field("接口地址（包含 /v1）", draft.openaiUrl) { model.apiModels = emptyList(); model.updateApi(model.apiDraft.copy(openaiUrl = it)) }
                        field("OpenAI API Key", draft.openaiKey, true) { model.apiModels = emptyList(); model.updateApi(model.apiDraft.copy(openaiKey = it)) }
                        field("模型 ID", draft.openaiModel) { model.updateApi(model.apiDraft.copy(openaiModel = it)) }
                        Action("获取可用模型", model.apiReady) { model.fetchApiModels("openai") }
                        model.apiModels.filter { model.apiModelsProvider == "openai" }.forEach { id -> Choice(id, draft.openaiModel == id, enabled = model.apiReady) { model.updateApi(model.apiDraft.copy(openaiModel = id)) } }
                        Info("请选择支持 Responses API 的对话模型，也可手动填写模型 ID。")
                    }
                }
            }
        }
        if (apiProvider == "google") {
            Group("Gemini 对话接口") {
                field("接口地址（包含 /v1beta）", draft.geminiUrl) { model.apiModels = emptyList(); model.updateApi(model.apiDraft.copy(geminiUrl = it)) }
                field("Gemini API Key", draft.geminiKey, true) { model.apiModels = emptyList(); model.updateApi(model.apiDraft.copy(geminiKey = it)) }
                field("模型 ID", draft.geminiModel) { model.updateApi(model.apiDraft.copy(geminiModel = it)) }
                Action("获取可用模型", model.apiReady) { model.fetchApiModels("gemini") }
                model.apiModels.filter { model.apiModelsProvider == "gemini" }.forEach { id -> Choice(id, draft.geminiModel == id, enabled = model.apiReady) { model.updateApi(model.apiDraft.copy(geminiModel = id)) } }
            }
            Group("Gemini 语音接口") {
                field("语音识别端点（完整 HTTPS 地址）", draft.geminiVoiceUrl) { model.updateApi(model.apiDraft.copy(geminiVoiceUrl = it)) }
                field("Gemini 语音 API Key", draft.geminiVoiceKey, true) { model.updateApi(model.apiDraft.copy(geminiVoiceKey = it)) }
                field("语音模型 ID", draft.geminiVoiceModel) { model.updateApi(model.apiDraft.copy(geminiVoiceModel = it)) }
            }
        }
        if (apiProvider == "grok") {
            Group("语音接口") {
                field("语音识别端点（完整 HTTPS 地址）", draft.voiceUrl) { model.updateApi(model.apiDraft.copy(voiceUrl = it)) }
                field("语音 API Key", draft.voiceKey, true) { model.updateApi(model.apiDraft.copy(voiceKey = it)) }
                field("语音模型 ID", draft.voiceModel) { model.updateApi(model.apiDraft.copy(voiceModel = it)) }
            }
        }
        if (apiProvider == "openai") {
            Group("语音接口") {
                field("语音识别端点（完整 HTTPS 地址）", draft.openaiVoiceUrl) { model.updateApi(model.apiDraft.copy(openaiVoiceUrl = it)) }
                field("语音 API Key", draft.openaiVoiceKey, true) { model.updateApi(model.apiDraft.copy(openaiVoiceKey = it)) }
                field("语音模型 ID", draft.openaiVoiceModel) { model.updateApi(model.apiDraft.copy(openaiVoiceModel = it)) }
            }
        }
        Group("其它设置") {
            Action("清除 API 配置", model.apiCanClear) { model.clearApi() }
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
            Toggle("自动联想", s.chineseAssociation) { s.chineseAssociation = it }
            Toggle("输入框预览", s.editorPreview) { s.editorPreview = it }
            Toggle("自动插入空格", s.autoChineseSpacing) { s.autoChineseSpacing = it }
            Toggle("空格显示英语翻译", s.spaceEnglishGloss) { s.spaceEnglishGloss = it }
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
            Toggle("模糊拼音", s.fuzzyPinyin, null) { s.fuzzyPinyin = it }
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
            Toggle("自动联想", model.settings.englishAssociation) { model.settings.englishAssociation = it }
        }
    }
    @Composable private fun KeyboardSettings() {
        model.revision
        val s = model.settings
        Group("按键反馈") {
            Nav("按键声音", KeySound.label(s.keySound)) { openPage("key_sound") }
            Toggle("按键振动", s.vibration) { s.vibration = it }
            Range("振动强度", s.vibrationStrength, 1, 100, "%") { s.vibrationStrength = it }
            Info("将震动强度设为 10% 将尝试使用系统震动")
        }
        Group("手势与长按") {
            Toggle("上滑输入符号", model.settings.swipeUpSymbols, "多个符号时只输入第一个") { model.settings.swipeUpSymbols = it }
            Toggle("长按输入符号", s.longPressSymbols) { s.longPressSymbols = it }
            Range("长按延迟", s.longPressDelay, 150, 1500, "ms", 10) { s.longPressDelay = it }
            Toggle("滑动退格删除", s.swipeDelete) { s.swipeDelete = it }
            Nav("定制工具栏", null, "more") { openPage("toolbar") }
        }
        Group("剪贴板设置") {
            Toggle("快捷粘贴", s.quickPaste) { s.quickPaste = it }
        }
    }
    @Composable private fun KeySoundSettings() {
        model.revision
        val view = androidx.compose.ui.platform.LocalView.current
        val player = remember { io.secure.keyboard.ui.KeySoundPlayer() }
        DisposableEffect(player) {
            player.prepare(view.context, "bong_001")
            onDispose { player.release() }
        }
        Group("按键声音") {
            KeySound.options.forEach { (id, label) ->
                BasicComponent(title = label, onClick = { model.settings.keySound = id }, rightActions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (model.settings.keySound == id) UiIcon("check", "已选择")
                        if (id != "none") IconButton(onClick = { player.perform(view, id, model.settings.keySoundVolume) }) {
                            UiIcon("play", "试听$label")
                        }
                    }
                })
            }
        }
        Group("音效音量") {
            Range("音量", model.settings.keySoundVolume, 0, 100, "%") { model.settings.keySoundVolume = it }
        }
    }
    @Composable private fun Appearance() {
        model.revision
        val s = model.settings
        Group("默认主题与切换") {
            HorizontalChoices(listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色"), s.theme) {
                if (it != "system") s.manualDark = it == "dark"
                s.theme = it
            }
            Nav("浅色配色", null, "theme") { openPage("theme_light") }
            Nav("深色配色", null, "theme") { openPage("theme_dark") }
        }
        Group("按键样式") {
            Nav("按键样式", null, "theme") { openPage("theme_style") }
        }
        Group("文字与提示") {
            Range("候选栏字号", s.candidateTextSize, 12, 32, "sp") { s.candidateTextSize = it }
            Range("展开候选字号", s.expandedCandidateTextSize, 12, 32, "sp") { s.expandedCandidateTextSize = it }
            HorizontalChoices(listOf("all" to "全部按键角标", "digits" to "仅数字角标", "hidden" to "隐藏角标"), s.symbolHints, equalWidth = true) {
                s.symbolHints = it
            }
        }
    }
    @Composable private fun Layouts() {
        val revision = model.revision
        val includeNavigationBar = remember(revision) { model.settings.includeNavigationBar }
        Group("键盘布局") {
            Toggle("计算底部导航栏", includeNavigationBar) {
                model.settings.includeNavigationBar = it
            }
        }
        Group("独立布局") {
            for (outer in listOf(true, false)) for (landscape in listOf(false, true)) {
                val id = "profile_${if (outer) "outer" else "inner"}_${if (landscape) "landscape" else "portrait"}"
                Nav("${if (outer) "外屏" else "内屏"} · ${if (landscape) "横屏" else "竖屏"}", icon = "layout") { openPage(id) }
            }
        }
        Group("符号设置") {
            Nav("键盘符号显示", null, "keyboard") { openPage("symbol_keyboard") }
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
            Range("键盘高度", if (p.floating) p.floatingHeightDp else p.heightDp, LayoutLimits.HEIGHT.first, LayoutLimits.HEIGHT.last, "dp") { save(if (p.floating) p.copy(floatingHeightDp = it) else p.copy(heightDp = it)) }
            Range("两侧留白", p.sideGapDp, 0, 300, "dp") { save(p.copy(sideGapDp = it)) }
            Range("底部留白", p.bottomGapDp, LayoutLimits.BOTTOM_GAP.first, LayoutLimits.BOTTOM_GAP.last, "dp") { save(p.copy(bottomGapDp = it)) }
            Range("次面板高度", p.popupHeightDp, 110, 450, "dp") { save(p.copy(popupHeightDp = it)) }
        }
        Group("布局形态") {
            Toggle("悬浮键盘", p.floating) { save(p.copy(floating = it)) }
            if (!outer) {
                Toggle("分离键盘", p.split) { save(p.copy(split = it)) }
                Range("分离间距", p.splitGapDp, 0, 180, "dp") { save(p.copy(splitGapDp = it)) }
            }
        }
    }
    @Composable private fun ThemeSettings(route: String, modifier: Modifier, close: () -> Unit,
        registerBack: ((() -> Unit)?) -> Unit) {
        val s = model.settings
        val style = route == "theme_style"
        var previewDark by rememberSaveable { mutableStateOf(route == "theme_dark") }
        val ids = themeLabels.keys.toList()
        val initialTokens = remember { s.themeTokens(previewDark) }
        val initialGeometry = remember { listOf(s.keyBorderWidth, s.keyHorizontalGap, s.keyVerticalGap, s.keyCornerRadius, s.enterCornerRadius) }
        val initial = remember { if (style) initialGeometry.joinToString("|") else ids.joinToString("|") { initialTokens.asMap().getValue(it) } }
        var baseline by rememberSaveable { mutableStateOf(initial) }
        var draft by rememberSaveable { mutableStateOf(initial) }
        var history by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
        var selected by rememberSaveable { mutableStateOf("普通按键") }
        var editing by rememberSaveable { mutableStateOf<String?>(null) }
        var more by remember { mutableStateOf(false) }
        var discard by remember { mutableStateOf(false) }
        fun change(next: String) {
            if (next != draft) { history = ArrayList((history + draft).takeLast(50)); draft = next }
        }
        fun setColor(id: String, color: String) {
            change(draft.split("|").toMutableList().apply { this[ids.indexOf(id)] = color }.joinToString("|"))
        }
        val colors = if (style) s.themeTokens(previewDark) else ids.zip(draft.split("|")).fold(initialTokens) { tokens, (id, value) -> tokens.previewColor(id, value) }
        val geometry = if (style) draft.split("|").map { it.toInt() } else initialGeometry
        val defaults = SettingsStore.defaultThemeTokens(previewDark).asMap()
        val groups = linkedMapOf(
            "键盘背景" to listOf("keyboardBackground", "divider"),
            "普通按键" to listOf("normalKeyBackground", "text", "hint"),
            "功能按键" to listOf("specialKeyBackground", "specialKeyText", "selectedKeyBackground", "selectedKeyText"),
            "回车键" to listOf("accent", "enterText"),
            "候选栏" to listOf("candidate", "candidateSelected", "preedit"),
            "工具栏" to listOf("toolbarIcon"))
        val leave = { if (draft != baseline) discard = true else close() }
        val currentLeave by rememberUpdatedState(leave)
        DisposableEffect(Unit) {
            registerBack { currentLeave() }
            onDispose { registerBack(null) }
        }
        BackHandler { if (editing != null) editing = null else leave() }
        Column(modifier.fillMaxSize().imePadding()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                item {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { leave() }) { UiIcon("previous", "返回") }
                        Text(pageTitle(route), modifier = Modifier.weight(1f).padding(start = 4.dp, end = 16.dp),
                            fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(16.dp))
                }
                item {
            Column(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.surface).padding(vertical = 8.dp)) {
                if (style) HorizontalChoices(listOf("light" to "浅色预览", "dark" to "深色预览"),
                    if (previewDark) "dark" else "light") { previewDark = it == "dark" }
                SKeyTheme(colors, previewDark) {
                    KeyboardThemePreview(colors, s.toolbarPins, s.symbolHints,
                        borderWidth = geometry[0], horizontalGap = geometry[1], verticalGap = geometry[2],
                        cornerRadius = geometry[3], enterCornerRadius = geometry[4],
                        selectedRegion = if (style) null else selected,
                        onRegionClick = if (style) null else { region -> selected = region })
                }
                Text(if (style) "按键样式由深浅主题共用" else "点击预览区域选择配色 · 部分颜色也用于设置页",
                    fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
                }
                item {
                    Column(Modifier.fillMaxWidth().padding(SettingsPagePadding)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(text = "撤销上一步", enabled = history.isNotEmpty(), onClick = {
                            draft = history.last(); history = ArrayList(history.dropLast(1))
                        })
                        TextButton(text = "更多", onClick = { more = true })
                    }
                    if (style) {
                        Group("按键样式") {
                            val names = listOf("边框宽度", "横向间距", "纵向间距", "按键圆角", "回车键圆角")
                            val limits = listOf(4, 16, 16, 30, 30)
                            val reset = listOf(0, 4, 5, 7, 30)
                            names.forEachIndexed { index, name ->
                                DesignRange(name, geometry[index], limits[index], reset = {
                                    change(geometry.toMutableList().apply { this[index] = reset[index] }.joinToString("|"))
                                }) { value ->
                                    change(geometry.toMutableList().apply { this[index] = value }.joinToString("|"))
                                }
                            }
                            Action("恢复本组默认样式") { change(reset.joinToString("|")) }
                        }
                    } else {
                        groups.keys.toList().chunked(3).forEach { row ->
                            HorizontalChoices(row.map { it to it }, selected) { selected = it }
                        }
                        Group(selected) {
                            groups.getValue(selected).forEach { id ->
                                BasicComponent(title = themeLabels.getValue(id), onClick = { editing = id },
                                    rightActions = {
                                        Box(Modifier.size(28.dp).background(hexColor(colors.asMap().getValue(id)), RoundedCornerShape(8.dp))
                                            .border(1.dp, MiuixTheme.colorScheme.outline, RoundedCornerShape(8.dp)))
                                        IconButton(onClick = { setColor(id, defaults.getValue(id)) }) { UiIcon("undo", "恢复默认：${themeLabels.getValue(id)}") }
                                    })
                            }
                            Action("恢复本组默认颜色") {
                                val values = draft.split("|").toMutableList()
                                groups.getValue(selected).forEach { values[ids.indexOf(it)] = defaults.getValue(it) }
                                change(values.joinToString("|"))
                            }
                        }
                    }
                }
            }
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(text = "取消", modifier = Modifier.weight(1f), onClick = { leave() })
                Button(modifier = Modifier.weight(1f), enabled = draft != baseline, colors = ButtonDefaults.buttonColorsPrimary(), onClick = {
                    if (style) {
                        s.keyBorderWidth = geometry[0]; s.keyHorizontalGap = geometry[1]; s.keyVerticalGap = geometry[2]
                        s.keyCornerRadius = geometry[3]; s.enterCornerRadius = geometry[4]
                    } else s.saveThemeTokens(previewDark, colors)
                    baseline = draft; history = arrayListOf(); model.message = "已应用"; close()
                }) { Text("保存", color = MiuixTheme.colorScheme.onPrimary) }
            }
        }
        if (discard) Modal("放弃未应用的修改？", { discard = false }) {
            Action("继续编辑") { discard = false }
            Action("放弃修改") { discard = false; close() }
        }
        if (more) Modal("更多", { more = false }) {
            Info("恢复默认只修改预览，点击保存后才会生效，也可以撤销。")
            Action(if (style) "恢复全部按键样式" else "恢复整套${if (previewDark) "深色" else "浅色"}配色") {
                change(if (style) "0|4|5|7|30" else ids.joinToString("|") { defaults.getValue(it) }); more = false
            }
        }
        editing?.let { id ->
            key(id) {
                ColorEditor(themeLabels.getValue(id), colors.asMap().getValue(id), { editing = null }) { color ->
                    setColor(id, color); editing = null
                }
            }
        }
    }
    @Composable private fun ColorEditor(label: String, initial: String, close: () -> Unit, save: (String) -> Unit) {
        val picker = remember(initial) { ColorPickerState(initial) }
        var pickerRevision by remember { mutableIntStateOf(0) }
        pickerRevision
        val hex = picker.rawHex
        val hue = picker.hue
        val saturation = picker.saturation
        val brightness = picker.value
        val valid = picker.isValid
        Modal(label, close) {
            AndroidView(factory = { context -> ColorPickerView(context, ColorPickerView.Component.SATURATION_VALUE) },
                update = { view ->
                    view.setSelection(hue, saturation, brightness)
                    view.onChange = { h, s, v, _ -> picker.chooseHsv(h, s, v); pickerRevision++ }
                }, modifier = Modifier.fillMaxWidth().height(160.dp))
            Text("色相", fontSize = 14.sp)
            AndroidView(factory = { context -> ColorPickerView(context, ColorPickerView.Component.HUE) },
                update = { view ->
                    view.setSelection(hue, saturation, brightness)
                    view.onChange = { h, s, v, _ -> picker.chooseHsv(h, s, v); pickerRevision++ }
                }, modifier = Modifier.fillMaxWidth().height(40.dp))
            TextField(value = hex, onValueChange = { value ->
                picker.typeHex(value); pickerRevision++
            }, label = "颜色 HEX，例如 #2B2B2B", singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(text = "取消", modifier = Modifier.weight(1f), onClick = close)
                Button(modifier = Modifier.weight(1f), enabled = valid, colors = ButtonDefaults.buttonColorsPrimary(),
                    onClick = { picker.confirmedHex()?.let(save) }) { Text("保存", color = MiuixTheme.colorScheme.onPrimary) }
            }
        }
    }
    @Composable private fun ContentHeader(route: String, ask: (String, () -> Unit) -> Unit) {
        model.revision
        Group(pageTitle(route)) {
            if (route == "clipboard") {
                HorizontalChoices(listOf("none" to "不保存历史", "timed" to "定时清理", "forever" to "长期保留"), model.settings.clipboardRetention, enabled = !model.busy, equalWidth = true) { id ->
                    model.setClipboardRetention(id)
                }
                if (model.settings.clipboardRetention == "timed") {
                    Range("保留时间", model.settings.clipboardRetentionHours, 1, 168, "小时") { value ->
                        model.settings.clipboardRetentionHours = value
                        model.task { model.content.trimClipboard() }
                    }
                }
                Range("未置顶历史上限", model.settings.clipboardLimit, 1, 300, "条") { value ->
                    model.settings.clipboardLimit = value
                    model.task { model.content.trimClipboard() }
                }
                Info("置顶内容单独保留，不参与自动清理。不保存历史时，已有未置顶记录会删除；快捷粘贴仍可使用。")
                if (model.settings.clipboardRetention == "timed") Info("按复制时间清理。系统休眠可能延后执行，打开历史时会再次清理。")
                Action("清空剪贴板历史") { ask("清空剪贴板历史？如果系统剪贴板内容也在历史中，将一并清空。") { model.task { model.content.clearClipboard() } } }
                if (model.clipboard.isEmpty()) Info("还没有剪贴板记录")
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(
                        text = if (route == "phrases") "添加常用语" else "添加词条",
                        enabled = !model.busy, modifier = Modifier.weight(1f),
                        onClick = { model.edit = ContentEdit(route) }
                    )
                    TextButton(
                        text = if (route == "phrases") "清空常用语" else "清空本词库",
                        enabled = !model.busy, modifier = Modifier.weight(1f),
                        onClick = {
                            if (route == "phrases") {
                                ask("清空全部常用语？") { model.task { model.content.clearPhrases() } }
                            } else {
                                val kind = if (route == "dictionary") DictionaryKind.CHINESE else DictionaryKind.ENGLISH
                                ask("清空${pageTitle(route)}？") { model.task { model.content.clearDictionary(kind) } }
                            }
                        }
                    )
                }
            }
        }
    }
    @Composable private fun EditDialog(edit: ContentEdit) {
        val customAi = edit.category == "custom_ai"
        val title = if (customAi) { if (edit.id == null) "添加自定义 AI" else "编辑自定义 AI" }
            else if (edit.id == null) "添加内容" else "编辑内容"
        if (customAi) {
            val canSave = !model.busy && edit.first.text.isNotBlank() && edit.second.text.isNotBlank()
            Dialog(onDismissRequest = { if (!model.busy) model.edit = null },
                properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
                Box(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center) {
                    Card(Modifier.widthIn(max = 600.dp).fillMaxWidth()
                        .heightIn(max = (LocalConfiguration.current.screenHeightDp * .85f).dp)) {
                        Column {
                            Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp))
                            Box(Modifier.fillMaxWidth().height(1.dp).background(MiuixTheme.colorScheme.onSurface.copy(alpha = .06f)))
                            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                AiEditorField("名称", "例如：邮件润色", edit.first, 40, false) {
                                    model.edit = edit.copy(first = it)
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("AI 提供商", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    HorizontalChoices(AiProviders.customOptions, edit.provider, modifier = Modifier,
                                        enabled = !model.busy, equalWidth = true) { model.edit = edit.copy(provider = it) }
                                    Text("模型 ID", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    TextField(value = edit.modelId,
                                        onValueChange = { if (it.length <= 256) model.edit = edit.copy(modelId = it) },
                                        enabled = !model.busy, label = "留空使用 AI 设置中的模型", singleLine = true,
                                        modifier = Modifier.fillMaxWidth())
                                }
                                AiEditorField("提示词", "描述你希望 AI 如何处理原文…", edit.second,
                                    ContentStore.MAX_AI_PROMPT_CHARACTERS, true) { model.edit = edit.copy(second = it) }
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("推理强度", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        io.secure.keyboard.data.AiReasoning.options.forEach { (value, label) ->
                                            val selected = edit.reasoningEffort == value
                                            Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(10.dp))
                                                .background(if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface.copy(alpha = .06f))
                                                .selectable(selected = selected, enabled = !model.busy,
                                                    role = androidx.compose.ui.semantics.Role.RadioButton) {
                                                    model.edit = edit.copy(reasoningEffort = value)
                                                }, contentAlignment = Alignment.Center) {
                                                Text(label, color = if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface)
                                            }
                                        }
                                    }
                                    Text("较高强度可能需要更长时间，部分模型不支持。", fontSize = 12.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceSecondary)
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    AiEditorToggle("联网搜索", "允许 AI 按需搜索网页", edit.webSearch) { model.edit = edit.copy(webSearch = it) }
                                    AiEditorToggle("X 搜索", "允许 AI 按需搜索 X 帖子；此选项仅 Grok 使用。", edit.xSearch) { model.edit = edit.copy(xSearch = it) }
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    SettingRange("会话有效期", edit.retentionHours, 0, 720, "小时",
                                        contentPadding = PaddingValues(0.dp)) {
                                        if (!model.busy) model.edit = edit.copy(retentionHours = it)
                                    }
                                    Text("0 小时不保留会话；保存后也应用于已有会话。", fontSize = 12.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceSecondary)
                                }
                            }
                            Box(Modifier.fillMaxWidth().height(1.dp).background(MiuixTheme.colorScheme.onSurface.copy(alpha = .06f)))
                            Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
                                SettingsDialogActions(cancel = { model.edit = null }, confirm = { model.saveEdit() },
                                    enabled = canSave, cancelEnabled = !model.busy)
                            }
                        }
                    }
                }
            }
            return
        }
        Modal(title, { if (!model.busy) model.edit = null }) {
            TextField(value = edit.first, onValueChange = { model.edit = edit.copy(first = it) },
                label = if (edit.category == "phrases") "常用语" else "词语",
                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp))
            if (edit.category != "phrases") TextField(value = edit.second,
                onValueChange = { model.edit = edit.copy(second = it) }, label = "拼音 / 编码",
                modifier = Modifier.fillMaxWidth(), singleLine = true)
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
            if (multiline) {
                androidx.compose.foundation.text.BasicTextField(value = value,
                    onValueChange = { if (it.text.length <= limit) change(it) },
                    enabled = !model.busy, minLines = 5, maxLines = 8,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 24.sp,
                        color = MiuixTheme.colorScheme.onSurface),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MiuixTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = title }
                        .clip(RoundedCornerShape(12.dp))
                        .background(MiuixTheme.colorScheme.onSurface.copy(alpha = .06f)).padding(16.dp),
                    decorationBox = { inner ->
                        Box {
                            if (value.text.isEmpty()) Text(placeholder, fontSize = 16.sp,
                                color = MiuixTheme.colorScheme.onSurfaceSecondary)
                            inner()
                        }
                    })
            } else {
                TextField(value = value, onValueChange = { if (it.text.length <= limit) change(it) },
                    enabled = !model.busy, singleLine = true, label = placeholder,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = title })
            }
        }
    }
    @Composable private fun AiEditorToggle(title: String, summary: String, checked: Boolean, change: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .clickable(enabled = !model.busy) { change(!checked) }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontSize = 16.sp)
                Text(summary, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
            }
            Spacer(Modifier.width(16.dp))
            Switch(checked = checked, onCheckedChange = { if (!model.busy) change(it) })
        }
    }
    @Composable private fun VoiceSettings() {
        model.revision
        val ai = model.settings.voiceMode == "ai"
        Group("识别方式") {
            HorizontalChoices(listOf("offline" to "离线语音", "ai" to "AI 语音"), model.settings.voiceMode) {
                model.settings.voiceMode = it
            }
        }
        if(ai) {
            Group("AI 语音") {
                Info("AI 语音提供商")
                HorizontalChoices(AiProviders.assistantOptions, model.apiDraft.voiceProvider, enabled = model.apiReady, equalWidth = true) {
                    model.updateApi(model.apiDraft.copy(voiceProvider = it))
                }
                Nav("AI 设置", "配置语音端点、密钥与模型", "settings") { openPage("api") }
                Action("允许麦克风权限", !microphone) { micPicker.launch(Manifest.permission.RECORD_AUDIO) }
            }
            return
        }
        val installed = remember(voiceState) { ModelRepository(applicationContext).isInstalled() }
        Group("离线普通话") {
            Info("模型：${if (installed) "已导入" else "未导入"} · 麦克风：${if (microphone) "已允许" else "未允许"}", true)
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
    @Composable private fun Licenses(modifier: Modifier = Modifier) {
        var paths by remember { mutableStateOf<List<String>>(emptyList()) }
        var selected by rememberSaveable { mutableStateOf<String?>(null) }
        var chunks by remember { mutableStateOf<List<String>>(emptyList()) }
        var error by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            try {
                paths = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    fun collect(path: String): List<String> = assets.list(path).orEmpty().flatMap { name ->
                        val child = if (path.isEmpty()) name else "$path/$name"
                        if (assets.list(child).orEmpty().isNotEmpty()) collect(child)
                        else if (name.contains("LICENSE", true) || name.contains("NOTICE", true) ||
                            name.contains("COPYRIGHT", true) || child == "runtime-licenses/DEPENDENCIES.txt") listOf(child)
                        else emptyList()
                    }
                    collect("").sorted()
                }
            } catch (_: java.io.IOException) { error = "无法读取声明，请重新打开此页" }
        }
        LaunchedEffect(selected) {
            chunks = emptyList()
            val path = selected ?: return@LaunchedEffect
            try {
                chunks = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    assets.open(path).bufferedReader().use { it.readText() }.chunked(2048)
                }
            } catch (_: java.io.IOException) { error = "无法读取此声明，请重试" }
        }
        LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = SettingsPagePadding,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Group("开源组件") {
                    Info("Miuix、图标、词库、音效与语音组件。选择文件查看完整许可声明。")
                    error?.let { Info(it) }
                    if (paths.isEmpty() && error == null) Info("正在读取声明目录…")
                    if (selected != null) Action("返回声明列表") { selected = null; chunks = emptyList(); error = null }
                }
            }
            if (selected == null) {
                items(paths, key = { it }) { path ->
                    Action(path) { chunks = emptyList(); error = null; selected = path }
                }
            } else {
                item { Info(selected.orEmpty(), strong = true) }
                if (chunks.isEmpty() && error == null) item { Info("正在读取声明…") }
                items(chunks.size) { index -> Info(chunks[index]) }
            }
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
    @Composable private fun Choice(title: String, checked: Boolean, enabled: Boolean = true, action: () -> Unit) {
        BasicComponent(title = title, enabled = enabled, onClick = { if (enabled) action() }, rightActions = { if (checked) UiIcon("check", "已选择") })
    }
    @Composable private fun DesignRange(title: String, value: Int, maximum: Int, reset: (() -> Unit)? = null, save: (Int) -> Unit) {
        SettingRange(title, value, 0, maximum, "dp", reset = reset, save = save)
    }
    @Composable private fun Range(title: String, value: Int, minimum: Int, maximum: Int, unit: String, step: Int = 1, save: (Int) -> Unit) {
        SettingRange(title, value, minimum, maximum, unit, step, save = save)
    }
    @Composable private fun Action(text: String, enabled: Boolean = true, action: () -> Unit) {
        TextButton(text = text, onClick = action, enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
    }
    @Composable private fun ContentCard(text: String, summary: String, actions: List<Pair<String, () -> Unit>>, compact: Boolean = false, first: Boolean = false, last: Boolean = false) {
        var preview by remember(text) { mutableStateOf(false) }
        val shape = RoundedCornerShape(
            topStart = if (!compact || first) 16.dp else 0.dp,
            topEnd = if (!compact || first) 16.dp else 0.dp,
            bottomStart = if (!compact || last) 16.dp else 0.dp,
            bottomEnd = if (!compact || last) 16.dp else 0.dp
        )
        Box(Modifier.fillMaxWidth().padding(top = if (compact && first) 16.dp else 0.dp)
            .background(MiuixTheme.colorScheme.surface, shape)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = if (compact) 2.dp else 12.dp, bottom = if (compact) 2.dp else 12.dp),
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
                    IconButton(modifier = Modifier.size(48.dp), enabled = !model.busy, onClick = { if (label == "复制") preview = true else action() }) {
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
        "symbol_keyboard" -> "键盘符号显示"
        "symbol_closure" -> "符号自动闭合"
        "builtin_ai_prompts" -> "内置 AI 提示词"
        "builtin_dictionaries" -> "内置词库"
        "dashboard" -> "S Key"
        "input" -> "输入方式"
        "input_english" -> "英文设置"
        "input_fuzzy" -> "模糊拼音"
        "keyboard", "feedback" -> "键盘操作"
        "key_sound" -> "按键声音"
        "behavior" -> "主题与外观"
        "theme_light" -> "浅色配色"
        "theme_dark" -> "深色配色"
        "theme_style" -> "按键样式"
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
        "cleanup", "recovery" -> "备份与清理"
        "licenses" -> "开源声明"
        "about" -> "关于应用"
        "privacy_data" -> "隐私数据声明"
        else -> if (route.startsWith("profile_")) "${if (route.contains("outer")) "外屏" else "内屏"} · ${if (route.endsWith("landscape")) "横屏" else "竖屏"}" else "偏好设置"
    }
    private fun toolName(id: String) = when(id) {
        "association" -> "自动联想"
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
