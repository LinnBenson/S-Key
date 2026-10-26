package io.secure.keyboard.data

import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Versioned authenticated envelope. Passwords and derived keys are never persisted. */
internal object PasswordBackup {
    private val magic = "SKEYENC1".toByteArray(Charsets.US_ASCII)
    internal const val LIMIT = 48_000_000
    private fun key(password: CharArray, salt: ByteArray): ByteArray {
        require(password.isNotEmpty() && password.size <= 1024)
        val spec = PBEKeySpec(password, salt, 600_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    fun encrypt(plain: ByteArray, output: OutputStream, password: CharArray) {
        encrypt(plain, plain.size, output, password)
    }
    internal fun encrypt(plain: ByteArray, length: Int, output: OutputStream, password: CharArray) {
        require(length in 0..minOf(LIMIT, plain.size))
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val header = magic + salt + nonce
        val derived = key(password, salt)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derived, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header)
            output.write(header); output.write(cipher.doFinal(plain, 0, length)); output.flush()
        } finally { derived.fill(0) }
    }
    fun decrypt(input: InputStream, password: CharArray): ByteArray {
        val bytes = input.readBytesBounded(LIMIT + 52)
        require(bytes.size >= 52 && bytes.copyOfRange(0, 8).contentEquals(magic)) { "不支持的备份格式" }
        val derived = key(password, bytes.copyOfRange(8, 24))
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derived, "AES"), GCMParameterSpec(128, bytes.copyOfRange(24, 36)))
            cipher.updateAAD(bytes, 0, 36)
            cipher.doFinal(bytes, 36, bytes.size - 36)
        } finally { derived.fill(0); bytes.fill(0) }
    }
    private fun InputStream.readBytesBounded(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= limit) { "备份文件过大" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}

/** Avoid payload copies and wipe the backing capacity, not just the used prefix. */
internal class SensitiveBackupBuffer : java.io.ByteArrayOutputStream() {
    override fun write(b: Int) { require(count < PasswordBackup.LIMIT); super.write(b) }
    override fun write(b: ByteArray, off: Int, len: Int) {
        require(len >= 0 && count.toLong() + len <= PasswordBackup.LIMIT) { "备份文件过大" }
        super.write(b, off, len)
    }
    fun setContentLength(length: Int) {
        java.nio.ByteBuffer.wrap(buf, 0, 4).putInt(length)
    }
    fun encrypt(output: OutputStream, password: CharArray) = PasswordBackup.encrypt(buf, count, output, password)
    override fun close() { buf.fill(0); reset() }
}
