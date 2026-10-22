package io.secure.keyboard.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    var message by rememberSaveable { mutableStateOf("修改后下次生成生效，并重新开始会话。") }
    val colors = MiuixTheme.colorScheme
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(message, color = colors.onSurfaceSecondary, fontSize = 14.sp) }
        for ((title, definitions) in listOf(
            "自定义 AI" to AiPromptStore.prompts.filter { it.id == "custom_common" },
            "内置助手" to AiPromptStore.prompts.filter { it.id != "custom_common" }
        )) {
            item {
                SmallTitle(title)
                Card {
                    definitions.forEach { prompt ->
                        val value = remember(revision, prompt.id) { store.read(prompt.id) }
                        BasicComponent(title = prompt.title, summary = when {
                            prompt.id == "custom_common" && value.isBlank() -> "留空 · 仅使用自定义指令"
                            value == prompt.default -> "默认"
                            value.isBlank() -> "已设为空"
                            else -> "已修改"
                        }, onClick = { draft = store.read(prompt.id); selected = prompt.id })
                    }
                }
            }
        }
    }
    selected?.let { id ->
        val definition = AiPromptStore.definition(id)
        Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
            Card(settingsDialogModifier()) {
                Column(Modifier.padding(20.dp).imePadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(definition.title, fontSize = 20.sp)
                    Text(when (id) {
                        "custom_common" -> "留空时只使用自定义指令。填写的内容会加在指令前，不使用普通 AI 通用要求。"
                        "common" -> "用于润色、扩写、语气和翻译，不用于自定义 AI。"
                        "translate" -> "{target} 会替换为当前选择的目标语言。"
                        else -> "与普通 AI 通用提示词一起使用。"
                    }, fontSize = 13.sp, color = colors.onSurfaceSecondary)
                    TextField(value = draft, onValueChange = { if (it.length <= 12000) draft = it },
                        label = if (id == "custom_common") "统一提示词（可留空）" else "提示词", modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 300.dp))
                    Text("${draft.length} / 12000", fontSize = 12.sp, color = colors.onSurfaceSecondary)
                    TextButton(text = "恢复默认", onClick = { draft = definition.default }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(text = "取消", onClick = { selected = null }, modifier = Modifier.weight(1f))
                        Button(onClick = { store.save(id, draft); revision++; selected = null; message = "已保存，下次生成生效" },
                            colors = ButtonDefaults.buttonColorsPrimary(), modifier = Modifier.weight(1f)) { Text("保存", color = colors.onPrimary) }
                    }
                }
            }
        }
    }
}
