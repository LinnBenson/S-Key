package io.secure.keyboard.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import io.secure.keyboard.ai.AiConversation
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small AES-GCM envelope in noBackupFilesDir. Never stores source text or API secrets. */
internal class AiConversationStore(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "ai_conversation.enc"))
    private fun key(): SecretKey = StorageKeys.get("skey_ai_conversation")
    fun load(): AiConversation? = synchronized(lock) {
        if (!file.baseFile.exists()) return@synchronized null
        try {
            check(file.baseFile.length() in 29..4096)
            val bytes = file.readFully()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0,12)))
            val plain = cipher.doFinal(bytes.copyOfRange(12,bytes.size))
            try {
                val json = JSONObject(String(plain,Charsets.UTF_8))
                AiConversation(json.getString("id"),json.getString("scope"),json.getLong("updatedAt"),json.getString("type"))
            } finally { plain.fill(0); bytes.fill(0) }
        } catch (_: Exception) { revision++; file.delete(); null }
    }
    /** Read ID and revocation generation under one lock, so clears cannot split the pair. */
    fun snapshot(): Pair<AiConversation?, Long> = synchronized(lock) { load() to revision }

    fun save(value: AiConversation, expectedRevision: Long? = null) = synchronized(lock) {
        check(expectedRevision == null || expectedRevision == revision) { "会话已重置" }
        val plain = JSONObject().put("id",value.id).put("scope",value.scope).put("updatedAt",value.updatedAt).put("type",value.type)
            .toString().toByteArray(Charsets.UTF_8)
        try {
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE,key())
            val out=file.startWrite()
            try { out.write(cipher.iv); out.write(cipher.doFinal(plain)); file.finishWrite(out) }
            catch(e:Exception) { file.failWrite(out); throw e }
        } finally { plain.fill(0) }
    }
    fun revision(): Long = synchronized(lock) { revision }
    fun clear() = synchronized(lock) { revision++; file.delete() }
    companion object { private val lock=Any(); private var revision=0L }
}
