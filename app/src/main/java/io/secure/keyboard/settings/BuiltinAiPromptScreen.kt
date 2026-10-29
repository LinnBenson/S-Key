package io.secure.keyboard.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.secure.keyboard.ai.AiPromptStore
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable internal fun BuiltinAiPromptScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember(context) { AiPromptStore(context) }
    var revision by remember { mutableIntStateOf(0) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    var modelDraft by rememberSaveable { mutableStateOf("") }
    var geminiModelDraft by rememberSaveable { mutableStateOf("") }
    var openaiModelDraft by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }
    val colors = MiuixTheme.colorScheme
    LazyColumn(modifier.fillMaxSize(), contentPadding = SettingsPagePadding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (message.isNotEmpty()) item { Text(message, color = colors.onSurfaceSecondary, fontSize = 14.sp) }
        for ((title, definitions) in listOf(
            "自定义 AI" to AiPromptStore.prompts.filter { it.id == "custom_common" },
            "内置助手" to AiPromptStore.prompts.filter { it.id != "custom_common" }
        )) {
            item {
                SettingsSection(title, spacedAfter = false) {
                    definitions.forEach { prompt ->
                        val value = remember(revision, prompt.id) { store.read(prompt.id) }
                        BasicComponent(title = prompt.title, leftAction = { Box(Modifier.padding(end = 12.dp)) { EditorIcon("ai") } },
                            rightActions = { EditorIcon("forward") }, summary = when {
                            prompt.id == "custom_common" && value.isBlank() -> null
                            value == prompt.default -> "默认"
                            value.isBlank() -> "已设为空"
                            else -> "自定义 · " + value.replace('\n', ' ').take(36)
                        }, onClick = { draft = store.read(prompt.id); modelDraft = store.model(prompt.id); openaiModelDraft = store.model(prompt.id, "openai"); geminiModelDraft = store.model(prompt.id, "gemini"); selected = prompt.id })
                    }
                }
            }
        }
    }
    selected?.let { id ->
        val definition = AiPromptStore.definition(id)
        Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
            Box(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center) {
                Card(Modifier.widthIn(max = 600.dp).fillMaxWidth()
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp * .85f).dp)) {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(definition.title, modifier = Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                            IconButton(onClick = { draft = definition.default }) { EditorIcon("undo", "恢复默认") }
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.onSurface.copy(alpha = .06f)))
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            if (id != "common" && id != "custom_common") {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Grok 模型 ID", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    TextField(value = modelDraft,
                                        onValueChange = { if (it.length <= 256 && it.none { c -> c.isWhitespace() || c.isISOControl() }) modelDraft = it },
                                        label = "留空使用 Grok 对话接口中的模型", singleLine = true, modifier = Modifier.fillMaxWidth())
                                    Text("OpenAI 模型 ID", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    TextField(value = openaiModelDraft,
                                        onValueChange = { if (it.length <= 256 && it.none { c -> c.isWhitespace() || c.isISOControl() }) openaiModelDraft = it },
                                        label = "留空使用 OpenAI 对话接口中的模型", singleLine = true, modifier = Modifier.fillMaxWidth())
                                    Text("Gemini 模型 ID", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    TextField(value = geminiModelDraft,
                                        onValueChange = { if (it.length <= 256 && it.none { c -> c.isWhitespace() || c.isISOControl() }) geminiModelDraft = it },
                                        label = "留空使用 Gemini 对话接口中的模型", singleLine = true, modifier = Modifier.fillMaxWidth())
                                }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                val label = if (id == "custom_common") "统一提示词（可留空）" else "提示词"
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text("${draft.length}/12000", fontSize = 12.sp, color = colors.onSurfaceSecondary)
                                }
                                BasicTextField(value = draft, onValueChange = { if (it.length <= 12000) draft = it },
                                    minLines = 5, maxLines = 8,
                                    textStyle = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, color = colors.onSurface),
                                    cursorBrush = SolidColor(colors.primary),
                                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = label }
                                        .clip(RoundedCornerShape(12.dp)).background(colors.onSurface.copy(alpha = .06f)).padding(16.dp),
                                    decorationBox = { inner -> Box {
                                        if (draft.isEmpty()) Text(label, fontSize = 16.sp, color = colors.onSurfaceSecondary)
                                        inner()
                                    } })
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.onSurface.copy(alpha = .06f)))
                        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
                            SettingsDialogActions(cancel = { selected = null }, confirm = {
                                store.save(id, draft, modelDraft.takeIf { id != "common" && id != "custom_common" },
                                    openaiModelDraft.takeIf { id != "common" && id != "custom_common" },
                                    geminiModelDraft.takeIf { id != "common" && id != "custom_common" })
                                revision++; selected = null; message = "已保存，下次生成生效"
                            })
                        }
                    }
                }
            }
        }
    }
}
