package io.secure.keyboard.data

import android.content.ClipboardManager
import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

/** Private, backup-excluded content. Does not observe the clipboard itself. */
class ContentStore(context: Context) {
    data class ClipboardItem(val id: String, val text: String, val pinned: Boolean = false)

    data class Phrase(val id: String, val text: String, val pinned: Boolean = false)

    data class DictionaryEntry(val id: String, val word: String, val reading: String)

    private val app = context.applicationContext
    private val settings = SettingsStore(app)
    private val dir = File(app.noBackupFilesDir, "content").apply { mkdirs() }
    private val file = File(dir, "content.json")
    private data class DictionaryCacheKey(val path: String, val kind: DictionaryKind)

    private val dictionaryCacheKeys =
        DictionaryKind.entries.associateWith { DictionaryCacheKey(file.canonicalPath, it) }
    private val dictionaryRevisionCounters =
        dictionaryCacheKeys.mapValues { (_, key) ->
            dictionaryRevisions.computeIfAbsent(key) { AtomicLong(0L) }
        }

    private data class DictionaryStamp(
        val exists: Boolean,
        val size: Long,
        val modified: String,
        val identity: String?,
    )

    private data class DictionarySnapshot(
        val revision: Long,
        val stamp: DictionaryStamp,
        val rows: List<DictionaryEntry>,
    )

    /** Lock-free path-and-kind-scoped revision; never waits behind content I/O on the main thread. */
    fun dictionaryRevision(kind: DictionaryKind = DictionaryKind.CHINESE): Long =
        dictionaryRevisionCounters.getValue(kind).get()

    // All callers hold lock. Never cache the JSON root, clipboard, phrases, or historical
    // snapshots. Shared-file corruption or failure invalidates both kinds.
    private fun invalidateDictionarySnapshot(kind: DictionaryKind) {
        dictionarySnapshots.remove(dictionaryCacheKeys.getValue(kind))
        dictionaryRevisionCounters.getValue(kind).incrementAndGet()
    }

    private fun invalidateDictionarySnapshots() =
        DictionaryKind.entries.forEach { invalidateDictionarySnapshot(it) }

    private fun dictionaryEpoch(kind: DictionaryKind): Long =
        dictionaryEpochs[dictionaryCacheKeys.getValue(kind)] ?: 0L

    private fun advanceDictionaryEpoch(kind: DictionaryKind) {
        dictionaryEpochs[dictionaryCacheKeys.getValue(kind)] = dictionaryEpoch(kind) + 1L
    }

