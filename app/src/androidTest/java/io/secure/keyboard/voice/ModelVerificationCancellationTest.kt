package io.secure.keyboard.voice

import android.content.ContextWrapper
import android.test.AndroidTestCase
import java.io.File
import java.io.RandomAccessFile

@Suppress("DEPRECATION")
class ModelVerificationCancellationTest : AndroidTestCase() {
    fun testCancellationStopsHashingButNeverAcceptsAnInvalidModel() {
        val root = File(context.cacheDir, "model-cancel-${System.nanoTime()}").apply { mkdirs() }
        val sandbox = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir() = root
        }
        try {
            val repository = ModelRepository(sandbox)
            assertFalse(repository.verifyInstalled { false })
            val installed = File(root, "mandarin-voice-v1").apply { mkdirs() }
            RandomAccessFile(File(installed, "model.int8.onnx"), "rw").use { it.setLength(ModelRepository.MODEL_SIZE) }
            RandomAccessFile(File(installed, "tokens.txt"), "rw").use { it.setLength(ModelRepository.TOKENS_SIZE) }
            File(installed, "verified").writeText(ModelRepository.MODEL_ID)
            var checks = 0
            assertFalse(repository.verifyInstalled { ++checks < 4 })
            assertEquals(4, checks)
            try { repository.verifyInstalled(); fail("Invalid model must still be rejected") }
            catch (_: IllegalStateException) { }
        } finally { root.deleteRecursively() }
    }
}
