package io.secure.keyboard.ai

import org.json.JSONObject

internal data class ChatInput(val model: String, val text: String, val instruction: String,
    val custom: Boolean, val previousResponseId: String?, val reasoningEffort: String,
    val image: String?, val webSearch: Boolean, val xSearch: Boolean)
internal data class ChatEvent(val delta: String? = null, val id: String? = null, val completedText: String? = null)
internal fun interface ChatDecoder { fun accept(json: JSONObject): ChatEvent }
internal data class ModelPage(val ids: List<String>, val nextToken: String = "")

/** Protocol adapters contain wire formats; transport owns deadlines, cancellation and bounds. */
internal interface AiProviderAdapter {
    val path: String
    fun headers(key: String): Map<String, String>
    fun payload(request: ChatInput): JSONObject
    fun decoder(): ChatDecoder
    fun models(json: JSONObject): ModelPage
}
internal object AiProviderAdapters {
    private val adapters = mapOf("grok" to ResponsesAdapter("grok"), "openai" to ResponsesAdapter("openai"), "gemini" to GeminiAdapter)
    fun get(provider: String): AiProviderAdapter = adapters[provider] ?: error("AI 提供商无效")
}
