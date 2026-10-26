package io.secure.keyboard.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.net.URI
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection

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
    fun validated() = validatedGoogle().validatedGrok().validatedVoice()
    internal fun conversationIdentity() = listOf(grokUrl.trim().trimEnd('/'), grokKey.trim(), grokModel.trim())

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
        val json = JSONObject(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8))
        return ApiConfiguration(json.getString("googleUrl"), json.getString("googleKey"), json.getString("googleModel"),
            json.getString("grokUrl"), json.getString("grokKey"), json.getString("grokModel"),
            json.optString("voiceUrl", "https://api.x.ai/v1/stt"), json.optString("voiceKey", ""),
            json.optString("voiceModel", "grok-voice-transcribe-2.0"))
    }
    fun save(config: ApiConfiguration) = saveDraft(config.validated())

    /** Preserve partially typed fields; requests still validate before network access. */
    fun saveDraft(c: ApiConfiguration) {
        val old = runCatching { load() }.getOrNull()
        if(old==null || old.conversationIdentity()!=c.conversationIdentity()) AiConversationStore(app).clear()
        val json = JSONObject().put("googleUrl", c.googleUrl).put("googleKey", c.googleKey).put("googleModel", c.googleModel)
            .put("grokUrl", c.grokUrl).put("grokKey", c.grokKey).put("grokModel", c.grokModel)
            .put("voiceUrl", c.voiceUrl).put("voiceKey", c.voiceKey).put("voiceModel", c.voiceModel)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(prefs.edit().putString("encrypted", encoded).commit()) { "配置保存失败" }
    }
    fun clear() { AiConversationStore(app).clear(); check(prefs.edit().clear().commit()) { "清除失败" } }
}

internal object GrokModelCatalog {
    fun fetch(config: ApiConfiguration): List<String> {
        val c = config.validatedGrok()
        require(c.grokKey.isNotBlank()) { "请先填写 Grok API Key" }
        val connection = URL(c.grokUrl + "/models").openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 15000; connection.readTimeout = 15000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer ${c.grokKey}")
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
