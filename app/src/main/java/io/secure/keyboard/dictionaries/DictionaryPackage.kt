package io.secure.keyboard.dictionaries

import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** Only the archive is staged. Entries stream into the repository's validated, atomic importer. */
internal object DictionaryPackage {
    data class Result(val added: Int, val replaced: Int, val skipped: Int) {
        val imported get() = added + replaced
        fun message() = "已导入 $imported 个文件（新增 $added，覆盖 $replaced），跳过 $skipped 个" +
            if (imported > 0) "，编译后生效" else ""
    }
    private const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
    private const val MAX_FILE_BYTES = 64L * 1024 * 1024
    private const val MAX_ENTRIES = 4096

    private val archiveLock = Any()
    private val activeArchives = mutableSetOf<File>()
    fun cleanupInterrupted(cacheDir: File) = synchronized(archiveLock) {
        cacheDir.listFiles()?.filter {
            it.name.startsWith("dictionary-pack-") && it.name.endsWith(".zip") && it !in activeArchives
        }?.forEach { check(it.delete() || !it.exists()) { "无法清理中断的词库包导入" } }
    }

    fun import(input: InputStream, cacheDir: File, repository: DictionaryRepository,
               checkActive: () -> Unit = {}): Result {
        var archive: File? = null
        try {
            input.use { source ->
                archive = synchronized(archiveLock) {
                    cleanupInterrupted(cacheDir)
                    File.createTempFile("dictionary-pack-", ".zip", cacheDir).also { activeArchives.add(it) }
                }
                archive!!.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        checkActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_ARCHIVE_BYTES) { "词库包不能超过 512 MiB" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            var added = 0; var replaced = 0; var skipped = 0
            var expanded = 0L
            ZipFile(checkNotNull(archive)).use { zip ->
                require(zip.size() <= MAX_ENTRIES) { "词库包文件数量过多" }
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    checkActive()
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val parts = entry.name.split('/')
                    val kind = parts.first()
                    val name = parts.last()
                    if (parts.size < 2 || kind !in listOf("wubi", "pinyin", "english") ||
                        parts.any { it.isEmpty() || it == "." || it == ".." || '\\' in it || ':' in it } ||
                        !listOf(".tsv", ".txt", ".yaml", ".yml").any { name.endsWith(it, true) } ||
                        entry.size > MAX_FILE_BYTES) { skipped++; continue }
                    if (expanded >= MAX_ARCHIVE_BYTES) { skipped++; continue }
                    try {
                        val replacing = repository.libraries().any { it.kind == kind && it.filename == name }
                        val source = object : java.io.FilterInputStream(zip.getInputStream(entry)) {
                            private var size = 0L
                            override fun read(): Int {
                                val value = ByteArray(1)
                                return if (read(value, 0, 1) < 0) -1 else value[0].toInt() and 255
                            }
                            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                                checkActive()
                                val count = super.read(bytes, offset, length)
                                if (count > 0) { size += count; expanded += count }
                                require(size <= MAX_FILE_BYTES && expanded <= MAX_ARCHIVE_BYTES) { "词库包解压内容过大" }
                                return count
                            }
                        }
                        source.use { repository.addFile(name, kind, it, replaceExisting = true, scopeToKind = true) }
                        if (replacing) replaced++ else added++
                    } catch (e: java.util.concurrent.CancellationException) { throw e }
                    catch (_: Exception) { skipped++ }
                }
            }
            return Result(added, replaced, skipped)
        } finally {
            archive?.let { file -> synchronized(archiveLock) {
                activeArchives.remove(file)
                check(!file.exists() || file.delete()) { "无法清理词库包临时文件" }
            } }
        }
    }
}
