package io.secure.keyboard.ai

import io.secure.keyboard.data.ContentStore

internal data class VoiceAiChoice(val id: String, val title: String, val task: String,
    val tone: String = "自然", val preset: ContentStore.AiPreset? = null) {
    companion object {
        fun options(presets: List<ContentStore.AiPreset>) = buildList {
            AiWriting.tasks.filterKeys { it != "tone" && it != "custom" }.forEach { (id, title) ->
                add(VoiceAiChoice(id, title, id))
            }
            AiWriting.tones.forEach { add(VoiceAiChoice("tone:$it", "${it}语气", "tone", it)) }
            presets.forEach { add(VoiceAiChoice("custom:${it.id}", it.name, "custom", preset = it)) }
        }
        val defaults = setOf("polish", "correct", "translate", "tone:自然")
        const val LIMIT = 4
    }
}
