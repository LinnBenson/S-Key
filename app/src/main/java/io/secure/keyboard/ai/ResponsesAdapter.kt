package io.secure.keyboard.ai

import org.json.JSONArray
import org.json.JSONObject

/** xAI and OpenAI share Responses wire format, with distinct tool capabilities. */
internal class ResponsesAdapter(private val provider: String) : AiProviderAdapter {
    override val path = "/responses"
    override fun headers(key: String) = mapOf("Authorization" to "Bearer $key")
    override fun payload(request: ChatInput): JSONObject = with(request) {
        // Initial system messages belong to the stored conversation. Continuations send
        // only the new user requirement: no duplicated source, result or task prompt.
        val input = JSONArray()
        if (previousResponseId == null) {
            input.put(JSONObject().put("role","system").put("content",
                instruction))
        }
        val content: Any = if (image == null) text else JSONArray()
            .put(JSONObject().put("type", "input_text").put("text", text.ifBlank { "请根据图片内容处理。" }))
            .put(JSONObject().put("type", "input_image").put("image_url", image))
        input.put(JSONObject().put("role","user").put("content",content))
        return JSONObject().put("model",model).put("stream",true).put("store",true)
            .apply {
                // GPT-4 models do not accept reasoning parameters.
                if (provider != "openai" || !model.startsWith("gpt-4")) put("reasoning", JSONObject().put("effort", reasoningEffort))
            }
            .put("input",input)
            .apply {
                val tools = JSONArray()
                if (custom && webSearch) tools.put(JSONObject().put("type", "web_search"))
                if (custom && xSearch && provider == "grok") tools.put(JSONObject().put("type", "x_search"))
                if (tools.length() > 0) put("tools", tools)
            }
            .apply { if (previousResponseId != null) put("previous_response_id",previousResponseId) }
    }
    override fun decoder(): ChatDecoder = object : ChatDecoder {
        override fun accept(json: JSONObject): ChatEvent {
            val type = json.optString("type")
            check(type !in listOf("error", "response.failed", "response.incomplete", "response.refusal.delta") &&
                (!json.has("error") || json.isNull("error"))) { "服务未返回完整文字结果，请重试" }
            when (type) {
                "response.output_text.delta" -> return ChatEvent(delta = json.opt("delta") as? String ?: error("流式响应格式错误，请检查接口"))
                "response.completed" -> {
                    val response = json.optJSONObject("response") ?: error("响应缺少完成信息")
                    check(response.optString("status") == "completed" && (!response.has("error") || response.isNull("error"))) { "服务未返回完整文字结果" }
                    val items = response.optJSONArray("output") ?: error("响应缺少正文")
                    val text = StringBuilder()
                    for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i)
                        if (item.optString("type") != "message") continue
                        check(item.optString("role") == "assistant") { "响应角色错误" }
                        val parts = item.optJSONArray("content") ?: continue
                        for (j in 0 until parts.length()) {
                            val part = parts.getJSONObject(j)
                            check(part.optString("type") != "refusal") { "服务未返回可用正文" }
                            if (part.optString("type") == "output_text") text.append(part.getString("text"))
                            check(text.length <= 20000) { "AI 响应过大，请缩短原文" }
                        }
                    }
                    return ChatEvent(id = response.optString("id"), completedText = text.toString())
                }
            }
            return ChatEvent()
        }
    }
    override fun models(json: JSONObject): ModelPage {
        val rows = json.getJSONArray("data")
        return ModelPage((0 until rows.length()).mapNotNull { rows.getJSONObject(it).optString("id").takeIf(String::isNotBlank) })
    }
}
