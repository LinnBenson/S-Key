package io.secure.keyboard.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.secure.keyboard.data.SymbolClosureStore
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable internal fun SymbolClosureScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember(context) { SymbolClosureStore(context) }
    var rules by remember { mutableStateOf(store.rules()) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var original by rememberSaveable { mutableStateOf<String?>(null) }
    var opening by rememberSaveable { mutableStateOf("") }
    var closing by rememberSaveable { mutableStateOf("") }
    var move by rememberSaveable { mutableStateOf("1") }
    var error by rememberSaveable { mutableStateOf("") }
    val colors = MiuixTheme.colorScheme
    Column(modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f),contentPadding=SettingsPagePadding,verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item { SettingsSection("自动配对 · ${rules.size} 条规则", spacedAfter = false) {
                if (rules.isEmpty()) Text("暂无规则，符号按原样输入", modifier = Modifier.padding(20.dp), color = colors.onSurfaceSecondary)
                rules.forEach { rule ->
                    BasicComponent(title = "${rule.opening}  →  ${rule.closing}",
                        summary = if (rule.moveBack == 0) "光标停在末尾" else "光标向左移动 ${rule.moveBack} 位",
                        leftAction = { Box(Modifier.padding(end = 12.dp)) { EditorBadge(rule.opening + rule.closing, compact = true) } },
                        rightActions = { EditorIcon("forward") }, onClick = {
                            original=rule.opening;opening=rule.opening;closing=rule.closing;move=rule.moveBack.toString();error="";editing=true
                        })
                }
            } }

        }
        Button(onClick={original=null;opening="";closing="";move="1";error="";editing=true},colors=ButtonDefaults.buttonColorsPrimary(),modifier=Modifier.fillMaxWidth().padding(20.dp)) {
            Text("添加规则",color=colors.onPrimary)
        }
    }
    if(editing) Dialog(onDismissRequest={},properties=DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress=false,dismissOnClickOutside=false)) {
        Card(settingsDialogModifier()) { Column(settingsDialogContentModifier(),verticalArrangement=Arrangement.spacedBy(SettingsDialogSpacing)) {
            Text(if(original==null) "添加规则" else "修改规则",fontSize=20.sp,fontWeight=FontWeight.SemiBold)
            TextField(value=opening,onValueChange={if(it.length<=8) opening=it},label="输入符号（一个字符）")
            TextField(value=closing,onValueChange={if(it.length<=32) closing=it},label="闭合符号")
            TextField(value=move,onValueChange={if(it.length<=3 && it.all { c -> c in '0'..'9' }) move=it},label="光标回移位")
            Text("例如 { ｜ } ｜ 1，插入 {} 后光标停在中间。",fontSize=13.sp,color=colors.onSurfaceSecondary)
            if(error.isNotEmpty()) Text(error,fontSize=13.sp)
            if(original!=null) TextButton(text="删除规则",modifier=Modifier.fillMaxWidth(),onClick={store.delete(original!!);rules=store.rules();editing=false})
            Row(horizontalArrangement=Arrangement.spacedBy(SettingsActionSpacing)) {
                TextButton(text="取消",modifier=Modifier.weight(1f),onClick={editing=false})
                Button(modifier=Modifier.weight(1f),colors=ButtonDefaults.buttonColorsPrimary(),onClick={
                    try {
                        val count=requireNotNull(move.toIntOrNull()) { "请输入回移位数" }
                        store.save(original,SymbolClosureStore.Rule(opening,closing,count));rules=store.rules();editing=false
                    } catch(e:Exception) { error=e.message ?: "规则无效" }
                }) { Text("保存",color=colors.onPrimary) }
            }
        } }
    }
}
