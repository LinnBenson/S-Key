package io.secure.keyboard.settings

import android.app.Application
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.first
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.secure.keyboard.dictionaries.DictionaryRepository
import io.secure.keyboard.dictionaries.DictionarySource
import io.secure.keyboard.ui.KeyboardIcons
import kotlinx.coroutines.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal class BuiltinDictionaryModel(app: Application, private val savedState: SavedStateHandle): AndroidViewModel(app) {
    val repository by lazy { DictionaryRepository(app) }
    var libraries by mutableStateOf<List<DictionaryRepository.Library>>(emptyList())
    var selected by mutableStateOf<DictionaryRepository.Library?>(null)
    var page by mutableStateOf<DictionarySource.Page?>(null)
    var editor by mutableStateOf(TextFieldValue())
    var text: String
        get()=editor.text
        set(value) { editor=TextFieldValue(value) }
    var query by mutableStateOf("")
    var dirty by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var status by mutableStateOf("正在读取词库文件")
    var showAdd by mutableStateOf(false)
    var showMenu by mutableStateOf(false)
    var rename by mutableStateOf<String?>(null)
    var filename by mutableStateOf("custom_wubi.dict.yaml")
    var kind by mutableStateOf("wubi")
    var confirmation by mutableStateOf<Pair<String,()->Unit>?>(null)
    var pendingImportKind: String
        get() = savedState["importKind"] ?: "wubi"
        set(value) { savedState["importKind"] = value }
    var pendingExport: Long?
        get() = savedState["exportId"]
        set(value) { savedState["exportId"] = value }
    val unsaved get()=page?.let { text!=it.text } == true
    init { run {} }
    fun run(message: String?=null, waitUntilReady: Boolean=false, notify: Boolean=false, completion: (() -> String)?=null, work: suspend ()->Unit) {
        if(busy && !waitUntilReady) return
        viewModelScope.launch(start=CoroutineStart.UNDISPATCHED) {
            if (busy) snapshotFlow { busy }.first { !it }
            busy=true
            try {
                withContext(Dispatchers.IO) { work() }
                val result=withContext(Dispatchers.IO) { repository.libraries() to repository.dirty() }
                libraries=result.first; dirty=result.second
                selected=selected?.let { current -> libraries.firstOrNull { it.id==current.id } }
                val current=selected
                if(current!=null && page!=null && !unsaved && current.sourcePath!=page?.version) {
                    val refreshed=withContext(Dispatchers.IO) { repository.page(current.id,page!!.start) }
                    page=refreshed; text=refreshed.text
                }
                status=completion?.invoke() ?: message ?: if(dirty) "有更改待编译" else "当前词库已应用"
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { status=e.message ?: "操作失败，当前生效词库未改变" }
            finally { busy=false }
            if (notify) android.widget.Toast.makeText(getApplication(), status, android.widget.Toast.LENGTH_LONG).show()
        }
    }
    fun importFile(uri: android.net.Uri) {
        val kind=pendingImportKind
        var importedName = ""
        var replaced = false
        run(waitUntilReady=true, notify=true, completion={
            "已${if (replaced) "覆盖" else "添加"}文件：$importedName，编译后生效"
        }) {
            val resolver=getApplication<Application>().contentResolver
            val name=resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
                if(it.moveToFirst()) it.getString(0) else null
            } ?: error("无法获取文件名")
            importedName = name
            replaced = repository.libraries().any { it.kind == kind && it.filename == name }
            repository.addFile(name,kind,resolver.openInputStream(uri) ?: error("无法打开文件"),replaceExisting=true)
        }
    }
    fun importPackage(uri: android.net.Uri) {
        var result: io.secure.keyboard.dictionaries.DictionaryPackage.Result? = null
        run(waitUntilReady=true, notify=true, completion={ checkNotNull(result).message() }) {
            val app = getApplication<Application>()
            val context = currentCoroutineContext()
            result = io.secure.keyboard.dictionaries.DictionaryPackage.import(
                app.contentResolver.openInputStream(uri) ?: error("无法打开词库包"),
                app.cacheDir, repository,
            ) { context.ensureActive() }
        }
    }
    fun exportFile(uri: android.net.Uri?) {
        val id=pendingExport
        pendingExport=null
        if(uri!=null && id!=null) run("原始文件已导出", waitUntilReady=true) {
            val resolver=getApplication<Application>().contentResolver
            repository.export(id,resolver.openOutputStream(uri,"wt") ?: error("无法写入文件"))
        }
    }
    fun open(lib: DictionaryRepository.Library,start: Int=0) {
        if(busy) return
        busy=true
        viewModelScope.launch {
            try {
                val result=withContext(Dispatchers.IO) { repository.page(lib.id,start.coerceAtLeast(0)) }
                selected=lib; page=result; text=result.text
            } catch(e: Exception) { status=e.message ?: "文件读取失败" }
            finally { busy=false }
        }
    }
    fun guard(action: ()->Unit) {
        if(busy) return
        if(unsaved) confirmation="当前分段有未保存的修改，放弃修改并继续？" to action else action()
    }
    fun back()=guard { selected=null; page=null; text="" }
    fun save() {
        val lib=selected ?: return; val old=page ?: return; val value=text
        if(busy) return
        busy=true
        viewModelScope.launch {
            try {
                val result=withContext(Dispatchers.IO) { repository.savePage(lib.id,old,value); repository.page(lib.id,old.start) to repository.libraries() }
                page=result.first; text=result.first.text; libraries=result.second; selected=libraries.first { it.id==lib.id }; dirty=true
                status="文件已保存，编译后生效"
            } catch(e: Exception) { status=e.message ?: "保存失败" }
            finally { busy=false }
        }
    }
    fun find() {
        val lib=selected ?: return
        if (query.isBlank() || busy) {
            return
        }

        guard {
            busy=true
            viewModelScope.launch {
                try {
                    val result=withContext(Dispatchers.IO) {
                        val line=repository.find(lib.id,query,(page?.start ?: -1)+1) ?: repository.find(lib.id,query,0)
                        line?.let { repository.page(lib.id,it) }
                    }
                    if(result==null) status="没有找到匹配内容" else { page=result; text=result.text; status="已定位到第 ${result.start+1} 行" }
                } catch(e: Exception) { status=e.message ?: "搜索失败" } finally { busy=false }
            }
        }
    }
    fun compile()=run("编译成功，已应用新词库") { repository.compile { message -> viewModelScope.launch(Dispatchers.Main) { status=message } } }
}