    private fun dictionaryStamp(): DictionaryStamp {
        if (!file.exists()) {
            if (dir.exists() && !dir.canRead())
                throw ContentReadException(java.io.IOException("Content directory is unreadable"))
            return DictionaryStamp(false, 0L, "", null)
        }
        return try {
            require(file.canRead()) { "Content file is unreadable" }
            val attributes =
                Files.readAttributes(
                    file.toPath(),
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
            require(attributes.isRegularFile) { "Content path is not a regular file" }
            DictionaryStamp(
                true,
                attributes.size(),
                attributes.lastModifiedTime().toString(),
                attributes.fileKey()?.toString(),
            )
        } catch (error: Exception) {
            throw ContentReadException(error)
        }
    }

    class ContentReadException(cause: Exception) :
        IllegalStateException(
            "本机内容无法读取，已停止保存以免覆盖原数据。请重试；如需重新使用，可在设置中明确清空全部用户内容。布局与设置不会受影响。",
            cause,
        )

    private fun read(): JSONObject {
        return try {
            if (!file.exists()) {
                require(!dir.exists() || dir.canRead()) { "Content directory is unreadable" }
                return JSONObject()
            }
            JSONObject(file.readText()).also { root ->
                for (key in listOf("clipboard", "phrases", "dictionary", "englishDictionary", "emoji")) {
                    if (!root.has(key)) continue
                    val entries = root.getJSONArray(key)
                    for (index in 0 until entries.length()) {
                        if (key == "emoji") require(entries.get(index) is String)
                        else {
                            val row = entries.getJSONObject(index)
                            val isDictionary = DictionaryKind.entries.any { it.storageKey == key }
                            val fields =
                                if (isDictionary) listOf("id", "word", "reading")
                                else listOf("id", "text")
                            fields.forEach { require(row.get(it) is String) }
                            if (!isDictionary && row.has("pinned"))
                                require(row.get("pinned") is Boolean)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            invalidateDictionarySnapshots()
            throw ContentReadException(e)
        }
    }

    private fun filesIn(directory: File): Array<File> {
        if (!directory.exists()) return emptyArray()
        return directory.listFiles() ?: throw java.io.IOException("无法检查本机文件是否已清空，请重试。")
    }

    private fun deleteVerified(target: File) {
        if (target.exists() && (!target.deleteRecursively() || target.exists())) {
            throw java.io.IOException("部分本机内容未能删除，尚未完成清空。请关闭键盘后重试。")
        }
    }

    private fun write(root: JSONObject) {
        // Mutators already evicted the changed kind. Preserve every unaffected dictionary's
        // immutable rows across replacement of their shared file, including the other language.
        val existing =
            dictionaryCacheKeys.mapValues { (_, key) -> dictionarySnapshots.remove(key) }
        try {
            val oldStamp = dictionaryStamp()
            val externallyChanged = existing.values.any { it != null && it.stamp != oldStamp }
            val preserved = if (externallyChanged) emptyMap() else existing.filterValues { it != null }
            if (externallyChanged) invalidateDictionarySnapshots()
            dir.mkdirs()
            val tmp = File(dir, "content.tmp")
            tmp.outputStream().use { output ->
                output.write(root.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                tmp.delete()
                error("无法保存本机内容")
            }
            // Never retain an app-owned export, journal, history, or recoverable prior version.
            filesIn(dir).filter { it != file }.forEach { deleteVerified(it) }
            if (preserved.isNotEmpty()) {
                val newStamp = dictionaryStamp()
                preserved.forEach { (kind, snapshot) ->
                    dictionarySnapshots[dictionaryCacheKeys.getValue(kind)] =
                        checkNotNull(snapshot).copy(stamp = newStamp)
                }
            }
        } catch (error: Exception) {
            invalidateDictionarySnapshots()
            throw error
        }
    }

    private fun array(root: JSONObject, key: String) = root.optJSONArray(key) ?: JSONArray()

    private fun objects(key: String): List<JSONObject> =
        synchronized(lock) {
            val values = array(read(), key)
            (0 until values.length()).map { values.getJSONObject(it) }
        }

    private fun mutate(key: String, operation: (MutableList<JSONObject>) -> Unit) =
        synchronized(lock) {
            DictionaryKind.entries.find { it.storageKey == key }?.let { invalidateDictionarySnapshot(it) }
            val root = read()
            val values = array(root, key)
            val list = (0 until values.length()).map { values.getJSONObject(it) }.toMutableList()
            operation(list)
            root.put(key, JSONArray(list))
            write(root)
        }

    fun clipboard(): List<ClipboardItem> =
        objects("clipboard").map {
            ClipboardItem(it.getString("id"), it.getString("text"), it.optBoolean("pinned"))
        }

    /** Display grouping never changes the stored copy order used for bounded eviction. */
    fun clipboardForDisplay(): List<ClipboardItem> =
        clipboard().asReversed().sortedByDescending { it.pinned }

    fun setClipboardPinned(id: String, pinned: Boolean) =
        mutate("clipboard") { list ->
            list.find { it.optString("id") == id }?.put("pinned", pinned)
        }

    /**
     * copyToken is ClipDescription.timestamp, NOT a hash of text: repeated copies stay distinct.
     */
    /** Snapshot when an OS copy event is received, before queueing its storage work. */
    fun clipboardGeneration(): Long = synchronized(lock) { clipboardEpoch }

    fun captureClipboard(
        text: String,
        copyToken: Long,
        newCopyEvent: Boolean = false,
        expectedGeneration: Long? = null,
    ): Boolean =
        synchronized(lock) {
            if (expectedGeneration != null && expectedGeneration != clipboardEpoch)
                return@synchronized false
            // A listener callback and an input-view reopen can observe the same OS copy.
            // Timestamp identity, not text or a time-window debounce, decides duplication.
            // Some devices expose no timestamp; only actual callbacks are usable there.
            if (
                text.isEmpty() ||
                    (copyToken <= 0L && !newCopyEvent) ||
                    (copyToken > 0L && copyToken == settings.lastClipboardToken)
            )
                return@synchronized false
            addClipboard(text)
            settings.lastClipboardToken = copyToken
            true
        }

    fun addClipboard(text: String) {
        if (text.isNotEmpty())
            mutate("clipboard") { list ->
                // Select a victim from existing history, so a full all-pinned history still
                // accepts the newest copy. Pins cannot bypass the configured storage bound.
                while (list.size >= settings.clipboardLimit) evictOldestClipboard(list)
                list.add(item(text))
            }
    }

    fun trimClipboard() =
        mutate("clipboard") { list ->
            while (list.size > settings.clipboardLimit) evictOldestClipboard(list)
        }

    private fun evictOldestClipboard(list: MutableList<JSONObject>) {
        val oldestUnpinned = list.indexOfFirst { !it.optBoolean("pinned") }
        list.removeAt(if (oldestUnpinned >= 0) oldestUnpinned else 0)
    }

    fun deleteClipboard(id: String) =
        synchronized(lock) {
            clipboardEpoch++
            mutate("clipboard") { it.removeAll { row -> row.optString("id") == id } }
        }

    fun clearClipboard() =
        synchronized(lock) {
            clipboardEpoch++
            suppressCurrentClipboard()
            removeCategory("clipboard")
        }

    private fun suppressCurrentClipboard() {
        val manager = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val token =
            try {
                manager.primaryClipDescription?.timestamp ?: 0L
            } catch (_: Exception) {
                0L
            }
        if (token > 0L) settings.lastClipboardToken = token
    }

    fun phrases(): List<Phrase> =
        objects("phrases").map {
            Phrase(it.getString("id"), it.getString("text"), it.optBoolean("pinned"))
        }

    /** Stable grouping preserves manual order within each group and when entries are unpinned. */
    fun phrasesForDisplay(): List<Phrase> = phrases().sortedByDescending { it.pinned }

    fun setPhrasePinned(id: String, pinned: Boolean) =
        mutate("phrases") { list ->
            list.find { it.optString("id") == id }?.put("pinned", pinned)
        }

    fun addPhrase(text: String) {
        if (text.isNotBlank()) mutate("phrases") { it.add(item(text)) }
    }

    fun updatePhrase(id: String, text: String) =
        mutate("phrases") { list -> list.find { it.optString("id") == id }?.put("text", text) }

    fun deletePhrase(id: String) =
        mutate("phrases") { it.removeAll { row -> row.optString("id") == id } }

    fun movePhrase(id: String, newIndex: Int) =
        mutate("phrases") { list ->
            val index = list.indexOfFirst { it.optString("id") == id }
            if (index >= 0) {
                val row = list.removeAt(index)
                list.add(newIndex.coerceIn(0, list.size), row)
            }
        }

    fun dictionary(kind: DictionaryKind = DictionaryKind.CHINESE): List<DictionaryEntry> =
        synchronized(lock) {
            try {
                val stamp = dictionaryStamp()
                // Any unrecognized shared-file replacement may affect either dictionary.
                if (dictionaryCacheKeys.values.any { key ->
                        dictionarySnapshots[key]?.let { it.stamp != stamp } == true
                    }) invalidateDictionarySnapshots()
                val key = dictionaryCacheKeys.getValue(kind)
                val previous = dictionarySnapshots[key]
                val revision = dictionaryRevision(kind)
                if (previous != null && previous.revision == revision && previous.stamp == stamp) {
                    return@synchronized previous.rows
                }
                // Metadata catches ordinary external corruption/replacement. Deliberately restoring
                // all metadata after same-size tampering is outside this app-owned-file cache model.
                if (previous != null) invalidateDictionarySnapshot(kind)
                val values = array(read(), kind.storageKey)
                val rows = ArrayList<DictionaryEntry>(values.length())
                for (index in 0 until values.length()) {
                    val item = values.getJSONObject(index)
                    rows.add(
                        DictionaryEntry(
                            item.getString("id"),
                            item.getString("word"),
                            item.getString("reading"),
                        )
                    )
                }
                if (stamp != dictionaryStamp())
                    throw ContentReadException(java.io.IOException("Content changed while reading"))
                val immutable: List<DictionaryEntry> = Collections.unmodifiableList(rows)
                dictionarySnapshots[key] = DictionarySnapshot(dictionaryRevision(kind), stamp, immutable)
                immutable
            } catch (error: Exception) {
                invalidateDictionarySnapshots()
                throw error
            }
        }

    fun addDictionary(word: String, reading: String, kind: DictionaryKind = DictionaryKind.CHINESE) {
        kind.validateEntry(word, reading)
        mutate(kind.storageKey) {
            require(it.size < MAX_DICTIONARY_ENTRIES) { DICTIONARY_LIMIT_MESSAGE }
            it.add(dictionaryItem(word, reading))
        }
    }

    fun updateDictionary(
        id: String,
        word: String,
        reading: String,
        kind: DictionaryKind = DictionaryKind.CHINESE,
    ) {
        kind.validateEntry(word, reading)
        mutate(kind.storageKey) { list ->
            list.find { it.optString("id") == id }?.put("word", word)?.put("reading", reading)
        }
    }

    fun deleteDictionary(id: String, kind: DictionaryKind = DictionaryKind.CHINESE) =
        synchronized(lock) {
            advanceDictionaryEpoch(kind)
            mutate(kind.storageKey) { it.removeAll { row -> row.optString("id") == id } }
        }

    /** Snapshot before starting an external file read that may outlive a same-kind deletion. */
    fun captureDictionaryEpoch(kind: DictionaryKind = DictionaryKind.CHINESE): Long =
        synchronized(lock) { dictionaryEpoch(kind) }

    /**
     * UTF-8 TSV: word TAB reading, one pair per line, optional first line word TAB reading. No
     * escaping. Validation is complete before a single atomic append to the selected dictionary.
     */
    fun importDictionary(
        tsv: String,
        expectedEpoch: Long? = null,
        kind: DictionaryKind = DictionaryKind.CHINESE,
    ): Int {
        var parsedCount = 0
        val entries =
            tsv.removePrefix("\uFEFF")
                .lineSequence()
                .filter { it.isNotBlank() }
                .mapIndexedNotNull { index, line ->
                    if (index == 0 && line.trimEnd('\r') == "word\treading") null
                    else {
                        require(++parsedCount <= MAX_DICTIONARY_ENTRIES) {
                            DICTIONARY_LIMIT_MESSAGE
                        }
                        val parts = line.trimEnd('\r').split('\t')
                        require(parts.size == 2) { "每行应为「词语 + 制表符 + 拼音／编码」" }
                        kind.validateEntry(parts[0], parts[1])
                        dictionaryItem(parts[0], parts[1])
                    }
                }
                .toList()
        mutate(kind.storageKey) {
            check(expectedEpoch == null || expectedEpoch == dictionaryEpoch(kind)) {
                "词库已被删除或清空，本次导入已取消。请重新选择文件后重试。"
            }
            require(it.size <= MAX_DICTIONARY_ENTRIES - entries.size) { DICTIONARY_LIMIT_MESSAGE }
            it.addAll(entries)
        }
        return entries.size
    }

    fun exportDictionary(kind: DictionaryKind = DictionaryKind.CHINESE): String =
        "word\treading\n" + dictionary(kind).joinToString("\n") { "${it.word}\t${it.reading}" }

    fun recentEmoji(): List<String> =
        synchronized(lock) {
            val a = array(read(), "emoji")
            (0 until a.length()).map { a.getString(it) }
        }

    fun useEmoji(emoji: String) =
        synchronized(lock) {
            val root = read()
            val a = array(root, "emoji")
            val values =
                (0 until a.length()).map { a.getString(it) }.filter { it != emoji }.toMutableList()
            values.add(0, emoji)
            root.put("emoji", JSONArray(values.take(50)))
            write(root)
        }

    private fun removeCategory(key: String) =
        synchronized(lock) {
            DictionaryKind.entries.find { it.storageKey == key }?.let { invalidateDictionarySnapshot(it) }
            val root = read()
            root.remove(key)
            write(root)
            purgeCaches()
        }

    fun clearPhrases() = removeCategory("phrases")

    fun clearDictionary(kind: DictionaryKind = DictionaryKind.CHINESE) =
        synchronized(lock) {
            advanceDictionaryEpoch(kind)
            removeCategory(kind.storageKey)
        }

    fun clearEmoji() = removeCategory("emoji")

    fun clearAll() =
        synchronized(lock) {
            clipboardEpoch++
            DictionaryKind.entries.forEach { advanceDictionaryEpoch(it) }
            invalidateDictionarySnapshots()
            suppressCurrentClipboard()
            filesIn(dir).forEach { deleteVerified(it) }
            purgeCaches()
        }

    private fun purgeCaches() {
        filesIn(app.cacheDir).forEach { deleteVerified(it) }
        app.externalCacheDirs.filterNotNull().forEach { directory ->
            filesIn(directory).forEach { deleteVerified(it) }
        }
    }

    private fun item(text: String) =
        JSONObject().put("id", UUID.randomUUID().toString()).put("text", text)

    private fun dictionaryItem(word: String, reading: String) =
        JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("word", word)
            .put("reading", reading)

    companion object {
        const val MAX_DICTIONARY_ENTRIES = 10_000
        private const val DICTIONARY_LIMIT_MESSAGE = "用户词库最多保存 10000 条。此次操作未写入任何词条，请减少导入数量或先删除部分词条。"
        private val lock = Any()
        private var clipboardEpoch = 0L
        private val dictionaryEpochs = mutableMapOf<DictionaryCacheKey, Long>()
        private val dictionarySnapshots = mutableMapOf<DictionaryCacheKey, DictionarySnapshot>()
        private val dictionaryRevisions = ConcurrentHashMap<DictionaryCacheKey, AtomicLong>()
    }
}
