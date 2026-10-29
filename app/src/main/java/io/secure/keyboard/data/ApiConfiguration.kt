package io.secure.keyboard.data

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.net.URI
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection

internal object AiProviders {
    val options = listOf("grok" to "Grok", "gemini" to "Gemini", "openai" to "OpenAI")
    val customOptions = listOf("default" to "跟随默认") + options
    fun valid(value: String) = options.any { it.first == value }
    fun label(value: String) = options.firstOrNull { it.first == value }?.second ?: "未知提供商"
}

internal data class ApiConfiguration(
    val googleUrl: String = "https://translation.googleapis.com/language/translate/v2",
    val googleKey: String = "",
    val googleModel: String = "nmt",
    val grokUrl: String = "https://api.x.ai/v1",
    val grokKey: String = "",
    val grokModel: String = "",
    val voiceUrl: String = "https://api.x.ai/v1/stt",
    val voiceKey: String = "",
    val voiceModel: String = "grok-voice-transcribe-2.0",
    val openaiUrl: String = "https://api.openai.com/v1",
    val openaiKey: String = "",
    val openaiModel: String = "",
    val openaiVoiceUrl: String = "https://api.openai.com/v1/audio/transcriptions",
    val openaiVoiceKey: String = "",
    val openaiVoiceModel: String = "gpt-transcribe",
    val aiProvider: String = "grok",
    val voiceProvider: String = "grok",
    val geminiUrl: String = "https://generativelanguage.googleapis.com/v1beta",
    val geminiKey: String = "",
    val geminiModel: String = "gemini-3.8-flash",
    val geminiVoiceUrl: String = "https://generativelanguage.googleapis.com/v1beta/interactions",
    val geminiVoiceKey: String = "",
    val geminiVoiceModel: String = "gemini-3.8-flash",
) {
    private fun endpoint(value: String): String {
        val uri = runCatching { URI(value.trim()) }.getOrNull()
        require(uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.query == null && uri.fragment == null) { "接口地址需为 HTTPS，不能包含密钥、查询参数或片段" }
        return value.trim().trimEnd('/')
    }
    private fun key(value: String): String {
        require(!value.contains('\n') && !value.contains('\r')) { "密钥不能包含换行" }
        return value.trim()
    }
    fun validatedGoogle() = copy(googleUrl = endpoint(googleUrl), googleKey = key(googleKey), googleModel = googleModel.trim().ifEmpty { "nmt" })
    fun validatedGrok() = copy(grokUrl = endpoint(grokUrl), grokKey = key(grokKey), grokModel = grokModel.trim())
    fun validatedVoice() = copy(voiceUrl = endpoint(voiceUrl), voiceKey = key(voiceKey), voiceModel = voiceModel.trim().ifEmpty { "grok-voice-transcribe-2.0" })
    val chatUrl get() = when (aiProvider) { "gemini" -> geminiUrl; "openai" -> openaiUrl; else -> grokUrl }
    val chatKey get() = when (aiProvider) { "gemini" -> geminiKey; "openai" -> openaiKey; else -> grokKey }
    val chatModel get() = when (aiProvider) { "gemini" -> geminiModel; "openai" -> openaiModel; else -> grokModel }
    fun withChatModel(model: String) = when (aiProvider) { "gemini" -> copy(geminiModel = model); "openai" -> copy(openaiModel = model); else -> copy(grokModel = model) }
    fun validatedChat(): ApiConfiguration {
        require(AiProviders.valid(aiProvider)) { "AI 提供商无效" }
        if (aiProvider == "gemini") return copy(geminiUrl = endpoint(geminiUrl), geminiKey = key(geminiKey), geminiModel = geminiModel.trim().removePrefix("models/"))
        return if (aiProvider == "openai") copy(openaiUrl = endpoint(openaiUrl), openaiKey = key(openaiKey), openaiModel = openaiModel.trim()) else validatedGrok()
    }
    fun resolvedVoice(): ApiConfiguration {
        require(AiProviders.valid(voiceProvider)) { "语音提供商无效" }
        if (voiceProvider == "gemini") return copy(voiceUrl = endpoint(geminiVoiceUrl), voiceKey = key(geminiVoiceKey), voiceModel = geminiVoiceModel.trim().removePrefix("models/"))
        return if (voiceProvider == "openai") copy(voiceUrl = endpoint(openaiVoiceUrl), voiceKey = key(openaiVoiceKey),
            voiceModel = openaiVoiceModel.trim().ifEmpty { "gpt-transcribe" }) else validatedVoice()
    }
    fun validated(): ApiConfiguration {
        require(AiProviders.valid(aiProvider) && AiProviders.valid(voiceProvider))
        return validatedGoogle().validatedGrok().validatedVoice().copy(openaiUrl = endpoint(openaiUrl), openaiKey = key(openaiKey),
            openaiModel = openaiModel.trim(), openaiVoiceUrl = endpoint(openaiVoiceUrl), openaiVoiceKey = key(openaiVoiceKey),
            openaiVoiceModel = openaiVoiceModel.trim().ifEmpty { "gpt-transcribe" },
            geminiUrl = endpoint(geminiUrl), geminiKey = key(geminiKey), geminiModel = geminiModel.trim().removePrefix("models/"),
            geminiVoiceUrl = endpoint(geminiVoiceUrl), geminiVoiceKey = key(geminiVoiceKey), geminiVoiceModel = geminiVoiceModel.trim().removePrefix("models/"))
    }
    // Provider selection changes the request scope, not the stored credentials or history.
    internal fun conversationIdentity() = listOf(grokUrl.trim().trimEnd('/'), grokKey.trim(), grokModel.trim(), openaiUrl.trim().trimEnd('/'), openaiKey.trim(), openaiModel.trim(), geminiUrl.trim().trimEnd('/'), geminiKey.trim(), geminiModel.trim())

}