@Composable private fun DictionaryIcon(name: String,description: String?=null,tint: androidx.compose.ui.graphics.Color=MiuixTheme.colorScheme.onSurface) {
    Icon(painter=painterResource(KeyboardIcons.resource(name)),contentDescription=description,modifier=Modifier.size(22.dp),tint=tint)
}
private fun kindLabel(kind: String)=when(kind) { "wubi"->"五笔"; "pinyin"->"拼音"; else->"英文" }

@Composable internal fun BuiltinDictionaryScreen(
    modifier: Modifier=Modifier,
    model: BuiltinDictionaryModel,
    onImport: () -> Unit,
    onImportPackage: () -> Unit,
    onExport: (String) -> Unit,
) {
    val colors=MiuixTheme.colorScheme
    BackHandler(enabled=model.selected!=null) { model.back() }
    val lib=model.selected
    Column(modifier.fillMaxSize().imePadding()) {
        if(lib==null) {
            LazyColumn(Modifier.weight(1f),contentPadding=SettingsPagePadding,verticalArrangement=Arrangement.spacedBy(18.dp)) {
                item {
                    Card { Row(Modifier.padding(20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(SettingsDialogSpacing)) {
                        if(model.busy) CircularProgressIndicator(modifier=Modifier.size(24.dp)) else DictionaryIcon(if(model.dirty) "edit" else "check",tint=colors.primary)
                        Column(Modifier.weight(1f)) {
                            Text(model.status,fontSize=16.sp,fontWeight=FontWeight.Medium)
                            Text("修改后需编译才能生效",fontSize=13.sp,color=colors.onSurfaceSecondary,modifier=Modifier.padding(top=4.dp))
                        }
                    } }
                }
                for(kind in listOf("wubi","pinyin","english")) {
                    val files=model.libraries.filter { it.kind==kind }
                    item {
                        SettingsSection("${kindLabel(kind)} · ${files.size} 个文件", spacedAfter = false) {
                            if(files.isEmpty()) Text("暂无文件",modifier=Modifier.padding(20.dp),color=colors.onSurfaceSecondary)
                            files.forEachIndexed { index,file ->
                                Row(Modifier.fillMaxWidth().clickable(enabled=!model.busy) { model.open(file) }.padding(horizontal=18.dp,vertical=18.dp),
                                    verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(SettingsDialogSpacing)) {
                                    Box(Modifier.size(40.dp).background(colors.secondaryContainer,RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center) { DictionaryIcon("phrases",tint=colors.primary) }
                                    Column(Modifier.weight(1f)) {
                                        Text(file.filename,fontSize=15.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
                                        val localPriority = io.secure.keyboard.dictionaries.BuiltinDictionaryFiles.isHighPriority(file.filename,file.kind)
                                        Text(if(localPriority) "本地高优先级词库" else if(file.sourcePath.isEmpty()) file.name else "已保存的源文件",fontSize=12.sp,color=colors.onSurfaceSecondary,modifier=Modifier.padding(top=5.dp))
                                    }
                                    DictionaryIcon("forward",tint=colors.onSurfaceSecondary)
                                }
                                if(index<files.lastIndex) HorizontalDivider(modifier=Modifier.padding(start=72.dp,end=20.dp))
                            }
                        }
                    }
                }
                item {
                    Card { BasicComponent(title="恢复默认文件",summary="恢复随应用提供的原始词库",leftAction={Box(Modifier.padding(end=14.dp)){DictionaryIcon("undo")}},onClick={
                        if(!model.busy) model.confirmation="恢复默认会替换所有词库源文件，包括自行添加的文件。编译后生效。" to { model.run("已恢复默认文件，请编译后生效") { model.repository.restoreDefaults() } }
                    }) }
                    Text("自定义词库无需编译。",modifier=Modifier.padding(horizontal=10.dp,vertical=14.dp),fontSize=12.sp,color=colors.onSurfaceSecondary)
                }
            }
            Card(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=10.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(SettingsActionSpacing)) {
                    Row(horizontalArrangement=Arrangement.spacedBy(SettingsActionSpacing)) {
                    TextButton(text="添加文件",enabled=!model.busy,onClick={model.showAdd=true},modifier=Modifier.weight(1f))
                    TextButton(text="添加词库包", enabled=!model.busy, onClick=onImportPackage, modifier=Modifier.weight(1.4f))
                    }
                    Button(colors=ButtonDefaults.buttonColorsPrimary(),onClick={model.compile()},enabled=!model.busy,modifier=Modifier.fillMaxWidth()) { Text("编译并应用",color=colors.onPrimary) }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick={model.back()},enabled=!model.busy) { DictionaryIcon("back","返回文件列表") }
                Column(Modifier.weight(1f).padding(horizontal=8.dp)) {
                    Text(lib.filename,fontSize=15.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text("${kindLabel(lib.kind)} · UTF-8 · ⇥ 为 Tab${if(model.unsaved) " · 未保存" else ""}",fontSize=12.sp,color=colors.onSurfaceSecondary)
                }
            }
            Row(Modifier.padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(SettingsActionSpacing)) {
                TextField(value=model.query,onValueChange={model.query=it},label="搜索文件内容",modifier=Modifier.weight(1f))
                TextButton(text="查找",onClick={model.find()},enabled=!model.busy && model.query.isNotBlank(),
                    colors=ButtonDefaults.textButtonColorsPrimary(),minHeight=48.dp,cornerRadius=14.dp)
            }
            val page=model.page
            Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(if((page?.count ?: 0)==0) "空文件" else "第 ${(page?.start ?: 0)+1}–${(page?.start ?: 0)+(page?.count ?: 0)} 行",
                    modifier=Modifier.weight(1f),fontSize=12.sp,color=colors.onSurfaceSecondary)
                @Composable fun action(icon: Int,label: String,enabled: Boolean=true,onClick: ()->Unit) {
                    IconButton(onClick=onClick,enabled=enabled,modifier=Modifier.size(44.dp)
                        .background(colors.secondaryContainer.copy(alpha=if(enabled) 1f else 0.4f),RoundedCornerShape(12.dp))) {
                        Icon(painter=painterResource(icon),contentDescription=label,modifier=Modifier.size(22.dp),
                            tint=colors.onSurface.copy(alpha=if(enabled) 1f else 0.3f))
                    }
                }
                action(io.secure.keyboard.R.drawable.ic_dictionary_more,"文件操作",!model.busy) { model.showMenu=true }
                action(io.secure.keyboard.R.drawable.ic_dictionary_tab,"插入 Tab 分隔符",!model.busy) {
                    val value=model.editor;val start=value.selection.min;val end=value.selection.max
                    if(value.text.length<256_000) model.editor=TextFieldValue(value.text.replaceRange(start,end,"\t"),TextRange(start+1))
                }
                action(KeyboardIcons.resource("previous"),"上一段",!model.busy && (page?.start ?: 0)>0) {
                    model.guard { model.open(lib,((page?.start ?: 0)-120).coerceAtLeast(0)) }
                }
                action(KeyboardIcons.resource("forward"),"下一段",!model.busy && page?.hasNext==true) {
                    model.guard { model.open(lib,page!!.start+page.count) }
                }
            }
            Card(Modifier.weight(1f).fillMaxWidth().padding(horizontal=20.dp)) {
                val scroll=rememberScrollState()
                key(page?.start,lib.id) {
                    BasicTextField(value=model.editor,onValueChange={if(it.text.length<=256_000) model.editor=it},readOnly=model.busy,
                        modifier=Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
                        textStyle=TextStyle(fontFamily=FontFamily.Monospace,fontSize=14.sp,lineHeight=22.sp,color=colors.onSurface),
                        cursorBrush=SolidColor(colors.primary),
                        visualTransformation=VisualTransformation { value ->
                            TransformedText(AnnotatedString(value.text.replace('\t','⇥')),OffsetMapping.Identity)
                        })
                }
            }
            Row(Modifier.fillMaxWidth().padding(20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(SettingsDialogSpacing)) {
                Text(model.status,modifier=Modifier.weight(1f),fontSize=12.sp,color=colors.onSurfaceSecondary,maxLines=3,overflow=TextOverflow.Ellipsis)
                Button(colors=ButtonDefaults.buttonColorsPrimary(),onClick={model.save()},enabled=!model.busy && model.unsaved) { Text("保存文件",color=colors.onPrimary) }
            }
        }
    }
    @Composable fun modal(onDismiss: ()->Unit,content: @Composable ColumnScope.()->Unit) {
        Dialog(onDismissRequest=onDismiss, properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) { Card(settingsDialogModifier()) { Column(settingsDialogContentModifier(),verticalArrangement=Arrangement.spacedBy(SettingsDialogSpacing),content=content) } }
    }
    if(model.showAdd) modal({model.showAdd=false}) {
        Text("添加词库文件",fontSize=20.sp,fontWeight=FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth()) { listOf("wubi","pinyin","english").forEach { kind ->
            TextButton(text=(if(model.kind==kind) "✓ " else "")+kindLabel(kind),modifier=Modifier.weight(1f),onClick={model.kind=kind;model.filename=if(kind=="english") "custom_english.tsv" else "custom_$kind.dict.yaml"})
        } }
        TextField(value=model.filename,onValueChange={model.filename=it},label="文件名")
        Text("同名文件校验通过后将覆盖原内容，编译后生效。",fontSize=12.sp,color=colors.onSurfaceSecondary)
        TextButton(text="从文件导入",modifier=Modifier.fillMaxWidth(),onClick={model.pendingImportKind=model.kind;model.showAdd=false;onImport()})
        Button(colors=ButtonDefaults.buttonColorsPrimary(),onClick={
            val filename=model.filename;val kind=model.kind
            val content=if(filename.endsWith(".yaml") || filename.endsWith(".yml")) "# 自定义词库\n---\nname: custom\nversion: \"1\"\nsort: by_weight\n...\n" else "# ${if(kind=="english") "单词\\t词频" else "文字\\t编码\\t词频"}\n"
            model.showAdd=false;model.run("文件已创建，打开后即可编辑", notify=true) { model.repository.addFile(filename,kind,content.byteInputStream(),allowEmptyTemplate=true) }
        },modifier=Modifier.fillMaxWidth()) { Text("新建文件",color=colors.onPrimary) }
    }
    if(model.showMenu && lib!=null) modal({model.showMenu=false}) {
        Text("文件操作",fontSize=20.sp,fontWeight=FontWeight.SemiBold)
        if (!model.repository.protectedFilename(lib.filename)) BasicComponent(title="重命名",onClick={model.showMenu=false;model.guard {model.rename=lib.filename}})
        BasicComponent(title="导出原始文件",onClick={model.showMenu=false;model.guard {model.pendingExport=lib.id;onExport(lib.filename)}})
        if (!model.repository.protectedFilename(lib.filename)) BasicComponent(title="删除文件",onClick={model.showMenu=false;model.confirmation="删除 ${lib.filename}？编译前仍使用旧版词库。" to {
            model.selected=null;model.page=null;model.text="";model.run("文件已删除，请编译后生效") {model.repository.deleteLibrary(lib.id)}
        }})
    }
    model.rename?.let { name -> modal({model.rename=null}) {
        Text("重命名文件",fontSize=20.sp,fontWeight=FontWeight.SemiBold)
        TextField(value=name,onValueChange={model.rename=it},label="文件名")
        Button(colors=ButtonDefaults.buttonColorsPrimary(),onClick={val id=lib?.id ?: return@Button;model.rename=null;model.run("文件名已修改") { model.repository.renameFile(id,name) }},modifier=Modifier.fillMaxWidth()) {Text("保存",color=colors.onPrimary)}
    } }
    model.confirmation?.let { (message,confirm) -> modal({model.confirmation=null}) {
        Text("确认操作",fontSize=20.sp,fontWeight=FontWeight.SemiBold);Text(message,fontSize=15.sp)
        Row(horizontalArrangement=Arrangement.spacedBy(SettingsActionSpacing)) {
            TextButton(text="取消",modifier=Modifier.weight(1f),onClick={model.confirmation=null})
            Button(colors=ButtonDefaults.buttonColorsPrimary(),onClick={model.confirmation=null;confirm()},modifier=Modifier.weight(1f)) {Text("确认",color=colors.onPrimary)}
        }
    } }
}
