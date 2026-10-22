package io.secure.keyboard.ai

import android.content.Context

/** Explicit user settings, separate from drafts and conversation history. */
internal class AiPromptStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("ai_builtin_prompts", Context.MODE_PRIVATE)
    data class Prompt(val id: String, val title: String, val default: String)
    fun read(id: String): String = prefs.getString(id, definition(id).default) ?: definition(id).default
    fun save(id: String, text: String) {
        definition(id)
        require(text.length <= 12000) { "提示词不能超过 12000 字符" }
        prefs.edit().putString(id, text).apply()
    }
    fun restore(id: String) { definition(id); prefs.edit().remove(id).apply() }
    fun system(task: String, tone: String, target: String, custom: String): String {
        if (task == "custom") {
            val instruction = AiWriting.instruction(task, tone, target, custom)
            val shared = read("custom_common").trim()
            return if (shared.isEmpty()) instruction else "$shared\n\n$instruction"
        }
        val id = if (task == "tone") "tone_${tone.takeIf { it in AiWriting.tones } ?: "自然"}" else task
        val instruction = read(id).replace("{target}", target)
        return read("common") + "\n首条用户消息为待处理原文；后续用户消息为补充要求，请据此继续修改上一轮正文。\n\n处理要求：\n" + instruction
    }
    companion object {
        val prompts: List<Prompt> = buildList {
            add(Prompt("common", "普通 AI 通用提示词", AiWriting.common))
            for (task in listOf("polish", "correct", "translate"))
                add(Prompt(task, AiWriting.tasks.getValue(task), AiWriting.instruction(task, "自然", "{target}", "")))
            for (tone in AiWriting.tones)
                add(Prompt("tone_$tone", "语气 · $tone", AiWriting.instruction("tone", tone, "", "")))
            add(Prompt("custom_common", "自定义 AI 统一提示词", ""))
        }
        fun definition(id: String) = prompts.first { it.id == id }
    }
}
