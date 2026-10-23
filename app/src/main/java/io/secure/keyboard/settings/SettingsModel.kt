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

/** Private drafts are discarded when leaving their page or backgrounding the activity. */
internal data class ContentEdit(val category: String, val id: String? = null,
    val first: TextFieldValue = TextFieldValue(), val second: TextFieldValue = TextFieldValue(), val reasoningEffort: String = "low", val modelId: String = "")

internal class SettingsModel(app: Application) : AndroidViewModel(app) {
    val apiStore = ApiConfigurationStore(app)
    var apiDraft by mutableStateOf(ApiConfiguration())
    var apiModels by mutableStateOf<List<String>>(emptyList())
    fun loadApi() { if (visible && page == "api") refresh() }
    fun fetchApiModels() {
        val snapshot = apiDraft
        var result = emptyList<String>()
        task(refreshContent = false, success = {
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
    private var taskBusy by mutableStateOf(false)
    private var loading by mutableStateOf(false)
    val busy: Boolean get() = taskBusy || loading
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
    init { prefs.registerOnSharedPreferenceChangeListener(listener) }
    override fun onCleared() { releaseSensitive(); prefs.unregisterOnSharedPreferenceChangeListener(listener) }

    private var page: String? = null
    private var visible by mutableStateOf(false)
    private var generation = 0L
    private var loadJob: Job? = null
    private var loadRevision = 0L

    private fun releaseSensitive() {
        generation++
        loadRevision++; loading = false
        loadJob?.cancel(); loadJob = null
        apiDraft = ApiConfiguration(); apiModels = emptyList()
        clipboard = emptyList(); phrases = emptyList(); chinese = emptyList()
        english = emptyList(); emoji = emptyList(); aiPresets = emptyList()
        edit = null; pendingBackup = null; message = null
    }

    fun showPage(value: String) {
        if (visible && page == value) return
        releaseSensitive()
        page = value; visible = true
        refresh()
    }

    fun hide() { visible = false; releaseSensitive() }

    fun refresh() {
        if (!visible) return
        loadJob?.cancel()
        val loadTicket = ++loadRevision
        loading = true
        val ticket = generation
        val route = page
        loadJob = viewModelScope.launch {
            try {
                // Only the displayed category is retained; unrelated pages do not read content.
                val value: Any? = withContext(Dispatchers.IO) {
                    when (route) {
                        "api" -> apiStore.load()
                        "clipboard" -> content.clipboardForDisplay()
                        "phrases" -> content.phrasesForDisplay()
                        "dictionary" -> content.dictionary(DictionaryKind.CHINESE)
                        "english_dictionary" -> content.dictionary(DictionaryKind.ENGLISH)
                        "emoji" -> content.recentEmoji()
                        "custom_ai" -> content.aiPresets()
                        else -> null
                    }
                }
                if (!visible || ticket != generation || route != page) return@launch
                @Suppress("UNCHECKED_CAST")
                when (route) {
                    "api" -> apiDraft = value as ApiConfiguration
                    "clipboard" -> clipboard = value as List<ContentStore.ClipboardItem>
                    "phrases" -> phrases = value as List<ContentStore.Phrase>
                    "dictionary" -> chinese = value as List<ContentStore.DictionaryEntry>
                    "english_dictionary" -> english = value as List<ContentStore.DictionaryEntry>
                    "emoji" -> emoji = value as List<String>
                    "custom_ai" -> aiPresets = value as List<ContentStore.AiPreset>
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (visible && ticket == generation) message = "读取失败，请重试" }
            finally { if (loadTicket == loadRevision) loading = false }
        }
    }

    /** Picker results must not disappear when onResume is refreshing the content lists. */
    fun fileTask(refreshContent: Boolean = false, success: (() -> Unit)? = null, work: () -> Unit) {
        viewModelScope.launch {
            snapshotFlow { busy to visible }.first { !it.first && it.second }
            task(refreshContent, success, work)
        }
    }
    fun task(refreshContent: Boolean = true, success: (() -> Unit)? = null, work: () -> Unit) {
        if (busy) return
        taskBusy = true
        val ticket = generation
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { work() }
                if (visible && refreshContent) refresh()
                if (visible && ticket == generation) {
                    success?.invoke()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (visible && ticket == generation) message = e.message ?: "操作失败，请重试" }
            finally { taskBusy = false }
        }
    }
    fun saveEdit() {
        val current = edit ?: return
        task(success = { edit = null }) {
            val text = current.first.text
            require(text.isNotBlank()) { "内容不能为空" }
            when (current.category) {
                "custom_ai" -> content.saveAiPreset(current.id, text, current.second.text, current.reasoningEffort, current.modelId)
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
