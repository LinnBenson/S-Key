package io.secure.keyboard.data

import android.util.AtomicFile
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/** Independent authenticated, atomic files; plaintext never enters a temporary file. */
internal class EncryptedContentFile(path: File) {
    private val file = AtomicFile(path)
    fun exists() = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()
    fun read(): String {
        val bytes = file.readFully()
        require(bytes.size >= 28) { "加密内容损坏" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, StorageKeys.get("skey_private_content"), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val plain = cipher.doFinal(bytes.copyOfRange(12, bytes.size))
        return try { String(plain, Charsets.UTF_8) } finally { plain.fill(0) }
    }
    fun write(value: String) {
        check(file.baseFile.parentFile!!.let { it.isDirectory || it.mkdirs() })
        val plain = value.toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, StorageKeys.get("skey_private_content"))
            val output = file.startWrite()
            try {
                output.write(cipher.iv)
                output.write(cipher.doFinal(plain))
                file.finishWrite(output)
            } catch (e: Exception) { file.failWrite(output); throw e }
        } finally { plain.fill(0) }
    }
}
