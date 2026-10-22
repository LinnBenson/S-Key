package io.secure.keyboard.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.secure.keyboard.data.SymbolKeyboardStore
import io.secure.keyboard.data.LetterSymbolStore
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable internal fun SymbolKeyboardScreen(modifier: Modifier=Modifier) {
    val context=LocalContext.current
    val store=remember(context) { SymbolKeyboardStore(context) }
    var chinese by rememberSaveable { mutableStateOf(true) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    val second = page == 1
    val letters = page == 2
    val letterStore = remember(context) { LetterSymbolStore(context) }
    val alphabet = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    var revision by remember { mutableIntStateOf(0) }
    var rowIndex by rememberSaveable { mutableIntStateOf(-1) }
    var columnIndex by rememberSaveable { mutableIntStateOf(0) }
    var draft by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }
    var restore by rememberSaveable { mutableStateOf(false) }
    val colors=MiuixTheme.colorScheme
    val rows=remember(chinese,page,revision) { if(letters) alphabet.map { row -> row.map { letterStore.symbols(chinese,it) } } else store.rows(chinese,second) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(SettingsPagePadding),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement=Arrangement.spacedBy(SettingsActionSpacing)) {
            listOf(true to "中文键盘",false to "英文键盘").forEach { (value,title) ->
                TextButton(text=title,onClick={chinese=value},modifier=Modifier.weight(1f),
                    colors=if(chinese==value) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors())
            }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(SettingsActionSpacing)) {
            listOf(0 to "第一页",1 to "第二页",2 to "字母长按").forEach { (value,title) ->
                TextButton(text=title,onClick={page=value},modifier=Modifier.weight(1f),
                    colors=if(page==value) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors())
            }
        }
        Text("点击按键修改，保存后生效。",fontSize=14.sp,color=colors.onSurfaceSecondary)
        Card {
            Column(Modifier.background(colors.secondaryContainer.copy(alpha=.35f)).padding(10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                @Composable fun RowScope.key(label:String,weight:Float=1f,editable:Boolean=false,hint:String?=null,onClick:()->Unit={}) {
                    Box(Modifier.weight(weight).height(52.dp).background(
                        if(editable) colors.surface else colors.secondaryContainer,RoundedCornerShape(7.dp))
                        .then(if(editable) Modifier.clickable(onClick=onClick) else Modifier),contentAlignment=Alignment.Center) {
                        Text(label,fontSize=if(editable) 19.sp else 12.sp,color=if(editable) colors.onSurface else colors.onSurfaceSecondary)
                        if(hint != null) Text(hint, fontSize=9.sp, maxLines=1,
                            overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            color=colors.onSurfaceSecondary, modifier=Modifier.align(Alignment.TopEnd).padding(3.dp))
                    }
                }
                rows.forEachIndexed { r,row ->
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(3.dp)) {
                        if(r==2) key(if(letters) "⇧" else "=\\<",1.3f)
                        if(letters && r==1) Spacer(Modifier.weight(.45f))
                        row.forEachIndexed { c,symbol -> key(if(letters) alphabet[r][c].toString() else symbol,editable=true,hint=if(letters) { symbol.codePoints().findFirst().orElse(-1).let { if(it < 0) "" else String(Character.toChars(it)) } } else null) { rowIndex=r;columnIndex=c;draft=symbol;error="" } }
                        if(letters && r==1) Spacer(Modifier.weight(.45f))
                        if(r==2) key("⌫",1.3f)
                    }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(3.dp)) {
                    key("ABC",1.7f);key(if(chinese) "，。" else ". ,",.9f)
                    key(if(chinese) "五笔" else "English",4.2f);key("◎",.9f);key("↵",1.7f)
                }
            }
        }
        Text(if(letters) "可填写多个符号，长按后滑动选择；留空可取消。需开启“长按输入符号”。" else "每个按键可设置一个字符。灰色功能键不可修改。",fontSize=13.sp,color=colors.onSurfaceSecondary)
        TextButton(text="恢复当前页默认符号",onClick={restore=true},modifier=Modifier.fillMaxWidth())
    }
    if(rowIndex>=0) Dialog(onDismissRequest={},properties=DialogProperties(dismissOnBackPress=false,dismissOnClickOutside=false,usePlatformDefaultWidth=false)) {
        Card(settingsDialogModifier()) { Column(settingsDialogContentModifier(),verticalArrangement=Arrangement.spacedBy(SettingsDialogSpacing)) {
            Text("修改符号",fontSize=20.sp,fontWeight=FontWeight.SemiBold)
            Text("${if(chinese) "中文" else "英文"} · ${if(letters) "字母 ${alphabet[rowIndex][columnIndex]} 长按" else "第 ${if(second) 2 else 1} 页 · 第 ${rowIndex+1} 行第 ${columnIndex+1} 键"}",fontSize=13.sp,color=colors.onSurfaceSecondary)
            TextField(value=draft,onValueChange={if(it.length<=32) draft=it},label=if(letters) "长按符号（最多 8 个字符，可留空）" else "符号（一个字符）")
            if(error.isNotEmpty()) Text(error,fontSize=13.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(SettingsActionSpacing)) {
                TextButton(text="取消",onClick={rowIndex=-1},modifier=Modifier.weight(1f))
                Button(onClick={try {if(letters) letterStore.save(chinese,alphabet[rowIndex][columnIndex],draft) else store.save(chinese,second,rowIndex,columnIndex,draft);revision++;rowIndex=-1} catch(e:Exception){error=e.message ?: "符号无效"}},
                    colors=ButtonDefaults.buttonColorsPrimary(),modifier=Modifier.weight(1f)) { Text("保存",color=colors.onPrimary) }
            }
        } }
    }
    if(restore) Dialog(onDismissRequest={restore=false}, properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Card(settingsDialogModifier()) { Column(settingsDialogContentModifier(),verticalArrangement=Arrangement.spacedBy(SettingsDialogSpacing)) {
            Text("恢复${if(chinese) "中文" else "英文"}${if(letters) "字母长按符号" else "第 ${if(second) 2 else 1} 页"}？",fontSize=20.sp,fontWeight=FontWeight.SemiBold)
            Text("当前页的修改将被覆盖。",fontSize=14.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(SettingsActionSpacing)) {
                TextButton(text="取消",onClick={restore=false},modifier=Modifier.weight(1f))
                Button(onClick={if(letters) letterStore.restore(chinese) else store.restore(chinese,second);revision++;restore=false},colors=ButtonDefaults.buttonColorsPrimary(),modifier=Modifier.weight(1f)) {Text("恢复",color=colors.onPrimary)}
            }
        } }
    }
}
