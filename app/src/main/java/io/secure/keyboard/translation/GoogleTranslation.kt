package io.secure.keyboard.translation

import io.secure.keyboard.data.ApiConfiguration
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

internal object GoogleTranslation {
    fun requestBody(text: String, source: String, target: String, model: String): JSONObject {
        require(text.isNotBlank() && text.length <= 5000) { "请输入 1–5000 字符的原文" }
        require(target.isNotBlank() && target != "auto") { "请选择目标语言" }
        return JSONObject().put("q", text).put("target", target).put("format", "text").put("model", model)
            .apply { if (source != "auto") put("source", source) }
    }
    class Call(private val connect: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection }) {
        private val canceled = AtomicBoolean(false)
        @Volatile private var activeConnection: HttpsURLConnection? = null
        fun cancel() {
            if (!canceled.compareAndSet(false, true)) return
            activeConnection?.let { connection ->
                Thread({ runCatching { connection.disconnect() } }, "translation-cancel").apply { isDaemon = true }.start()
            }
        }
        private fun checkActive() { check(!canceled.get()) { "翻译已取消" } }
        fun execute(config: ApiConfiguration, text: String, source: String, target: String): String {
            checkActive()
            val c = config.validatedGoogle()
            require(c.googleKey.isNotBlank()) { "请先在接口 API 配置中保存 Google 翻译密钥" }
            val payload = requestBody(text, source, target, c.googleModel).toString().toByteArray(Charsets.UTF_8)
            val connection = connect(URL(c.googleUrl))
            activeConnection = connection
            try {
                checkActive()
                connection.connectTimeout = 15000; connection.readTimeout = 20000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("X-Goog-Api-Key", c.googleKey)
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(payload.size)
                connection.outputStream.use { it.write(payload) }
                val code = connection.responseCode
                val errorHint = if (code != 200) runCatching {
                    connection.errorStream?.bufferedReader()?.use { reader ->
                        val chars = CharArray(8192); val count = reader.read(chars)
                        if (count > 0) String(chars, 0, count).lowercase() else ""
                    } ?: ""
                }.getOrDefault("") else ""
                check(code == 200) { when {
                    "api key not valid" in errorHint || "api_key_invalid" in errorHint -> "Google API Key 无效，请检查密钥是否完整以及是否来自 Google Cloud"
                    "billing" in errorHint -> "Google Cloud 项目尚未启用结算，请检查项目结算设置"
                    "service_disabled" in errorHint || "has not been used" in errorHint -> "Google Cloud 项目尚未启用 Cloud Translation API"
                    code == 400 -> "翻译请求无效，请检查语言和模型配置"
                    code == 401 || code == 403 -> "Google 翻译鉴权失败，请检查密钥、API 启用状态与配额"
                    code == 429 -> "请求过于频繁或配额不足，请稍后重试"
                    else -> "翻译失败（HTTP $code），请稍后重试"
                } }
                val body = connection.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream(); val bytes = ByteArray(8192)
                    while (true) { checkActive(); val n = input.read(bytes); if (n < 0) break
                        check(out.size()+n <= 1024*1024) { "翻译响应过大" }; out.write(bytes,0,n) }
                    out.toString("UTF-8")
                }
                val value = io.secure.keyboard.data.NetworkJson.objectValue(body).getJSONObject("data").getJSONArray("translations").getJSONObject(0).opt("translatedText") as? String
                checkActive()
                check(!value.isNullOrBlank() && value.length <= 20000) { "翻译未返回有效文字" }
                return value
            } finally { payload.fill(0); activeConnection = null; connection.disconnect() }
        }
    }
    fun translate(config: ApiConfiguration, text: String, source: String, target: String): String =
        Call().execute(config, text, source, target)
}
