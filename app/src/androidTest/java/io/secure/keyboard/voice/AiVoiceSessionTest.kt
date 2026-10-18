package io.secure.keyboard.voice

import android.test.AndroidTestCase
import io.secure.keyboard.data.ApiConfiguration
import io.secure.keyboard.data.ApiConfigurationStore
import io.secure.keyboard.data.SettingsStore
import java.net.URL
import java.io.ByteArrayOutputStream
import javax.net.ssl.HttpsURLConnection
import java.security.cert.Certificate

@Suppress("DEPRECATION")
class AiVoiceSessionTest : AndroidTestCase() {
    private val listener=object:OfflineVoiceSession.Listener {
        override fun onState(state:OfflineVoiceSession.State) {}
        override fun onPreview(text:String,isFinal:Boolean) {}
        override fun onError(message:String) {}
    }
    private class Connection:HttpsURLConnection(URL("https://example.invalid/stt")) {
        val sent=ByteArrayOutputStream()
        var status=200
        var disconnected=false
        override fun connect() {}
        override fun disconnect() { disconnected=true }
        override fun usingProxy()=false
        override fun getCipherSuite()="test"
        override fun getLocalCertificates():Array<Certificate>?=null
        override fun getServerCertificates():Array<Certificate> = emptyArray()
        override fun getOutputStream()=sent
        override fun getResponseCode()=status
        override fun getInputStream()="""{"text":"synthetic transcript"}""".byteInputStream()
    }
    private fun transcribe(session:AiVoiceSession):String {
        val method=AiVoiceSession::class.java.getDeclaredMethod("transcribe",Long::class.javaPrimitiveType,ByteArray::class.java,Int::class.javaPrimitiveType)
        method.isAccessible=true
        return method.invoke(session,0L,ByteArray(3200),3200) as String
    }
    fun testWavUploadAndResponseWithoutRealNetwork() {
        val conn=Connection()
        val session=AiVoiceSession(context,ApiConfiguration(voiceKey="synthetic-key"),listener) { conn }
        try {
            assertEquals("synthetic transcript",transcribe(session))
            val bytes=conn.sent.toByteArray()
            val body=String(bytes,Charsets.ISO_8859_1)
            assertTrue(body.indexOf("name=\"model\"")<body.indexOf("name=\"file\""))
            assertTrue(body.contains("grok-voice-transcribe-2.0"))
            assertTrue(body.contains("RIFF")); assertTrue(body.contains("WAVEfmt "))
            assertEquals("Bearer synthetic-key",conn.getRequestProperty("Authorization"))
            assertFalse(conn.instanceFollowRedirects)
            assertTrue(conn.disconnected)
        } finally { session.release() }
    }
    fun testCancellationNeverWritesAudio() {
        val conn=Connection()
        val session=AiVoiceSession(context,ApiConfiguration(voiceKey="synthetic-key"),listener) { conn }
        session.cancel()
        try { transcribe(session);fail("Canceled request uploaded") }
        catch(e:java.lang.reflect.InvocationTargetException) { assertTrue(e.cause is IllegalStateException) }
        finally { session.release() }
        assertEquals(0,conn.sent.size())
    }
    fun testAuthenticationFailureDoesNotExposeKey() {
        val conn=Connection().apply { status=401 }
        val session=AiVoiceSession(context,ApiConfiguration(voiceKey="synthetic-key"),listener) { conn }
        try { transcribe(session);fail("401 accepted") }
        catch(e:java.lang.reflect.InvocationTargetException) {
            assertTrue(e.cause!!.message!!.contains("鉴权失败"))
            assertFalse(e.cause!!.message!!.contains("synthetic-key"))
        } finally { session.release() }
    }
    fun testModeAndEncryptedConfigurationPersistence() {
        val settings=SettingsStore(context);val oldMode=settings.voiceMode
        val store=ApiConfigurationStore(context);val old=store.load()
        try {
            settings.voiceMode="ai";assertEquals("ai",SettingsStore(context).voiceMode)
            settings.voiceMode="invalid";assertEquals("offline",SettingsStore(context).voiceMode)
            val next=old.copy(voiceUrl="https://example.invalid/stt",voiceKey="synthetic-voice-secret",voiceModel="voice-model")
            store.save(next);assertEquals(next,store.load())
            assertFalse(context.getSharedPreferences("api_configuration",0).all.toString().contains("synthetic-voice-secret"))
        } finally { settings.voiceMode=oldMode;store.save(old) }
    }
    fun testWavHeaderHasBoundedPcmLayout() {
        val data=AiVoiceSession.wavHeader(3200)
        val b=java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(44,data.size);assertEquals(3236,b.getInt(4));assertEquals(16000,b.getInt(24));assertEquals(3200,b.getInt(40))
        try { AiVoiceSession.wavHeader(1920002);fail("Oversized audio accepted") } catch(_:IllegalArgumentException) {}
    }
}