/** Local configuration uses Android Keystore; explicit exports use password-encrypted backups. */
internal class ApiConfigurationStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = context.getSharedPreferences("api_configuration", Context.MODE_PRIVATE)
    private fun key(): SecretKey = StorageKeys.get("skey_api_config")
    fun load(): ApiConfiguration {
        val stored = prefs.getString("encrypted", null) ?: return ApiConfiguration()
        val parts = stored.split(':')
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        val plain = cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP))
        val json = try { JSONObject(String(plain, Charsets.UTF_8)) } finally { plain.fill(0) }
        return ApiConfigurationCodec.fromJson(json)
    }
    fun save(config: ApiConfiguration) = saveDraft(config.validated())

    /** Preserve partially typed fields; requests still validate before network access. */
    fun saveDraft(c: ApiConfiguration) {
        val old = runCatching { load() }.getOrNull()
        if(old==null || old.conversationIdentity()!=c.conversationIdentity()) AiConversationStore(app).clear()
        val json = ApiConfigurationCodec.toJson(c)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val plain = json.toString().toByteArray(Charsets.UTF_8)
        val encoded = try {
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(cipher.doFinal(plain), Base64.NO_WRAP)
        } finally { plain.fill(0) }
        check(prefs.edit().putString("encrypted", encoded).commit()) { "配置保存失败" }
    }
    fun clear() { AiConversationStore(app).clear(); check(prefs.edit().clear().commit()) { "清除失败" } }
}

internal object AiModelCatalog {
    fun fetch(config: ApiConfiguration): List<String> {
        val c = config.validatedChat()
        require(c.chatKey.isNotBlank()) { "请先填写 ${AiProviders.label(c.aiProvider)} API Key" }
        val adapter = io.secure.keyboard.ai.AiProviderAdapters.get(c.aiProvider)
        val ids = mutableSetOf<String>()
        var token = ""
        repeat(10) {
            val suffix = if (token.isEmpty()) "" else "?pageToken=" + java.net.URLEncoder.encode(token, "UTF-8")
            val connection = URL(c.chatUrl + "/models" + suffix).openConnection() as HttpsURLConnection
            val page = try {
                connection.connectTimeout = 15000; connection.readTimeout = 15000
                connection.instanceFollowRedirects = false
                adapter.headers(c.chatKey).forEach { (name, value) -> connection.setRequestProperty(name, value) }
                connection.setRequestProperty("Accept", "application/json")
                val status = connection.responseCode
                check(status == 200) { "获取模型失败（HTTP $status），请检查地址、密钥与模型访问权限" }
                val body = connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        check(output.size() + n <= 1024 * 1024) { "模型列表响应过大" }
                        output.write(buffer, 0, n)
                    }
                    output.toString("UTF-8")
                }
                adapter.models(io.secure.keyboard.data.NetworkJson.objectValue(body))
            } finally { connection.disconnect() }
            ids.addAll(page.ids)
            check(ids.size <= 2000 && page.nextToken.length <= 4096) { "模型列表响应过大" }
            if (page.nextToken.isEmpty()) return ids.sorted()
            check(page.nextToken != token) { "模型列表分页无效" }
            token = page.nextToken
        }
        error("模型列表分页过多，请手动填写模型 ID")
    }
}
