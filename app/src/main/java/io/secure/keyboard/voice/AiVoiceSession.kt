package io.secure.keyboard.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.secure.keyboard.data.ApiConfiguration
import org.json.JSONObject
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection

/** Bounded memory-only capture. Explicit stop uploads; cancel invalidates all callbacks. */
internal class AiVoiceSession(context: Context, private val config: ApiConfiguration,
    private val listener: OfflineVoiceSession.Listener,
    private val translateToEnglish: Boolean = false,
    private val connect: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection }) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val generation = AtomicLong()
    private val gate = MicrophoneLifecycleGate()
    @Volatile private var recording = false
    @Volatile private var released = false
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var connection: HttpsURLConnection? = null
    @Volatile private var translationCall: io.secure.keyboard.translation.GoogleTranslation.Call? = null
    private var busy = false
    private var timer: Runnable? = null
    fun begin() {
        if(released || busy) return
        busy = true; recording = true
        val id = generation.incrementAndGet()
        listener.onState(OfflineVoiceSession.State.LOADING)
        worker.execute { capture(id) }
    }
    fun stop() {
        gate.stop { recording = false; runCatching { recorder?.stop() } }
        timer?.let(main::removeCallbacks); timer=null
    }
    fun cancel() {
        generation.incrementAndGet(); stop(); translationCall?.cancel()
        connection?.let { c -> Thread({ runCatching { c.disconnect() } }, "voice-disconnect").apply { isDaemon=true }.start() }
    }
    fun release() { if(released) return; cancel(); released=true; worker.shutdown() }
    private fun current(id:Long) = !released && generation.get()==id
    private fun emit(id:Long, action:()->Unit) { main.post { if(current(id)) action() } }
    private fun capture(id:Long) {
        val pcm = ByteArray(16000*2*60)
        var count=0
        var mic:AudioRecord?=null
        try {
            check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) { "请先允许麦克风权限" }
            val minimum=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
            check(minimum>0) { "设备不支持此录音格式" }
            mic=AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,maxOf(minimum*2,6400))
            val input=mic; recorder=input
            check(input.state==AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
            check(gate.startIfAllowed({current(id) && recording}) { input.startRecording() }) {
                "录音尚未开始，请按住空格等待聆听提示后再说话"
            }
            check(input.recordingState==AudioRecord.RECORDSTATE_RECORDING) { "无法启动麦克风" }
            emit(id) {
                listener.onState(OfflineVoiceSession.State.LISTENING)
                if(recording) timer=Runnable { if(current(id)) stop() }.also { main.postDelayed(it,60000) }
            }
            val deadline=SystemClock.elapsedRealtime()+60000
            while(current(id) && recording && count<pcm.size && SystemClock.elapsedRealtime()<deadline) {
                val n=input.read(pcm,count,minOf(3200,pcm.size-count))
                if(n<0) { if(!recording || !current(id)) break; error("录音中断，请重试") }
                if(n>0) {
                    val level=VoiceLevel.measure(pcm,count,n)
                    emit(id) { if(recording) listener.onLevel(level) }
                    count+=n
                }
            }
            gate.stop { recording=false; runCatching { input.stop() }; recorder=null }
            input.release(); mic=null
            if(!current(id)) return
            check(count>=3200) { "录音太短，请重新说话" }
            emit(id) { listener.onState(OfflineVoiceSession.State.PROCESSING) }
            val result=prepareOutput(id,transcribe(id,pcm,count))
            emit(id) { listener.onPreview(result,true); listener.onSegment(1,result) }
        } catch(e:Exception) {
            val message=if(e is IllegalStateException || e is IllegalArgumentException) e.message ?: "识别失败" else "语音连接失败或超时，请重试"
            emit(id) { listener.onError(message) }
        } finally {
            pcm.fill(0)
            gate.stop { recording=false; recorder=null; runCatching { mic?.stop() }; runCatching { mic?.release() } }
            main.post { busy=false; if(current(id)) { timer?.let(main::removeCallbacks);timer=null;listener.onState(OfflineVoiceSession.State.IDLE) } }
        }
    }
    private fun prepareOutput(id: Long, transcript: String): String {
        check(current(id)) { "已取消" }
        if (!translateToEnglish) return transcript
        val call = io.secure.keyboard.translation.GoogleTranslation.Call(connect)
        translationCall = call
        try {
            check(current(id)) { "已取消" }
            val result = call.execute(config, transcript, "auto", "en")
            check(current(id)) { "已取消" }
            return result
        } finally { translationCall = null }
    }

    private fun transcribe(id:Long, pcm:ByteArray, count:Int):String {
        val c=config.validated()
        require(c.voiceKey.isNotBlank()) { "请配置语音 API 密钥" }
        val boundary="skey-voice-${java.util.UUID.randomUUID()}"
        val model=c.voiceModel
        require(!model.contains('\r') && !model.contains('\n')) { "语音模型格式无效" }
        val prefix=("--$boundary\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n$model\r\n"+
            "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n").toByteArray()
        val suffix="\r\n--$boundary--\r\n".toByteArray()
        val conn=connect(URL(c.voiceUrl))
        connection=conn
        try {
            check(current(id)) { "已取消" }
            conn.instanceFollowRedirects=false;conn.connectTimeout=15000;conn.readTimeout=60000
            conn.requestMethod="POST";conn.doOutput=true
            conn.setRequestProperty("Authorization","Bearer ${c.voiceKey}")
            conn.setRequestProperty("Content-Type","multipart/form-data; boundary=$boundary")
            conn.setFixedLengthStreamingMode(prefix.size+44+count+suffix.size)
            conn.outputStream.use { out ->
                out.write(prefix);out.write(wavHeader(count))
                var offset=0
                while(offset<count) { check(current(id)) { "已取消" };val n=minOf(8192,count-offset);out.write(pcm,offset,n);offset+=n }
                out.write(suffix)
            }
            val status=conn.responseCode
            check(status==200) { when(status) {
                401,403 -> "语音 API 鉴权失败，请检查密钥与权限"
                400,404 -> "语音端点或模型不可用，请检查语音 API 配置"
                429 -> "语音请求过于频繁或额度不足"
                else -> "语音识别失败（HTTP $status）"
            } }
            val bytes=conn.inputStream.use { input ->
                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true) { check(current(id)) { "已取消" };val n=input.read(buffer);if(n<0)break
                    check(out.size()+n<=1024*1024) { "语音响应过大" };out.write(buffer,0,n) }
                out.toByteArray()
            }
            return try {
                (JSONObject(String(bytes,Charsets.UTF_8)).opt("text") as? String)?.trim().orEmpty().also {
                    check(it.isNotEmpty() && it.length<=20000) { "未识别到有效文字，请重试" }
                }
            } finally { bytes.fill(0) }
        } finally { connection=null;conn.disconnect() }
    }
    companion object {
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
