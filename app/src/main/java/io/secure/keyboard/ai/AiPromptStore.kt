package io.secure.keyboard.ai

import android.content.Context

/** Explicit user settings, separate from drafts and conversation history. */
internal class AiPromptStore(context: Context) {
    private val conversations = io.secure.keyboard.data.AiConversationStore(context)
    private val prefs = context.applicationContext.getSharedPreferences("ai_builtin_prompts", Context.MODE_PRIVATE)
    data class Prompt(val id: String, val title: String, val default: String)
    fun read(id: String): String = prefs.getString(id, definition(id).default) ?: definition(id).default
    fun save(id: String, text: String, modelId: String? = null) {
        definition(id)
        require(text.length <= 12000) { "提示词不能超过 12000 字符" }
        val normalizedModel = modelId?.let(::normalizeModel)
        val changed = read(id) != text || (normalizedModel != null && model(id) != normalizedModel)
        prefs.edit().putString(id, text).apply {
            if (normalizedModel != null) putString("model_$id", normalizedModel)
        }.apply()
        if (changed) conversations.clearAsync()
    }
    fun model(id: String): String { definition(id); return prefs.getString("model_$id", "").orEmpty() }
    fun saveModel(id: String, model: String) = save(id, read(id), model)
    fun resolveModel(task: String, tone: String, customModel: String, fallback: String): String =
        (if (task == "custom") customModel else model(if (task == "tone") "tone_${tone.takeIf { it in AiWriting.tones } ?: "自然"}" else task))
            .trim().ifEmpty { fallback.trim() }
    fun restore(id: String) = save(id, definition(id).default)
    fun system(task: String, tone: String, custom: String): String {
        if (task == "custom") {
            val instruction = AiWriting.instruction(task, tone, custom)
            val shared = read("custom_common").trim()
            return if (shared.isEmpty()) instruction else "$shared\n\n$instruction"
        }
        val id = if (task == "tone") "tone_${tone.takeIf { it in AiWriting.tones } ?: "自然"}" else task
        val instruction = read(id)
        if (task == "translate") return instruction
        return read("common") + "\n首条用户消息为待处理原文；后续用户消息为补充要求，请据此继续修改上一轮正文。\n\n处理要求：\n" + instruction
    }
    companion object {
        fun normalizeModel(value: String): String = value.trim().also {
            require(it.length <= 256 && it.none { c -> c.isWhitespace() || c.isISOControl() }) { "模型 ID 最多 256 字符，不能包含空白字符" }
        }
        val prompts: List<Prompt> = buildList {
            add(Prompt("common", "普通 AI 通用提示词", AiWriting.common))
            for (task in listOf("polish", "correct", "translate"))
                add(Prompt(task, AiWriting.tasks.getValue(task), AiWriting.instruction(task, "自然", "")))
            for (tone in AiWriting.tones)
                add(Prompt("tone_$tone", "语气 · $tone", AiWriting.instruction("tone", tone, "")))
            add(Prompt("custom_common", "自定义 AI 统一提示词", ""))
        }
        fun definition(id: String) = prompts.first { it.id == id }
    }
}
