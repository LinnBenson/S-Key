package io.secure.keyboard.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.test.AndroidTestCase
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

@Suppress("DEPRECATION")
class EncryptedContentRegressionTest : AndroidTestCase() {
    private lateinit var root: File
    private lateinit var isolated: Context
    override fun setUp() {
        super.setUp()
        root = File(context.cacheDir, "encrypted-regression").apply { deleteRecursively(); mkdirs() }
        isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = File(root, "data").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getExternalCacheDirs(): Array<File> = emptyArray()
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                context.getSharedPreferences("encrypted_regression_$name", mode)
        }
        isolated.getSharedPreferences("settings", 0).edit().clear().commit()
    }
    override fun tearDown() {
        root.deleteRecursively()
        isolated.getSharedPreferences("settings", 0).edit().clear().commit()
        super.tearDown()
    }
    fun testMigrationAndCopyDoNotRewriteDictionaryAndNoPlaintextRemains() {
        val legacy = File(isolated.noBackupFilesDir, "content/content.json")
        legacy.parentFile!!.mkdirs()
        val marker = "private migration phrase"
        legacy.writeText(JSONObject().put("clipboard", JSONArray().put(JSONObject().put("id", "c").put("text", marker)))
            .put("phrases", JSONArray().put(JSONObject().put("id", "p").put("text", marker)))
            .put("emoji", JSONArray().put("🙂"))
            .put("dictionary", JSONArray().put(JSONObject().put("id", "d").put("word", "测试").put("reading", "ceshi"))).toString())
        val store = ContentStore(isolated)
        assertEquals(marker, store.clipboard().single().text)
        assertEquals(marker, store.phrases().single().text)
        assertEquals(listOf("🙂"), store.recentEmoji())
        val oldBytes = legacy.readBytes()
        val oldTime = legacy.lastModified()
        repeat(15) { store.addClipboard("new copy $it") }
        assertEquals(10, store.clipboard().size)
        assertTrue(oldBytes.contentEquals(legacy.readBytes()))
        assertEquals(oldTime, legacy.lastModified())
        assertFalse(legacy.readText().contains(marker))
        for (name in listOf("clipboard", "phrases", "emoji")) {
            val bytes = File(isolated.noBackupFilesDir, "private-content/$name.enc").readBytes()
            assertFalse(String(bytes).contains(marker))
        }
        assertEquals(marker, ContentStore(isolated).phrases().single().text)
        val backup = java.io.ByteArrayOutputStream()
        store.writeBackup(backup)
        store.clearPhrases()
        assertTrue(store.phrases().isEmpty())
        store.restoreBackup(backup.toByteArray().inputStream())
        assertEquals(marker, store.phrases().single().text)
        store.clearEmoji()
        assertTrue(ContentStore(isolated).recentEmoji().isEmpty())
    }
    fun testCiphertextTamperingFailsClosedAndPreservesOtherCategories() {
        val store = ContentStore(isolated)
        store.addPhrase("keep phrase")
        store.addClipboard("private copy")
        val file = File(isolated.noBackupFilesDir, "private-content/clipboard.enc")
        val damaged = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        file.writeBytes(damaged)
        try { store.addClipboard("must not overwrite"); fail("Tampering accepted") }
        catch (_: ContentStore.ContentReadException) { }
        assertTrue(damaged.contentEquals(file.readBytes()))
        assertEquals("keep phrase", store.phrases().single().text)
    }
    fun testInterruptedMigrationWithDamagedEncryptedCopyPreservesLegacy() {
        val legacy = File(isolated.noBackupFilesDir, "content/content.json")
        legacy.parentFile!!.mkdirs()
        val original = "{\"clipboard\":[{\"id\":\"c\",\"text\":\"keep original\"}]}"
        legacy.writeText(original)
        val damaged = File(isolated.noBackupFilesDir, "private-content/clipboard.enc")
        damaged.parentFile!!.mkdirs()
        damaged.writeText("damaged encrypted copy")
        try { ContentStore(isolated).clipboard(); fail("Damaged migration must stop") }
        catch (_: ContentStore.ContentReadException) { }
        assertEquals(original, legacy.readText())
    }
    fun testNewDefaultsAndClamps() {
        val settings = SettingsStore(isolated)
        assertEquals("timed", settings.clipboardRetention)
        assertEquals(24, settings.clipboardRetentionHours)
        assertEquals(10, settings.clipboardLimit)
        assertTrue(settings.editorPreview && settings.autoChineseSpacing)
        assertTrue(settings.chineseAssociation && settings.englishAssociation)
        settings.clipboardRetentionHours = 999
        settings.clipboardLimit = 999
        assertEquals(72, settings.clipboardRetentionHours)
        assertEquals(100, settings.clipboardLimit)
        settings.clipboardRetentionHours = 0
        settings.clipboardLimit = 0
        assertEquals(1, settings.clipboardRetentionHours)
        assertEquals(1, settings.clipboardLimit)
    }
    fun testConcurrentKeyCreationReturnsOneKey() {
        val alias = "skey_regression_${System.nanoTime()}"
        val executor = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val tasks = (1..16).map { executor.submit<ByteArray> {
                val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, StorageKeys.get(alias))
                cipher.iv + cipher.doFinal("roundtrip".toByteArray())
            } }
            tasks.forEach { task ->
                val bytes = task.get()
                val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE, StorageKeys.get(alias), javax.crypto.spec.GCMParameterSpec(128, bytes.copyOfRange(0,12)))
                assertEquals("roundtrip", String(cipher.doFinal(bytes.copyOfRange(12,bytes.size))))
            }
        } finally {
            executor.shutdownNow()
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }
}
