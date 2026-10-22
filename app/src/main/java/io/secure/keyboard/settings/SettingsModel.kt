package io.secure.keyboard.settings

import android.app.Application
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.secure.keyboard.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Current edit survives rotation in memory; private text never enters saved-state bundles. */
internal data class ContentEdit(val category: String, val id: String? = null,
    val first: TextFieldValue = TextFieldValue(), val second: TextFieldValue = TextFieldValue(), val reasoningEffort: String = "low")

internal class SettingsModel(app: Application) : AndroidViewModel(app) {
    val apiStore = ApiConfigurationStore(app)
    var apiDraft by mutableStateOf(ApiConfiguration())
    var apiModels by mutableStateOf<List<String>>(emptyList())
    fun loadApi() { runCatching { apiStore.load() }.onSuccess { apiDraft = it }
        .onFailure { message = "API 配置读取失败，可清除后重新配置" } }
    fun fetchApiModels() {
        val snapshot = apiDraft
        var result = emptyList<String>()
        task(success = {
            if (apiDraft.grokUrl == snapshot.grokUrl && apiDraft.grokKey == snapshot.grokKey) {
                apiModels = result
                message = if (result.isEmpty()) "此密钥未返回可用模型" else "已获取 ${result.size} 个模型，请选择后保存"
            }
        }) {
            try { result = GrokModelCatalog.fetch(snapshot) }
            catch (e: IllegalArgumentException) { throw e }
            catch (e: IllegalStateException) { throw e }
            catch (e: Exception) { error("获取模型失败，请检查网络与接口配置") }
        }
    }
    val settings = SettingsStore(app)
    val content = ContentStore(app)
    private val prefs = app.getSharedPreferences("settings", 0)
    var revision by mutableIntStateOf(0)
        private set
    var busy by mutableStateOf(false)
        private set
    var pendingBackup by mutableStateOf<ContentStore.BackupPlan?>(null)
    var message by mutableStateOf<String?>(null)
    var edit by mutableStateOf<ContentEdit?>(null)
    var clipboard by mutableStateOf<List<ContentStore.ClipboardItem>>(emptyList())
    var phrases by mutableStateOf<List<ContentStore.Phrase>>(emptyList())
    var chinese by mutableStateOf<List<ContentStore.DictionaryEntry>>(emptyList())
    var english by mutableStateOf<List<ContentStore.DictionaryEntry>>(emptyList())
    var aiPresets by mutableStateOf<List<ContentStore.AiPreset>>(emptyList())
    var emoji by mutableStateOf<List<String>>(emptyList())
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
    init { prefs.registerOnSharedPreferenceChangeListener(listener); loadApi(); refresh() }
    override fun onCleared() { prefs.unregisterOnSharedPreferenceChangeListener(listener) }

    private suspend fun loadContent() {
        val snapshot = withContext(Dispatchers.IO) {
            Snapshot(content.clipboardForDisplay(), content.phrasesForDisplay(),
                content.dictionary(), content.dictionary(DictionaryKind.ENGLISH), content.recentEmoji(), content.aiPresets())
        }
        clipboard = snapshot.clips; phrases = snapshot.phrases
        chinese = snapshot.chinese; english = snapshot.english; emoji = snapshot.emoji; aiPresets = snapshot.aiPresets
    }
    private data class Snapshot(val clips: List<ContentStore.ClipboardItem>, val phrases: List<ContentStore.Phrase>,
        val chinese: List<ContentStore.DictionaryEntry>, val english: List<ContentStore.DictionaryEntry>, val emoji: List<String>, val aiPresets: List<ContentStore.AiPreset>)

    /** Picker results must not disappear when onResume is refreshing the content lists. */
    fun fileTask(success: (() -> Unit)? = null, work: () -> Unit) {
        viewModelScope.launch {
            snapshotFlow { busy }.first { !it }
            task(success, work)
        }
    }
    fun refresh() = task { }
    fun task(success: (() -> Unit)? = null, work: () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { work() }
                loadContent()
                success?.invoke()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "操作失败，请重试" }
            finally { busy = false }
        }
    }
    fun saveEdit() {
        val current = edit ?: return
        task(success = { edit = null }) {
            val text = current.first.text
            require(text.isNotBlank()) { "内容不能为空" }
            when (current.category) {
                "custom_ai" -> content.saveAiPreset(current.id, text, current.second.text, current.reasoningEffort)
                "phrases" -> if (current.id == null) content.addPhrase(text) else content.updatePhrase(current.id, text)
                "dictionary", "english_dictionary" -> {
                    val kind = if (current.category == "dictionary") DictionaryKind.CHINESE else DictionaryKind.ENGLISH
                    if (current.id == null) content.addDictionary(text, current.second.text, kind)
                    else content.updateDictionary(current.id, text, current.second.text, kind)
                }
            }
        }
    }
}
