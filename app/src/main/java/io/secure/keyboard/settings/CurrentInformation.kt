package io.secure.keyboard.settings

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.secure.keyboard.data.*
import io.secure.keyboard.dictionaries.DictionaryRepository
import io.secure.keyboard.voice.*
import io.secure.keyboard.ai.AiWriting
import io.secure.keyboard.translation.GoogleTranslation
import kotlinx.coroutines.*
import top.yukonga.miuix.kmp.basic.*
import org.json.JSONObject

@Composable
internal fun CurrentInformation(context: Context) {
    val app = context.applicationContext
    var local by remember { mutableStateOf(listOf("读取中", "读取中", "读取中")) }
    var config by remember { mutableStateOf<ApiConfiguration?>(null) }
    var states by remember { mutableStateOf(listOf("读取中", "读取中", "读取中")) }
    var checking by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    var cancelCall by remember { mutableStateOf<(() -> Unit)?>(null) }
    DisposableEffect(Unit) { onDispose { cancelCall?.invoke() } }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val clipboard = runCatching { "${ContentStore(app).clipboard().size} 条" }.getOrDefault("读取失败")
            val dictionaries = runCatching {
                val repository = DictionaryRepository(app)
                val file = repository.activeFile() ?: repository.defaultRuntime()
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("SELECT kind,count(*) FROM words WHERE kind IN ('wubi','pinyin','english') GROUP BY kind", null).use { rows ->
                        val counts = mutableListOf<Pair<String, Long>>()
                        while (rows.moveToNext()) counts += rows.getString(0) to rows.getLong(1)
                        "${counts.size} 类 · ${counts.sumOf { it.second }} 条词条"
                    }
                }
            }.getOrDefault("读取失败")
            val offline = runCatching {
                val repository = ModelRepository(app)
                if (!repository.isInstalled()) "未安装" else if (repository.verifyInstalled { isActive }) "已安装，可用" else "模型损坏，请重新导入"
            }.getOrDefault("检查失败")
            val api = runCatching { ApiConfigurationStore(app).load() }.getOrNull()
            withContext(Dispatchers.Main) {
                local = listOf(clipboard, dictionaries, offline)
                config = api
                states = (0..2).map { index -> api?.let { apiState(it, index) } ?: "配置读取失败" }
            }
        }
    }
    SettingsSection("当前信息") {
        listOf("剪贴板数量", "已编译词库数量", "离线模型状态").forEachIndexed { index, title ->
            BasicComponent(title = title, summary = local[index])
        }
        listOf("翻译 API 可用性", "${AiProviders.label(config?.aiProvider.orEmpty())} AI 可用性", "${AiProviders.label(config?.voiceProvider.orEmpty())} 语音可用性").forEachIndexed { index, title ->
            BasicComponent(title = title, summary = states[index], rightActions = {
                TextButton(text = if (checking == index) "检测中" else "检测", enabled = checking == null && config?.let { apiState(it, index) == "未检测" } == true,
                    onClick = {
                        val snapshot = config ?: return@TextButton
                        checking = index
                        states = states.toMutableList().apply { this[index] = "检测中…" }
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                try {
                                    when (index) {
                                        0 -> {
                                            val call = GoogleTranslation.Call()
                                            withContext(Dispatchers.Main) { cancelCall = call::cancel }
                                            call.execute(snapshot, "Hello", "en", "zh-CN")
                                        }
                                        1 -> {
                                            val call = AiWriting.Call()
                                            withContext(Dispatchers.Main) { cancelCall = call::cancel }
                                            call.execute(snapshot, "Reply OK", "Reply only OK.")
                                        }
                                        else -> {
                                            val call = VoiceHttpRequest()
                                            withContext(Dispatchers.Main) { cancelCall = call::cancel }
                                            checkVoice(snapshot, call)
                                        }
                                    }
                                    "可用（本次检测成功）"
                                } catch (e: CancellationException) { throw e }
                                catch (_: Exception) { "检测失败，请检查网络、密钥、模型或额度" }
                            }
                            states = states.toMutableList().apply { this[index] = result }
                            checking = null; cancelCall = null
                        }
                    })
            })
        }
    }
}

internal fun apiState(config: ApiConfiguration, index: Int): String {
    val key = when(index) { 0 -> config.googleKey; 1 -> config.chatKey; else -> when (config.voiceProvider) { "gemini" -> config.geminiVoiceKey; "openai" -> config.openaiVoiceKey; else -> config.voiceKey } }
    if (key.isBlank()) return "未配置"
    return try {
        when(index) {
            0 -> config.validatedGoogle()
            1 -> { config.validatedChat(); require(config.chatModel.isNotBlank()) }
            else -> { val voice = config.resolvedVoice(); require(voice.voiceModel.isNotBlank()); require(!voice.voiceModel.contains('\r') && !voice.voiceModel.contains('\n')) }
        }
        "未检测"
    } catch (_: Exception) { "配置不完整或无效" }
}

private fun checkVoice(config: ApiConfiguration, call: VoiceHttpRequest) {
    val c = config.resolvedVoice()
    val adapter = VoiceProviderAdapters.get(c.voiceProvider)
    val silence = ByteArray(32000)
    try {
        adapter.upload(c.voiceModel, silence, silence.size) {}.use { upload ->
            val result = call.execute(c.voiceUrl, adapter.headers(c.voiceKey), upload.contentType, upload.size,
                upload.write, { "HTTP $it" })
            adapter.validateResponse(result)
        }
    } finally { silence.fill(0) }
}
