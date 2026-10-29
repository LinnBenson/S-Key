package io.secure.keyboard.data

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream

/** Append-only backup fields retain the 9-field and 17-field legacy layouts. */
internal object ApiConfigurationCodec {
    fun fromJson(json: JSONObject): ApiConfiguration =
        ApiConfiguration(json.getString("googleUrl"), json.getString("googleKey"), json.getString("googleModel"),
            json.getString("grokUrl"), json.getString("grokKey"), json.getString("grokModel"),
            json.optString("voiceUrl", "https://api.x.ai/v1/stt"), json.optString("voiceKey", ""),
            json.optString("voiceModel", "grok-voice-transcribe-2.0"),
            json.optString("openaiUrl", "https://api.openai.com/v1"), json.optString("openaiKey", ""), json.optString("openaiModel", ""),
            json.optString("openaiVoiceUrl", "https://api.openai.com/v1/audio/transcriptions"), json.optString("openaiVoiceKey", ""),
            json.optString("openaiVoiceModel", "gpt-transcribe"), json.optString("aiProvider", "grok"), json.optString("voiceProvider", "grok"),
            json.optString("geminiUrl", "https://generativelanguage.googleapis.com/v1beta"), json.optString("geminiKey", ""), json.optString("geminiModel", "gemini-3.8-flash"),
            json.optString("geminiVoiceUrl", "https://generativelanguage.googleapis.com/v1beta/interactions"), json.optString("geminiVoiceKey", ""), json.optString("geminiVoiceModel", "gemini-3.8-flash"))
    fun toJson(c: ApiConfiguration): JSONObject =
        JSONObject().put("googleUrl", c.googleUrl).put("googleKey", c.googleKey).put("googleModel", c.googleModel)
            .put("grokUrl", c.grokUrl).put("grokKey", c.grokKey).put("grokModel", c.grokModel)
            .put("voiceUrl", c.voiceUrl).put("voiceKey", c.voiceKey).put("voiceModel", c.voiceModel)
            .put("openaiUrl", c.openaiUrl).put("openaiKey", c.openaiKey).put("openaiModel", c.openaiModel)
            .put("openaiVoiceUrl", c.openaiVoiceUrl).put("openaiVoiceKey", c.openaiVoiceKey).put("openaiVoiceModel", c.openaiVoiceModel)
            .put("aiProvider", c.aiProvider).put("voiceProvider", c.voiceProvider)
            .put("geminiUrl", c.geminiUrl).put("geminiKey", c.geminiKey).put("geminiModel", c.geminiModel)
            .put("geminiVoiceUrl", c.geminiVoiceUrl).put("geminiVoiceKey", c.geminiVoiceKey).put("geminiVoiceModel", c.geminiVoiceModel)
    fun write(data: DataOutputStream, api: ApiConfiguration) {
        val fields = listOf(api.googleUrl, api.googleKey, api.googleModel, api.grokUrl, api.grokKey,
            api.grokModel, api.voiceUrl, api.voiceKey, api.voiceModel, api.openaiUrl, api.openaiKey, api.openaiModel, api.openaiVoiceUrl, api.openaiVoiceKey, api.openaiVoiceModel, api.aiProvider, api.voiceProvider, api.geminiUrl, api.geminiKey, api.geminiModel, api.geminiVoiceUrl, api.geminiVoiceKey, api.geminiVoiceModel)
        require(fields.all { it.length <= 16384 }) { "AI 配置内容过长" }
        fields.forEach { data.writeUTF(it) }
    }
    fun read(data: DataInputStream): ApiConfiguration {
        fun field(): String = data.readUTF().also { require(it.length <= 16384) }
        var api = ApiConfiguration(field(), field(), field(), field(), field(), field(), field(), field(), field())
        if (data.available() > 0) api = api.copy(openaiUrl = field(), openaiKey = field(), openaiModel = field(),
            openaiVoiceUrl = field(), openaiVoiceKey = field(), openaiVoiceModel = field(), aiProvider = field(), voiceProvider = field())
        if (data.available() > 0) api = api.copy(geminiUrl = field(), geminiKey = field(), geminiModel = field(),
            geminiVoiceUrl = field(), geminiVoiceKey = field(), geminiVoiceModel = field())
        require(AiProviders.valid(api.aiProvider) && AiProviders.valid(api.voiceProvider))
        require(data.read() == -1)
        return api
    }
}
