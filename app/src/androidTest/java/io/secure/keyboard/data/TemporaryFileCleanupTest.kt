package io.secure.keyboard.data

import android.content.Context
import android.content.ContextWrapper
import android.test.AndroidTestCase
import io.secure.keyboard.voice.ModelRepository
import java.io.File

@Suppress("DEPRECATION")
class TemporaryFileCleanupTest : AndroidTestCase() {
    fun testInterruptedFilesRemovedWithoutDeletingSavedDataOrInstalledModel() {
        val root = File(context.cacheDir, "cleanup-test-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = root
        }
        try {
            val store = ContentStore(isolated)
            store.addPhrase("synthetic saved phrase")
            val saved = File(root, "content/content.json").readBytes()
            File(root, "content/content.tmp").writeText("interrupted synthetic content")
            val stale = File(root, "mandarin-import-123").apply { mkdirs() }
            File(stale, "model.int8.onnx").writeBytes(ByteArray(4096))
            val installed = File(root, "mandarin-voice-v1").apply { mkdirs() }
            File(installed, "verified").writeText("keep")
            val unrelated = File(root, "mandarin-import-user").apply { mkdirs() }
            repeat(3) { store.cleanupTemporaryFiles(); ModelRepository(isolated).cleanupInterruptedImports() }
            assertFalse(stale.exists())
            assertFalse(File(root, "content/content.tmp").exists())
            assertTrue(saved.contentEquals(File(root, "content/content.json").readBytes()))
            assertTrue(File(installed, "verified").exists())
            assertTrue(unrelated.exists())
        } finally { root.deleteRecursively() }
    }
}
