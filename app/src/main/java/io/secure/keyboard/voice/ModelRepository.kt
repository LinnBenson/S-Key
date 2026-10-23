package io.secure.keyboard.voice

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.security.MessageDigest
import kotlin.concurrent.withLock

/**
 * Imports only the audited Mandarin model. No archive parsing, network, or arbitrary native input.
 */
class ModelRepository(private val context: Context) {
    private val root = File(context.noBackupFilesDir, "mandarin-voice-v1")
    val modelFile
        get() = File(root, "model.int8.onnx")

    val tokensFile
        get() = File(root, "tokens.txt")

    fun isInstalled(): Boolean =
        modelFile.length() == MODEL_SIZE &&
            tokensFile.length() == TOKENS_SIZE &&
            File(root, "verified").isFile

    fun verifyInstalled(isCurrent: () -> Boolean = { true }): Boolean {
        if (!isCurrent()) return false
        check(isInstalled()) { "请先导入离线普通话模型" }
        return verify(modelFile, MODEL_SIZE, MODEL_SHA, isCurrent) &&
            verify(tokensFile, TOKENS_SIZE, TOKENS_SHA, isCurrent)
    }

    /** Same lock as import: never delete the live staging directory. No user files are touched. */
    fun cleanupInterruptedImports() {
        // A slow document provider must not hold up keyboard initialization.
        if (!IMPORT_LOCK.tryLock()) return
        try {
        context.noBackupFilesDir.listFiles()?.filter {
            it.name.matches(Regex("mandarin-import-[0-9]+")) &&
                !java.nio.file.Files.isSymbolicLink(it.toPath())
        }?.forEach { check(it.deleteRecursively()) { "无法清理未完成的模型导入" } }
        } finally { IMPORT_LOCK.unlock() }
    }

    /** The user picks the extracted model folder with Android's Storage Access Framework. */
    fun importTree(tree: Uri) =
        IMPORT_LOCK.withLock {
            cleanupInterruptedImports()
            if (isInstalled() && runCatching { verifyInstalled() }.isSuccess) return@withLock
            val parent = DocumentsContract.getTreeDocumentId(tree)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            val found = mutableMapOf<String, Uri>()
            context.contentResolver
                .query(
                    children,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    ),
                    null,
                    null,
                    null,
                )
                ?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(1)
                        if (name == "model.int8.onnx" || name == "tokens.txt") {
                            check(!found.containsKey(name)) { "模型文件重名" }
                            found[name] =
                                DocumentsContract.buildDocumentUriUsingTree(
                                    tree,
                                    cursor.getString(0),
                                )
                        }
                    }
                }
            check(found.size == 2) { "请选择同时包含 model.int8.onnx 和 tokens.txt 的模型文件夹" }
            val staging = File(context.noBackupFilesDir, "mandarin-import-${System.nanoTime()}")
            check(staging.mkdirs()) { "无法创建模型目录" }
            try {
                copyChecked(
                    found.getValue("model.int8.onnx"),
                    File(staging, "model.int8.onnx"),
                    MODEL_SIZE,
                    MODEL_SHA,
                )
                copyChecked(
                    found.getValue("tokens.txt"),
                    File(staging, "tokens.txt"),
                    TOKENS_SIZE,
                    TOKENS_SHA,
                )
                File(staging, "verified").writeText(MODEL_ID)
                if (root.exists()) check(root.deleteRecursively()) { "无法清理旧模型" }
                check(staging.renameTo(root)) { "无法保存模型" }
            } finally {
                staging.deleteRecursively()
            }
        }

    private fun copyChecked(uri: Uri, destination: File, size: Long, hash: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        val buffer = ByteArray(64 * 1024)
        context.contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    count += n
                    check(count <= size) { "模型大小不匹配，请使用指定版本" }
                    digest.update(buffer, 0, n)
                    output.write(buffer, 0, n)
                }
            }
        } ?: error("无法读取所选模型")
        check(count == size && digest.digest().hex() == hash) { "模型校验失败：文件损坏或版本不匹配" }
    }

    private fun verify(file: File, size: Long, hash: String, isCurrent: () -> Boolean): Boolean {
        check(file.length() == size) { "模型大小不匹配" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                if (!isCurrent()) return false
                val n = input.read(bytes)
                if (n < 0) break
                digest.update(bytes, 0, n)
            }
        }
        if (!isCurrent()) return false
        check(digest.digest().hex() == hash) { "模型校验失败，请重新安装模型" }
        return true
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        private val IMPORT_LOCK = java.util.concurrent.locks.ReentrantLock()
        const val MODEL_ID = "sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01"
        const val MODEL_SIZE = 26342340L
        const val TOKENS_SIZE = 13366L
        const val MODEL_SHA = "68c9c943840f7d9cf3e8a4970ba50f404feb5277f611fa82b7e72267786fa84a"
        const val TOKENS_SHA = "6fed8c6c248516f38e7faa19404b57413e8ce259f1cbc1fa4aebc86eac32fdfd"
        const val DOWNLOAD_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01.tar.bz2"
    }
}
