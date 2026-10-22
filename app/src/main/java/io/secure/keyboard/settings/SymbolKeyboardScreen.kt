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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.secure.keyboard.data.SymbolKeyboardStore
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable internal fun SymbolKeyboardScreen(modifier: Modifier=Modifier) {
    val context=LocalContext.current
    val store=remember(context) { SymbolKeyboardStore(context) }
    var chinese by rememberSaveable { mutableStateOf(true) }
    var second by rememberSaveable { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var rowIndex by rememberSaveable { mutableIntStateOf(-1) }
    var columnIndex by rememberSaveable { mutableIntStateOf(0) }
    var draft by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }
    var restore by rememberSaveable { mutableStateOf(false) }
    val colors=MiuixTheme.colorScheme
    val rows=remember(chinese,second,revision) { store.rows(chinese,second) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            listOf(true to "中文键盘",false to "英文键盘").forEach { (value,title) ->
                TextButton(text=title,onClick={chinese=value},modifier=Modifier.weight(1f),
                    colors=if(chinese==value) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors())
            }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            listOf(false to "第一页",true to "第二页").forEach { (value,title) ->
                TextButton(text=title,onClick={second=value},modifier=Modifier.weight(1f),
                    colors=if(second==value) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors())
            }
        }
        Text("点击下方符号键即可修改，保存后即时生效。四个页面独立保存。",fontSize=14.sp,color=colors.onSurfaceSecondary)
        Card {
            Column(Modifier.background(colors.secondaryContainer.copy(alpha=.35f)).padding(10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                @Composable fun RowScope.key(label:String,weight:Float=1f,editable:Boolean=false,onClick:()->Unit={}) {
                    Box(Modifier.weight(weight).height(52.dp).background(
                        if(editable) colors.surface else colors.secondaryContainer,RoundedCornerShape(7.dp))
                        .then(if(editable) Modifier.clickable(onClick=onClick) else Modifier),contentAlignment=Alignment.Center) {
                        Text(label,fontSize=if(editable) 19.sp else 12.sp,color=if(editable) colors.onSurface else colors.onSurfaceSecondary)
                    }
                }
                rows.forEachIndexed { r,row ->
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(3.dp)) {
                        if(r==2) key("=\\<",1.3f)
                        row.forEachIndexed { c,symbol -> key(symbol,editable=true) { rowIndex=r;columnIndex=c;draft=symbol;error="" } }
                        if(r==2) key("⌫",1.3f)
                    }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(3.dp)) {
                    key("ABC",1.7f);key(if(chinese) "，。" else ". ,",.9f)
                    key(if(chinese) "五笔" else "English",4.2f);key("◎",.9f);key("↵",1.7f)
                }
            }
        }
        Text("灰色功能键仅作位置参考。每个符号键填写一个字符；自动闭合规则按修改后的符号匹配。",fontSize=13.sp,color=colors.onSurfaceSecondary)
        TextButton(text="恢复当前页默认符号",onClick={restore=true},modifier=Modifier.fillMaxWidth())
    }
    if(rowIndex>=0) Dialog(onDismissRequest={},properties=DialogProperties(dismissOnBackPress=false,dismissOnClickOutside=false)) {
        Card { Column(Modifier.padding(20.dp).imePadding().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text("修改符号",fontSize=20.sp)
            Text("${if(chinese) "中文" else "英文"} · 第 ${if(second) 2 else 1} 页 · 第 ${rowIndex+1} 行第 ${columnIndex+1} 键",fontSize=13.sp,color=colors.onSurfaceSecondary)
            TextField(value=draft,onValueChange={if(it.length<=8) draft=it},label="符号（一个字符）")
            if(error.isNotEmpty()) Text(error,fontSize=13.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                TextButton(text="取消",onClick={rowIndex=-1},modifier=Modifier.weight(1f))
                Button(onClick={try {store.save(chinese,second,rowIndex,columnIndex,draft);revision++;rowIndex=-1} catch(e:Exception){error=e.message ?: "符号无效"}},
                    colors=ButtonDefaults.buttonColorsPrimary(),modifier=Modifier.weight(1f)) { Text("保存",color=colors.onPrimary) }
            }
        } }
    }
    if(restore) Dialog(onDismissRequest={restore=false}) {
        Card { Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text("恢复${if(chinese) "中文" else "英文"}第 ${if(second) 2 else 1} 页？",fontSize=20.sp)
            Text("此页修改将被默认符号替换。其他页面不受影响。",fontSize=14.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                TextButton(text="取消",onClick={restore=false},modifier=Modifier.weight(1f))
                Button(onClick={store.restore(chinese,second);revision++;restore=false},colors=ButtonDefaults.buttonColorsPrimary(),modifier=Modifier.weight(1f)) {Text("恢复",color=colors.onPrimary)}
            }
        } }
    }
}
