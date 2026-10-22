package io.secure.keyboard.voice

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One cancelable upload/response, including a deadline across connect, write and read. */
internal class VoiceHttpRequest(private val newCall: (Request) -> Call = client::newCall) {
    private val canceled = AtomicBoolean()
    @Volatile private var active: Call? = null

    fun cancel() {
        canceled.set(true)
        active?.cancel()
    }

    fun execute(url: String, headers: Map<String, String>, contentType: String, size: Long,
                write: (OutputStream) -> Unit, errorMessage: (Int) -> String): String {
        checkActive()
        val body = object : RequestBody() {
            override fun contentType() = contentType.toMediaType()
            override fun contentLength() = size
            // Never replay a recording automatically after a network failure or HTTP retry.
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) { checkActive(); write(sink.outputStream()) }
        }
        val request = Request.Builder().url(url).post(body).apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.build()
        val call = newCall(request)
        active = call
        try {
            if (canceled.get()) call.cancel()
            checkActive()
            return call.execute().use { response ->
                checkActive()
                check(response.code == 200) { errorMessage(response.code) }
                val input = checkNotNull(response.body).byteStream()
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                try {
                    while (true) {
                        checkActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        check(output.size() + n <= 1024 * 1024) { "语音响应过大" }
                        output.write(buffer, 0, n)
                    }
                    checkActive()
                    output.toString("UTF-8")
                } finally { buffer.fill(0) }
            }
        } finally { call.cancel(); active = null }
    }

    private fun checkActive() { if (canceled.get()) throw IOException("已取消") }

    companion object {
        // Shared bounded resources; no disk cache, cookie jar, logging or redirect forwarding.
        internal val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }
}
