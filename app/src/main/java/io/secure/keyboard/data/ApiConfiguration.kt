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
    val options = listOf("grok" to "Grok", "openai" to "OpenAI")
    val customOptions = listOf("default" to "跟随默认") + options
    fun valid(value: String) = value == "grok" || value == "openai"
    fun label(value: String) = if (value == "openai") "OpenAI" else "Grok"
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
    val chatUrl get() = if (aiProvider == "openai") openaiUrl else grokUrl
    val chatKey get() = if (aiProvider == "openai") openaiKey else grokKey
    val chatModel get() = if (aiProvider == "openai") openaiModel else grokModel
    fun withChatModel(model: String) = if (aiProvider == "openai") copy(openaiModel = model) else copy(grokModel = model)
    fun validatedChat(): ApiConfiguration {
        require(AiProviders.valid(aiProvider)) { "AI 提供商无效" }
        return if (aiProvider == "openai") copy(openaiUrl = endpoint(openaiUrl), openaiKey = key(openaiKey), openaiModel = openaiModel.trim()) else validatedGrok()
    }
    fun resolvedVoice(): ApiConfiguration {
        require(AiProviders.valid(voiceProvider)) { "语音提供商无效" }
        return if (voiceProvider == "openai") copy(voiceUrl = endpoint(openaiVoiceUrl), voiceKey = key(openaiVoiceKey),
            voiceModel = openaiVoiceModel.trim().ifEmpty { "gpt-transcribe" }) else validatedVoice()
    }
    fun validated(): ApiConfiguration {
        require(AiProviders.valid(aiProvider) && AiProviders.valid(voiceProvider))
        return validatedGoogle().validatedGrok().validatedVoice().copy(openaiUrl = endpoint(openaiUrl), openaiKey = key(openaiKey),
            openaiModel = openaiModel.trim(), openaiVoiceUrl = endpoint(openaiVoiceUrl), openaiVoiceKey = key(openaiVoiceKey),
            openaiVoiceModel = openaiVoiceModel.trim().ifEmpty { "gpt-transcribe" })
    }
    // Provider selection changes the request scope, not the stored credentials or history.
    internal fun conversationIdentity() = listOf(grokUrl.trim().trimEnd('/'), grokKey.trim(), grokModel.trim(), openaiUrl.trim().trimEnd('/'), openaiKey.trim(), openaiModel.trim())

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
        return ApiConfiguration(json.getString("googleUrl"), json.getString("googleKey"), json.getString("googleModel"),
            json.getString("grokUrl"), json.getString("grokKey"), json.getString("grokModel"),
            json.optString("voiceUrl", "https://api.x.ai/v1/stt"), json.optString("voiceKey", ""),
            json.optString("voiceModel", "grok-voice-transcribe-2.0"),
            json.optString("openaiUrl", "https://api.openai.com/v1"), json.optString("openaiKey", ""), json.optString("openaiModel", ""),
            json.optString("openaiVoiceUrl", "https://api.openai.com/v1/audio/transcriptions"), json.optString("openaiVoiceKey", ""),
            json.optString("openaiVoiceModel", "gpt-transcribe"), json.optString("aiProvider", "grok"), json.optString("voiceProvider", "grok"))
    }
    fun save(config: ApiConfiguration) = saveDraft(config.validated())

    /** Preserve partially typed fields; requests still validate before network access. */
    fun saveDraft(c: ApiConfiguration) {
        val old = runCatching { load() }.getOrNull()
        if(old==null || old.conversationIdentity()!=c.conversationIdentity()) AiConversationStore(app).clear()
        val json = JSONObject().put("googleUrl", c.googleUrl).put("googleKey", c.googleKey).put("googleModel", c.googleModel)
            .put("grokUrl", c.grokUrl).put("grokKey", c.grokKey).put("grokModel", c.grokModel)
            .put("voiceUrl", c.voiceUrl).put("voiceKey", c.voiceKey).put("voiceModel", c.voiceModel)
            .put("openaiUrl", c.openaiUrl).put("openaiKey", c.openaiKey).put("openaiModel", c.openaiModel)
            .put("openaiVoiceUrl", c.openaiVoiceUrl).put("openaiVoiceKey", c.openaiVoiceKey).put("openaiVoiceModel", c.openaiVoiceModel)
            .put("aiProvider", c.aiProvider).put("voiceProvider", c.voiceProvider)
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
        val connection = URL(c.chatUrl + "/models").openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 15000; connection.readTimeout = 15000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer ${c.chatKey}")
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
            val data = io.secure.keyboard.data.NetworkJson.objectValue(body).getJSONArray("data")
            return (0 until data.length()).mapNotNull { data.getJSONObject(it).optString("id").takeIf(String::isNotBlank) }.distinct().sorted()
        } finally { connection.disconnect() }
    }
}
