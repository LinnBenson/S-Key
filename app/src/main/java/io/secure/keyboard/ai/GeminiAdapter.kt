package io.secure.keyboard.ai

import org.json.JSONArray
import org.json.JSONObject

/** Native Interactions API; continuation IDs avoid persisting complete user messages locally. */
internal object GeminiAdapter : AiProviderAdapter {
    override val path = "/interactions"
    override fun headers(key: String) = mapOf("x-goog-api-key" to key)
    override fun payload(request: ChatInput): JSONObject = with(request) {
        val input: Any = if (image == null) text else {
            val match = Regex("data:(image/[a-zA-Z0-9.+-]+);base64,([A-Za-z0-9+/=]+)").matchEntire(image)
                ?: error("图片格式无效")
            JSONArray().put(JSONObject().put("type", "text").put("text", text.ifBlank { "请根据图片内容处理。" }))
                .put(JSONObject().put("type", "image").put("mime_type", match.groupValues[1]).put("data", match.groupValues[2]))
        }
        JSONObject().put("model", model).put("input", input).put("stream", true).put("store", true)
            // Interactions does not inherit system instructions/tools/configuration from prior turns.
            .put("system_instruction", instruction)
            .put("generation_config", JSONObject().put("thinking_level", if (reasoningEffort == "xhigh") "high" else reasoningEffort).put("thinking_summaries", "none"))
            .apply {
                if (previousResponseId != null) put("previous_interaction_id", previousResponseId)
                if (custom && webSearch) put("tools", JSONArray().put(JSONObject().put("type", "google_search")))
                // X search is an xAI-only capability.
            }
    }
    override fun decoder(): ChatDecoder = object : ChatDecoder {
        private val output = StringBuilder()
        private val steps = mutableMapOf<Int, String>()
        override fun accept(json: JSONObject): ChatEvent {
            check(!json.has("error") || json.isNull("error")) { "Gemini 未返回完整文字结果，请重试" }
            when (json.optString("event_type")) {
                "step.start" -> {
                    check(steps.size < 512) { "Gemini 响应步骤过多" }
                    steps[json.getInt("index")] = json.getJSONObject("step").getString("type")
                }
                "step.delta" -> {
                    val delta = json.getJSONObject("delta")
                    if (steps[json.getInt("index")] == "model_output" && delta.optString("type") == "text") {
                        val text = delta.getString("text")
                        check(output.length + text.length <= 20000) { "AI 响应过大，请缩短原文" }
                        output.append(text)
                        return ChatEvent(delta = text)
                    }
                }
                "interaction.completed" -> {
                    val result = json.getJSONObject("interaction")
                    check(result.optString("status") == "completed") { "Gemini 未返回完整文字结果" }
                    val text = if (result.has("steps")) outputText(result) else output.toString()
                    return ChatEvent(id = result.optString("id"), completedText = text)
                }
                "error", "interaction.failed", "interaction.incomplete", "interaction.cancelled" -> error("Gemini 未返回完整文字结果，请重试")
            }
            return ChatEvent()
        }
    }
    fun outputText(result: JSONObject): String {
        check(result.optString("status") == "completed") { "Gemini 未返回完整文字结果" }
        val steps = result.optJSONArray("steps") ?: error("Gemini 响应缺少正文")
        val text = StringBuilder()
        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i)
            if (step.optString("type") != "model_output") continue
            val content = step.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.getJSONObject(j)
                if (part.optString("type") == "text") text.append(part.getString("text"))
                check(text.length <= 20000) { "Gemini 响应过大" }
            }
        }
        return text.toString().trim().also { check(it.isNotEmpty()) { "Gemini 未返回有效文字" } }
    }
    override fun models(json: JSONObject): ModelPage {
        val rows = json.optJSONArray("models") ?: JSONArray()
        val ids = (0 until rows.length()).mapNotNull {
            val row = rows.getJSONObject(it)
            val name = row.optString("name").removePrefix("models/")
            name.takeIf { it.startsWith("gemini-") && !it.contains("embedding") }
        }
        return ModelPage(ids, json.optString("nextPageToken"))
    }
}
