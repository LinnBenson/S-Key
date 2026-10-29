package io.secure.keyboard.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.secure.keyboard.data.ApiConfiguration
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Bounded memory-only capture. Explicit stop uploads; cancel invalidates all callbacks. */
internal class AiVoiceSession(context: Context, private val config: ApiConfiguration,
    private val listener: OfflineVoiceSession.Listener,
    private val translateToEnglish: Boolean = false,
    private val newCall: (okhttp3.Request) -> okhttp3.Call = VoiceHttpRequest.client::newCall) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val generation = AtomicLong()
    private val captureEnvironment = VoiceCaptureEnvironment(context)
    private val gate = MicrophoneLifecycleGate()
    @Volatile private var recording = false
    @Volatile private var released = false
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var request: VoiceHttpRequest? = null
    private val levels = LatestVoiceLevel({ main.postDelayed(it, 50L) }, { main.removeCallbacks(it) }) { id, level ->
        if (current(id) && recording) listener.onLevel(level)
    }
    private var busy = false
    private var timer: Runnable? = null
    fun begin() {
        if(released || busy) return
        if (!captureSlots.tryAcquire()) {
            listener.onError("先前的语音请求仍在结束，请稍后重试")
            return
        }
        busy = true; recording = true
        val id = generation.incrementAndGet()
        listener.onState(OfflineVoiceSession.State.LOADING)
        try {
            worker.execute { runCapture(id) { capture(id) } }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            captureSlots.release(); busy=false; recording=false
            if (current(id)) listener.onError("语音任务未启动，请重试")
        }
    }
    private fun runCapture(id: Long, capture: () -> String?) {
        val outcome = try { runCatching { capture() } } finally { captureSlots.release() }
        // Permit and recorder cleanup precede callbacks, so retry cannot race old ownership.
        main.post {
            busy = false
            if (!current(id)) return@post
            timer?.let(main::removeCallbacks); timer = null
            outcome.fold(onSuccess = { text ->
                if (text != null) {
                    listener.onPreview(text, true)
                    if (current(id)) listener.onSegment(1, text)
                }
            }, onFailure = { error ->
                val message = if (error is SecurityException) "麦克风权限不可用，请重新允许"
                else if (error is IllegalStateException || error is IllegalArgumentException)
                    error.message ?: "识别失败" else "语音连接失败或超时，请重试"
                listener.onError(message)
            })
            if (current(id)) listener.onState(OfflineVoiceSession.State.IDLE)
        }
    }

    fun stop() {
        levels.clear()
        // Hardware ownership stays on the capture worker, including a start still in flight.
        recording = false
        timer?.let(main::removeCallbacks); timer=null
    }
    fun cancel() {
        generation.incrementAndGet()
        recording = false
        request?.cancel()
        stop()
    }
    fun release() { if(released) return; cancel(); released=true; levels.close(); worker.shutdown() }
    private fun current(id:Long) = !released && generation.get()==id
    private fun emit(id:Long, action:()->Unit) { main.post { if(current(id)) action() } }
    private fun capture(id:Long): String? {
        if (!current(id)) return null
        val pcm = ByteArray(16000*2*60)
        var count=0
        var mic:AudioRecord?=null
        try {
            check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) { "请先允许麦克风权限" }
            val input = MicrophoneCapture.start(context, captureEnvironment, gate, { current(id) && recording }) { recorder = it }
                ?: return null
            mic = input
            val status = MicrophoneStatus(input)
            val signal = CaptureSignalHealth()
            emit(id) {
                listener.onState(OfflineVoiceSession.State.LISTENING)
                if(recording) timer=Runnable { if(current(id)) stop() }.also { main.postDelayed(it,60000) }
            }
            val deadline=SystemClock.elapsedRealtime()+60000
            val progress = AudioReadProgress(SystemClock.elapsedRealtime())
            while(current(id) && recording && count<pcm.size && SystemClock.elapsedRealtime()<deadline) {
                status.checkAvailable()
                captureEnvironment.checkAvailable()
                val n=input.read(pcm,count,minOf(3200,pcm.size-count),AudioRecord.READ_NON_BLOCKING)
                if(n<0) { if(!recording || !current(id)) break; error("录音中断，请重试") }
                if (!current(id) || (n == 0 && !recording)) break
                check(progress.accept(n, SystemClock.elapsedRealtime())) { "麦克风未返回录音数据，请关闭语音后重试" }
                if (n == 0) { Thread.sleep(10); continue }
                if(n>0) {
                    check(signal.accept(pcm, count, n)) { "麦克风录音数据异常，请重试" }
                    val level=VoiceLevel.measure(pcm,count,n)
                    levels.offer(id, level)
                    count+=n
                }
            }
            if (current(id)) status.checkAvailable()
            gate.stop { recording=false; runCatching { input.stop() }; captureEnvironment.close(); recorder=null }
            input.release(); mic=null
            if(!current(id)) return null
            check(signal.hasVariation) { "未采集到有效声音，请检查麦克风后重试" }
            check(count>=3200) { "录音太短，请重新说话" }
            emit(id) { listener.onState(OfflineVoiceSession.State.PROCESSING) }
            return prepareOutput(id,transcribe(id,pcm,count))
        } finally {
            levels.clear()
            pcm.fill(0)
            gate.stop { recording=false; recorder=null; runCatching { mic?.stop() }; runCatching { captureEnvironment.close() }; runCatching { mic?.release() } }

        }
    }
    private fun prepareOutput(id: Long, transcript: String): String {
        check(current(id)) { "已取消" }
        val normalized = VoiceChineseNormalizer.simplify(context, transcript)
        check(current(id)) { "已取消" }
        if (!translateToEnglish) return normalized
        val c = config.validatedGoogle()
        require(c.googleKey.isNotBlank()) { "请先配置 Google 翻译密钥" }
        val payload = io.secure.keyboard.translation.GoogleTranslation.requestBody(normalized, "auto", "en", c.googleModel)
            .toString().toByteArray(Charsets.UTF_8)
        try {
            val body = send(id, c.googleUrl,
                mapOf("X-Goog-Api-Key" to c.googleKey), "application/json; charset=utf-8", payload.size.toLong(), { it.write(payload) }) { status ->
                when (status) {
                    401, 403 -> "Google 翻译鉴权失败，请检查密钥与权限"
                    429 -> "翻译请求过于频繁或配额不足"
                    else -> "翻译失败（HTTP $status），请稍后重试"
                }
            }
            val value = io.secure.keyboard.data.NetworkJson.objectValue(body).getJSONObject("data").getJSONArray("translations")
                .getJSONObject(0).opt("translatedText") as? String
            check(!value.isNullOrBlank() && value.length <= 20000) { "翻译未返回有效文字" }
            return value
        } finally { payload.fill(0) }
    }

    private fun send(id: Long, url: String, headers: Map<String, String>, contentType: String,
                     size: Long, write: (java.io.OutputStream) -> Unit, error: (Int) -> String): String {
        check(current(id)) { "已取消" }
        val call = VoiceHttpRequest(newCall)
        request = call
        try {
            // Handles cancellation between the first check and publishing the request.
            if (!current(id)) call.cancel()
            val result = call.execute(url, headers, contentType, size, write, error)
            check(current(id)) { "已取消" }
            return result
        } finally { call.cancel(); request = null }
    }

    private fun transcribe(id:Long, pcm:ByteArray, count:Int):String {
        check(current(id)) { "已取消" }
        val c=config.resolvedVoice()
        require(c.voiceKey.isNotBlank()) { "请配置语音 API 密钥" }
        val adapter = VoiceProviderAdapters.get(c.voiceProvider)
        val body = adapter.upload(c.voiceModel, pcm, count) { check(current(id)) { "已取消" } }.use { upload ->
            send(id, c.voiceUrl, adapter.headers(c.voiceKey), upload.contentType, upload.size, upload.write) { status ->
                when(status) {
                    401,403 -> "语音 API 鉴权失败，请检查密钥与权限"
                    400,404 -> "语音端点或模型不可用，请检查语音 API 配置"
                    429 -> "语音请求过于频繁或额度不足"
                    else -> "语音识别失败（HTTP $status）"
                }
            }
        }
        return adapter.transcript(body).also {
            check(it.isNotEmpty() && it.length <= 20000) { "未识别到有效文字，请重试" }
        }
    }

    companion object {
        // A canceled socket may outlive its UI. Bound all sessions together, not each instance.
        private val captureSlots = java.util.concurrent.Semaphore(2)
        internal fun wavHeader(bytes:Int):ByteArray {
            require(bytes in 0..1920000 && bytes%2==0)
            return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray());putInt(36+bytes);put("WAVEfmt ".toByteArray());putInt(16)
                putShort(1);putShort(1);putInt(16000);putInt(32000);putShort(2);putShort(16)
                put("data".toByteArray());putInt(bytes)
            }.array()
        }
    }
}
