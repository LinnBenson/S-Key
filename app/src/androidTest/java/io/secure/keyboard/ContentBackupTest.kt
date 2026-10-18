package io.secure.keyboard

import android.content.Context
import android.content.ContextWrapper
import android.test.InstrumentationTestCase
import io.secure.keyboard.data.*
import java.io.ByteArrayOutputStream
import java.io.File
import org.json.JSONObject

@Suppress("DEPRECATION")
class ContentBackupTest : InstrumentationTestCase() {
    fun testRepeatedRestoreMemoryAndBoundedParser() {
        val base = instrumentation.targetContext
        val tag = "backup-memory-${System.nanoTime()}"
        val root = File(base.noBackupFilesDir, tag).apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = root
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$tag-$name", mode)
        }
        try {
            val store = ContentStore(context)
            store.addPhrase("SKEY_BACKUP_MEMORY_PRIVATE_" + "text".repeat(125_000))
            val output = ByteArrayOutputStream(); store.writeBackup(output)
            val raw = output.toByteArray()
            fun sample(label: String): Long {
                repeat(3) { System.gc(); System.runFinalization(); android.os.SystemClock.sleep(80) }
                val memory = android.os.Debug.MemoryInfo(); android.os.Debug.getMemoryInfo(memory)
                val java = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                instrumentation.sendStatus(0, android.os.Bundle().apply {
                    putString("audit", "$label pssKiB=${memory.totalPss} javaBytes=$java nativeBytes=${android.os.Debug.getNativeHeapAllocatedSize()}")
                })
                return java
            }
            repeat(3) { store.restoreBackup(raw.inputStream()) }
            val baseline = sample("backup_baseline")
            val refs = mutableListOf<java.lang.ref.WeakReference<java.io.InputStream>>()
            repeat(30) {
                val input = raw.inputStream(); refs.add(java.lang.ref.WeakReference(input))
                store.restoreBackup(input)
                val sink = object : java.io.OutputStream() {
                    override fun write(b: Int) = Unit
                    override fun write(b: ByteArray, off: Int, len: Int) = Unit
                }
                store.writeBackup(sink)
            }
            val after = sample("backup_30_cycles")
            assertTrue("Unexpected retained heap growth", after - baseline < 16 * 1024 * 1024)
            assertTrue("Input streams retained", refs.count { it.get() != null } <= 1)
            val rows = org.json.JSONArray()
            repeat(10_001) { rows.put(JSONObject().put("id", "entry-$it").put("text", "x")) }
            val backup = JSONObject(raw.toString(Charsets.UTF_8))
            backup.getJSONObject("data").put("phrases", rows)
            val before = File(root, "content/content.json").readBytes()
            try { store.restoreBackup(backup.toString().byteInputStream()); fail("Excess records accepted") }
            catch (_: Exception) { }
            assertTrue(before.contentEquals(File(root, "content/content.json").readBytes()))
        } finally { root.deleteRecursively(); base.deleteSharedPreferences("$tag-settings") }
    }

    fun testRoundTripAndInvalidBackupIsAtomic() {
        val base = instrumentation.targetContext
        val tag = "backup-test-${System.nanoTime()}"
        val root = File(base.noBackupFilesDir, tag).apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = root
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$tag-$name", mode)
        }
        try {
            val store = ContentStore(context)
            store.addDictionary("你好", "nihao")
            store.addDictionary("hello", "hel", DictionaryKind.ENGLISH)
            store.addPhrase("line one\nline two")
            store.setPhrasePinned(store.phrases().first().id, true)
            store.saveAiPreset(null, "测试", "只输出正文")
            store.useEmoji("😀")
            val output = ByteArrayOutputStream(); store.writeBackup(output)
            val raw = output.toByteArray()
            val json = JSONObject(raw.toString(Charsets.UTF_8))
            assertFalse(json.getJSONObject("data").has("clipboard"))
            assertFalse(json.getJSONObject("data").has("emoji"))
            store.addPhrase("replace me")
            store.restoreBackup(raw.inputStream())
            assertEquals(1, store.phrases().size)
            assertTrue(store.phrases().first().pinned)
            assertEquals("line one\nline two", store.phrases().first().text)
            assertEquals("hello", store.dictionary(DictionaryKind.ENGLISH).single().word)
            assertEquals("你好", store.dictionary().single().word)
            assertEquals("只输出正文", store.aiPresets().single().prompt)
            assertEquals(listOf("😀"), store.recentEmoji())
            val before = File(root, "content/content.json").readBytes()
            json.getJSONObject("data").getJSONArray("aiPresets").getJSONObject(0).put("prompt", "")
            try { store.restoreBackup(json.toString().byteInputStream()); fail("Invalid preset accepted") }
            catch (_: Exception) { }
            assertTrue(before.contentEquals(File(root, "content/content.json").readBytes()))
            try { store.restoreBackup("{".repeat(100).byteInputStream()); fail("Deep JSON accepted") }
            catch (_: Exception) { }
            assertTrue(before.contentEquals(File(root, "content/content.json").readBytes()))
        } finally {
            root.deleteRecursively()
            base.deleteSharedPreferences("$tag-settings")
        }
    }
}
