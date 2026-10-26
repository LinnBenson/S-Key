package io.secure.keyboard.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.secure.keyboard.data.EmojiShortcutStore
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable internal fun EmojiShortcutScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember(context) { EmojiShortcutStore(context) }
    val initial = remember(store) { runCatching { store.entries() } }
    var loadFailed by remember { mutableStateOf(initial.isFailure) }
    var confirmReset by remember { mutableStateOf(false) }
    var rules by remember { mutableStateOf(initial.getOrDefault(emptyList())) }
    var search by remember { mutableStateOf("") }
    val filteredRules = remember(rules, search) {
        val query = search.trim()
        if (query.isEmpty()) rules else rules.filter { rule ->
            rule.emoji.contains(query) || rule.keywords.any { it.contains(query, ignoreCase = true) }
        }
    }
    var editing by rememberSaveable { mutableStateOf(false) }
    var original by rememberSaveable { mutableStateOf<String?>(null) }
    var opening by rememberSaveable { mutableStateOf("") }
    var closing by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }
    val colors = MiuixTheme.colorScheme
    Column(modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f),contentPadding=SettingsPagePadding,verticalArrangement=Arrangement.spacedBy(10.dp)) {

            item {
                TextField(value=search, onValueChange={search=it}, label="搜索 emoji 或关键词",
                    modifier=Modifier.fillMaxWidth(), singleLine=true)
            }
            item { Text("${filteredRules.size} 个快捷表情", modifier = Modifier.padding(horizontal = 16.dp), fontSize = 14.sp, color = colors.onSurfaceSecondary) }
            if (loadFailed) item {
                Column {
                    Text("快捷表情配置无法读取，正常输入不受影响。可以还原备份，或恢复默认快捷表情。", color=colors.onSurfaceSecondary)
                    TextButton(text="恢复默认快捷表情", onClick={confirmReset=true})
                }
            }
            else if (rules.isEmpty()) item { Text("暂无快捷表情") }
            else if (filteredRules.isEmpty()) item { Text("没有匹配的表情", color=colors.onSurfaceSecondary) }
            items(filteredRules, key={it.emoji}) { rule ->
                Card {
                    BasicComponent(title = rule.keywords.joinToString(" · "), summary = "点击编辑关键词",
                        leftAction = { Box(Modifier.padding(end = 16.dp)) { EditorBadge(rule.emoji) } },
                        rightActions = { EditorIcon("forward") }, onClick = {
                            original=rule.emoji;opening=rule.emoji;closing=rule.keywords.joinToString(",");error="";editing=true
                        })
                }

            }
        }
        Button(enabled=!loadFailed, onClick={original=null;opening="";closing="";error="";editing=true},colors=ButtonDefaults.buttonColorsPrimary(),modifier=Modifier.fillMaxWidth().padding(20.dp)) {
            Text("添加快捷表情",color=colors.onPrimary)
        }
    }
    if (confirmReset) Dialog(onDismissRequest={confirmReset=false}) {
        Card(settingsDialogModifier()) { Column(settingsDialogContentModifier(), verticalArrangement=Arrangement.spacedBy(SettingsDialogSpacing)) {
            Text("恢复默认快捷表情？")
            Text("这会替换无法读取的快捷表情配置。")
            Row(horizontalArrangement=Arrangement.spacedBy(SettingsActionSpacing)) {
                TextButton(text="取消", modifier=Modifier.weight(1f), onClick={confirmReset=false})
                TextButton(text="恢复默认", modifier=Modifier.weight(1f), onClick={
                    store.reset(); rules=store.entries(); loadFailed=false; confirmReset=false
                })
            }
        } }
    }
    if(editing) Dialog(onDismissRequest={},properties=DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress=false,dismissOnClickOutside=false)) {
        Card(settingsDialogModifier()) { Column(settingsDialogContentModifier(),verticalArrangement=Arrangement.spacedBy(SettingsDialogSpacing)) {
            Text(if(original==null) "添加快捷表情" else "修改快捷表情",fontSize=20.sp,fontWeight=FontWeight.SemiBold)
            TextField(value=opening,onValueChange={if(it.length<=64) opening=it},label="emoji 表情")
            TextField(value=closing,onValueChange={if(it.length<=2100) closing=it},label="关键词（用 , 分隔）")
            if(error.isNotEmpty()) Text(error,fontSize=13.sp)
            if(original!=null) TextButton(text="删除快捷表情",modifier=Modifier.fillMaxWidth(),onClick={store.delete(original!!);rules=store.entries();editing=false})
            Row(horizontalArrangement=Arrangement.spacedBy(SettingsActionSpacing)) {
                TextButton(text="取消",modifier=Modifier.weight(1f),onClick={editing=false})
                Button(modifier=Modifier.weight(1f),colors=ButtonDefaults.buttonColorsPrimary(),onClick={
                    try {
                        store.save(original,opening,closing);rules=store.entries();editing=false
                    } catch(e:Exception) { error=e.message ?: "快捷表情无效" }
                }) { Text("保存",color=colors.onPrimary) }
            }
        } }
    }
}
