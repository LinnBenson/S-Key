package io.secure.keyboard.data

import android.content.ClipData
import android.os.Build
import android.content.ClipboardManager
import android.content.Context
import android.util.JsonWriter
import android.util.AtomicFile
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
    data class ClipboardItem(val id: String, val text: String, val pinned: Boolean = false, val copiedAt: Long = 0L)

    data class AiPreset(val id: String, val name: String, val prompt: String, val reasoningEffort: String = "low", val modelId: String = "", val retentionHours: Int = 4, val webSearch: Boolean = false, val xSearch: Boolean = false, val provider: String = "default")

    data class Phrase(val id: String, val text: String, val pinned: Boolean = false)

    data class DictionaryEntry(val id: String, val word: String, val reading: String)

    private val app = context.applicationContext
    private val settings = SettingsStore(app)
    private val dir = File(app.noBackupFilesDir, "content").apply { mkdirs() }
    private val file = File(dir, "content.json")
    private val privateDir = File(app.noBackupFilesDir, "private-content")
    private val privateFiles = listOf("clipboard", "phrases", "emoji").associateWith {
        EncryptedContentFile(File(privateDir, "$it.enc"))
    }
    private val migrationDone = File(privateDir, "migrated-v1")

    /** Durable encrypted copies precede removal of legacy plaintext; safe to retry after a crash. */
    private fun migratePrivateContent() {
        if (migrationDone.exists()) return
        val root = readLegacy()
        privateFiles.forEach { (key, target) ->
            if (!target.exists()) {
                val rows = if (key == "emoji" && (emojiFile.baseFile.exists() || File(emojiDir, "history.json.bak").exists()))
                    JSONArray(emojiFile.openRead().bufferedReader().use { it.readText() }) else array(root, key)
                target.write(rows.toString())
            } else {
                // A retry must authenticate its durable copy before deleting legacy plaintext.
                JSONArray(target.read())
            }
        }
        val hadPrivateContent = privateFiles.keys.any { root.has(it) }
        privateFiles.keys.forEach { root.remove(it) }
        if (hadPrivateContent) write(root)
        deleteVerified(emojiDir)
        check(migrationDone.createNewFile()) { "无法完成加密内容迁移" }
    }

    private var optionalMigrationAttempted = false
    /** Keep healthy upgrades eager, but a private-storage failure cannot disable dictionaries.
     * Retry through private-content access (which reports errors), or a new store instance. */
    private fun tryPrivateMigration() {
        if (optionalMigrationAttempted) return
        optionalMigrationAttempted = true
        try { migratePrivateContent() } catch (_: Exception) { /* Preserve legacy data for retry. */ }
    }

    private fun readCategory(key: String): JSONObject {
        if (key !in privateFiles) { tryPrivateMigration(); return readLegacy(validatePrivate = false) }
        return try {
            migratePrivateContent()
            val rows = JSONArray(privateFiles.getValue(key).read())
            for (index in 0 until rows.length()) {
                if (key == "emoji") require(rows.get(index) is String)
                else {
                    val row = rows.getJSONObject(index)
                    require(row.get("id") is String && row.get("text") is String)
                    if (row.has("pinned")) require(row.get("pinned") is Boolean)
                    if (row.has("copiedAt")) require(row.get("copiedAt") is Number)
                }
            }
            JSONObject().put(key, rows)
        } catch (e: Exception) { throw ContentReadException(e) }
    }

    private fun writeCategory(key: String, root: JSONObject) {
        if (key in privateFiles) privateFiles.getValue(key).write(array(root, key).toString())
        else write(root, writePrivateContent = false)
    }

    private fun read(): JSONObject {
        migratePrivateContent()
        return readLegacy().also { root ->
            // Bulk backup/restore includes phrases, but never reloads clipboard history.
            root.put("phrases", array(readCategory("phrases"), "phrases"))
        }
    }

    private val emojiDir = File(app.noBackupFilesDir, "recent-emoji")
    private val emojiFile = AtomicFile(File(emojiDir, "history.json"))
    private val phraseEpoch = phraseEpochs.computeIfAbsent(file.canonicalPath) { AtomicLong(0L) }
    fun phraseGeneration(): Long = phraseEpoch.get()
    private val emojiEpoch = emojiEpochs.computeIfAbsent(file.canonicalPath) { AtomicLong(0L) }
    fun emojiGeneration(): Long = emojiEpoch.get()
    private val aiPresetEpoch = aiPresetEpochs.computeIfAbsent(file.canonicalPath) { AtomicLong(0L) }
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
        val searches: MutableMap<DictionaryKind, io.secure.keyboard.engine.CustomDictionaryIndex<DictionaryEntry>> = mutableMapOf(),
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
            "本机内容无法读取，已停止保存以免覆盖原数据。请重试；如需重新使用，可在设置中清除用户内容数据。布局与设置不会受影响。",
            cause,
        )

    /** Interrupted writes are never authoritative; leave the current content file untouched. */
    fun cleanupTemporaryFiles() = synchronized(lock) {
        val temporary = File(dir, "content.tmp")
        if (temporary.exists()) deleteVerified(temporary)
    }

    private fun readLegacy(validatePrivate: Boolean = true): JSONObject {
        return try {
            if (!file.exists()) {
                require(!dir.exists() || dir.canRead()) { "Content directory is unreadable" }
                return JSONObject()
            }
            JSONObject(file.readText()).also { root ->
                for (key in listOf("clipboard", "phrases", "dictionary", "englishDictionary", "emoji", "aiPresets")) {
                    if (!root.has(key) || (!validatePrivate && key in privateFiles)) continue
                    val entries = root.getJSONArray(key)
                    for (index in 0 until entries.length()) {
                        if (key == "emoji") require(entries.get(index) is String)
                        else {
                            val row = entries.getJSONObject(index)
                            val isDictionary = DictionaryKind.entries.any { it.storageKey == key }
                            val fields =
                                if (key == "aiPresets") listOf("id", "name", "prompt")
                                else if (isDictionary) listOf("id", "word", "reading")
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

    private fun write(root: JSONObject, writePrivateContent: Boolean = true) {
        // A dictionary-only edit preserves any legacy private fields after a failed migration.
        // Only explicit private-content/restore operations may write encrypted categories.
        if (writePrivateContent) privateFiles.forEach { (key, target) ->
            if (root.has(key)) { target.write(root.getJSONArray(key).toString()); root.remove(key) }
        }
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
            if (writePrivateContent && emojiFile.baseFile.exists()) root.remove("emoji")
            val tmp = File(dir, "content.tmp")
            tmp.outputStream().use { output ->
                // Stream JSON to avoid a second full JSON string and UTF-8 byte array.
                val writer = JsonWriter(output.bufferedWriter(Charsets.UTF_8))
                writeJson(writer, root)
                writer.flush()
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
            // A failed write must not leave a second copy of the user's saved content.
            runCatching { cleanupTemporaryFiles() }
            invalidateDictionarySnapshots()
            throw error
        }
    }

    private fun writeJson(writer: JsonWriter, value: Any?) {
        when (value) {
            null, JSONObject.NULL -> writer.nullValue()
            is JSONObject -> {
                writer.beginObject()
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    writer.name(key)
                    writeJson(writer, value.get(key))
                }
                writer.endObject()
            }
            is JSONArray -> {
                writer.beginArray()
                for (index in 0 until value.length()) writeJson(writer, value.get(index))
                writer.endArray()
            }
            is String -> writer.value(value)
            is Boolean -> writer.value(value)
            is Number -> writer.value(value)
            else -> error("Unsupported content value")
        }
    }

    private fun array(root: JSONObject, key: String) = root.optJSONArray(key) ?: JSONArray()

    private fun objects(key: String): List<JSONObject> =
        synchronized(lock) {
            val values = array(readCategory(key), key)
            (0 until values.length()).map { values.getJSONObject(it) }
        }

    private fun mutate(key: String, operation: (MutableList<JSONObject>) -> Unit) =
        synchronized(lock) {
            DictionaryKind.entries.find { it.storageKey == key }?.let { invalidateDictionarySnapshot(it) }
            val root = readCategory(key)
            val values = array(root, key)
            val list = (0 until values.length()).map { values.getJSONObject(it) }.toMutableList()
            if (key == "clipboard") deduplicateClipboard(list)
            val oldCharacters = contentCharacters(list)
            val oldCount = list.size
            operation(list)
            if (key == "phrases") require(list.size <= maxOf(MAX_PHRASE_ENTRIES, oldCount)) {
                "常用语最多保存 10000 条，请删除部分条目后重试。"
            }
            // Legacy oversized data stays readable/editable: allow reductions and metadata edits.
            if (key != "clipboard") {
                val limit = when {
                    DictionaryKind.entries.any { it.storageKey == key } -> MAX_DICTIONARY_CHARACTERS
                    key == "aiPresets" -> MAX_AI_PRESET_CHARACTERS
                    else -> MAX_PHRASE_CHARACTERS
                }
                require(contentCharacters(list) <= maxOf(limit.toLong(), oldCharacters)) {
                    "本类内容已达到字符容量上限，请缩短内容或删除部分条目后重试。此次操作未保存。"
                }
            }
            root.put(key, JSONArray(list))
            writeCategory(key, root)
            if (key == "aiPresets") { aiPresetEpoch.incrementAndGet(); retentionPolicyEpoch.incrementAndGet() }
        }

    private fun contentCharacters(rows: List<JSONObject>): Long = rows.sumOf { row ->
        if (row.has("prompt")) row.getString("name").length.toLong() + row.getString("prompt").length
        else if (row.has("text")) row.getString("text").length.toLong()
        else row.getString("word").length.toLong() + row.getString("reading").length
    }

    fun clipboard(): List<ClipboardItem> = synchronized(lock) {
        trimClipboard()
        objects("clipboard").map {
            ClipboardItem(it.getString("id"), it.getString("text"), it.optBoolean("pinned"), it.optLong("copiedAt"))
        }
    }

    /** Exact text only: whitespace and line breaks are meaningful clipboard content. */
    private fun deduplicateClipboard(list: MutableList<JSONObject>) {
        val unique = linkedMapOf<String, JSONObject>()
        for (row in list) {
            val text = row.getString("text")
            val previous = unique.remove(text)
            if (previous?.optBoolean("pinned") == true) row.put("pinned", true)
            unique[text] = row
        }
        list.clear()
        list.addAll(unique.values)
    }

    /** Display grouping never changes the stored copy order used for bounded eviction. */
    fun clipboardForDisplay(): List<ClipboardItem> =
        clipboard().asReversed().sortedByDescending { it.pinned }

    fun setClipboardPinned(id: String, pinned: Boolean) =
        mutate("clipboard") { list ->
            if (pinned) require(list.count { it.optBoolean("pinned") } < 1000) { "最多置顶 1000 条记录" }
            list.find { it.optString("id") == id }?.put("pinned", pinned)
            pruneClipboard(list, System.currentTimeMillis())
        }

    /** Snapshot when an OS copy event is received, before queueing its storage work. */
    fun clipboardGeneration(): Long = clipboardEpoch.get()

    fun captureClipboard(
        text: String,
        copyToken: Long,
        newCopyEvent: Boolean = false,
        expectedGeneration: Long? = null,
    ): Boolean =
        synchronized(lock) {
            if (expectedGeneration != null && expectedGeneration != clipboardEpoch.get())
                return@synchronized false
            // A listener callback and an input-view reopen can observe the same OS copy.
            // Timestamp identity suppresses reprocessing; storage separately merges equal text.
            // Some devices expose no timestamp; only actual callbacks are usable there.
            if (
                text.isEmpty() || text.length > MAX_CLIPBOARD_ITEM_CHARACTERS ||
                    (copyToken <= 0L && !newCopyEvent) ||
                    (copyToken > 0L && copyToken == settings.lastClipboardToken)
            )
                return@synchronized false
            addClipboard(text)
            settings.lastClipboardToken = copyToken
            true
        }

    fun addClipboard(text: String) {
        require(text.length <= MAX_CLIPBOARD_ITEM_CHARACTERS) { "剪贴板单条最多保存 65536 个字符，原文仍可从系统剪贴板粘贴。" }
        if (text.isEmpty()) return
        mutate("clipboard") { list ->
            val now = System.currentTimeMillis()
            pruneClipboard(list, now)
            val existing = list.find { it.getString("text") == text }
            if (settings.clipboardRetention == "none" && existing?.optBoolean("pinned") != true) return@mutate
            if (existing != null) list.remove(existing)
            val row = (existing ?: item(text)).put("copiedAt", now)
            val pinned = row.optBoolean("pinned")
            while (list.any { !it.optBoolean("pinned") } &&
                ((!pinned && list.count { !it.optBoolean("pinned") } >= settings.clipboardLimit) ||
                    list.sumOf { it.getString("text").length.toLong() } + text.length > MAX_CLIPBOARD_CHARACTERS)) {
                list.removeAt(list.indexOfFirst { !it.optBoolean("pinned") })
            }
            // Automatic eviction never removes a pin. A full pinned budget skips new history.
            if (list.sumOf { it.getString("text").length.toLong() } + text.length <= MAX_CLIPBOARD_CHARACTERS)
                list.add(row)
        }
        scheduleClipboardCleanup()
    }

    private fun pruneClipboard(list: MutableList<JSONObject>, now: Long) {
        // Old records acquire an age once, so upgrading cannot erase an entire history.
        list.forEach { if (!it.has("copiedAt")) it.put("copiedAt", now) }
        val retention = settings.clipboardRetention
        val cutoff = now - settings.clipboardRetentionHours * 3_600_000L
        list.removeAll { !it.optBoolean("pinned") &&
            (retention == "none" || (retention == "timed" && it.optLong("copiedAt") <= cutoff)) }
        while (list.count { !it.optBoolean("pinned") } > settings.clipboardLimit)
            list.removeAt(list.indexOfFirst { !it.optBoolean("pinned") })
    }

    fun trimClipboard() = synchronized(lock) {
        val root = readCategory("clipboard")
        val rows = array(root, "clipboard")
        val list = (0 until rows.length()).map { rows.getJSONObject(it) }.toMutableList()
        val before = rows.toString()
        deduplicateClipboard(list)
        pruneClipboard(list, System.currentTimeMillis())
        val after = JSONArray(list)
        if (before != after.toString()) { root.put("clipboard", after); writeCategory("clipboard", root) }
        scheduleClipboardCleanup(list)
    }

    private fun scheduleClipboardCleanup(rows: List<JSONObject> = objects("clipboard")) {
        val oldest = rows.filterNot { it.optBoolean("pinned") }.minOfOrNull { it.optLong("copiedAt") }
        ClipboardCleanupJob.schedule(app, if (settings.clipboardRetention == "timed" && oldest != null)
            oldest + settings.clipboardRetentionHours * 3_600_000L else null)
    }

    fun deleteClipboard(id: String) =
        synchronized(lock) {
            clipboardEpoch.incrementAndGet()
            var removed: String? = null
            mutate("clipboard") { list ->
                removed = list.find { it.optString("id") == id }?.getString("text")
                list.removeAll { row -> row.optString("id") == id }
            }
            removed?.let { clearSystemClipboardIfMatching(setOf(it)) }
        }

    fun clearClipboard() =
        synchronized(lock) {
            clipboardEpoch.incrementAndGet()
            val removed = clipboard().map { it.text }.toSet()
            removeCategory("clipboard")
            suppressCurrentClipboard()
            clearSystemClipboardIfMatching(removed)
        }

    private fun clearSystemClipboardIfMatching(removed: Set<String>) {
        val manager = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        // Never coerce URIs or intents into text, or erase unrelated multi-item content.
        val clip = try { manager.primaryClip } catch (_: SecurityException) { null } ?: return
        if (!ClipboardCapturePolicy.matchesDeleted(clip, removed)) return
        suppressCurrentClipboard()
        try {
            if (Build.VERSION.SDK_INT >= 28) manager.clearPrimaryClip()
            else manager.setPrimaryClip(ClipData.newPlainText("", ""))
        } catch (_: SecurityException) { /* The OS can revoke clipboard access on focus loss. */ }
    }

    private fun suppressCurrentClipboard() {
        val manager = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val token =
            try {
                manager.primaryClipDescription?.timestamp ?: 0L
            } catch (_: Exception) {
                0L
            }
        if (token > 0L) {
            settings.lastClipboardToken = token
            settings.quickPasteConsumedToken = token
        }
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("clipboard_clear_revision", UUID.randomUUID().toString()).apply()
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
        mutate("phrases") { list ->
            checkNotNull(list.find { it.optString("id") == id }) { "常用语已被删除，请取消后重新添加" }.put("text", text)
        }

    /** Generation check and mutation share the same lock as clear/restore. */
    fun savePhrase(text: String, id: String?, expectedGeneration: Long): String = synchronized(lock) {
        check(expectedGeneration == phraseEpoch.get()) { "常用语数据已清理或还原，请取消后重新打开" }
        require(text.isNotBlank()) { "请输入常用语" }
        val savedId = id ?: UUID.randomUUID().toString()
        mutate("phrases") { list ->
            if (id == null) list.add(item(text).put("id", savedId))
            else checkNotNull(list.find { it.optString("id") == id }) { "常用语已被删除，请取消后重新添加" }.put("text", text)
        }
        savedId
    }

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

    /** Drop only rebuildable snapshots; revisions still protect in-flight candidate queries. */
    fun trimMemory() = synchronized(lock) {
        dictionaryCacheKeys.values.forEach { dictionarySnapshots.remove(it) }
    }

    fun dictionary(kind: DictionaryKind = DictionaryKind.CHINESE): List<DictionaryEntry> =
        synchronized(lock) {
            try {
                // Dictionary access must not depend on private-content encryption/migration.
                tryPrivateMigration()
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
                val values = array(readLegacy(validatePrivate = false), kind.storageKey)
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

    /** Shares the same invalidation and corruption checks as the saved-row snapshot. */
    fun matchingDictionary(query: String, kind: DictionaryKind): List<DictionaryEntry> = synchronized(lock) {
        val rows = dictionary(kind)
        val snapshot = dictionarySnapshots.getValue(dictionaryCacheKeys.getValue(kind))
        val index = snapshot.searches.getOrPut(kind) {
            io.secure.keyboard.engine.CustomDictionaryIndex(rows, { it.reading }, kind == DictionaryKind.ENGLISH)
        }
        index.matching(query)
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

    internal fun writeEncryptedBackup(output: java.io.OutputStream, password: CharArray) {
        val api = ApiConfigurationStore(app).load()
        SensitiveBackupBuffer().use { buffer ->
            val data = java.io.DataOutputStream(buffer)
            data.writeInt(0)
            writeBackup(buffer)
            buffer.setContentLength(buffer.size() - 4)
            ApiConfigurationCodec.write(data, api)
            buffer.encrypt(output, password)
        }
    }

    internal fun prepareEncryptedBackup(input: java.io.InputStream, password: CharArray): BackupPlan {
        val plain = PasswordBackup.decrypt(input, password)
        return try {
            java.io.DataInputStream(plain.inputStream()).use { data ->
                val length = data.readInt()
                require(length > 0 && length <= plain.size - 4)
                require(data.skipBytes(length) == length)
                val api = ApiConfigurationCodec.read(data)
                val plan = prepareBackup(java.io.ByteArrayInputStream(plain, 4, length))
                BackupPlan(plan.clean, plan.configuration,
                    plan.summary.replace("和 API 密钥不变", "不变") + "\nAI 设置的接口地址、密钥和模型配置将替换为备份中的内容。", api)
            }
        } finally { plain.fill(0) }
    }

    /** Only explicitly selected user content is exported, never credentials or conversations. */
    fun writeBackup(output: java.io.OutputStream) = synchronized(lock) {
        aiPresets() // Freeze legacy defaults before exporting.
        val root = read()
        val data = JSONObject()
        BACKUP_KEYS.forEach { data.put(it, array(root, it)) }
        val envelope = JSONObject().put("format", "skey-content-backup").put("version", 3)
            .put("data", data).put("settings", SettingsBackup(app).snapshot())
        val writer = JsonWriter(output.bufferedWriter(Charsets.UTF_8))
        writeJson(writer, envelope)
        writer.flush()
    }

    class BackupPlan internal constructor(internal val clean: JSONObject, internal val configuration: JSONObject?, val summary: String, internal val api: ApiConfiguration? = null)

    fun restoreBackup(input: java.io.InputStream) = restoreBackup(prepareBackup(input))

    fun prepareBackup(input: java.io.InputStream): BackupPlan {
        val backup = ContentBackupReader.read(input)
        require(backup.getString("format") == "skey-content-backup" && backup.getInt("version") in 1..3)
        val data = backup.getJSONObject("data")
        val clean = JSONObject()
        for (key in BACKUP_KEYS) {
            val rows = data.getJSONArray(key)
            val kind = DictionaryKind.entries.find { it.storageKey == key }
            require(rows.length() <= if (key == "aiPresets") 100 else 10_000)
            val ids = HashSet<String>(); val names = HashSet<String>()
            val checked = JSONArray(); var characters = 0L
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                fun string(name: String): String { require(row.get(name) is String); return row.getString(name) }
                val id = string("id")
                require(id.isNotBlank() && id.length <= 128 && ids.add(id))
                val item = JSONObject().put("id", id)
                when {
                    kind != null -> {
                        val word = string("word"); val reading = string("reading")
                        kind.validateEntry(word, reading)
                        item.put("word", word).put("reading", reading)
                        characters += word.length.toLong() + reading.length
                    }
                    key == "aiPresets" -> {
                        val name = string("name"); val prompt = string("prompt")
                        require(name.isNotBlank() && name.length <= 40 && prompt.isNotBlank() && prompt.length <= MAX_AI_PROMPT_CHARACTERS)
                        require(names.add(name.lowercase(java.util.Locale.ROOT)))
                        val effort = if (row.has("reasoningEffort")) string("reasoningEffort") else "low"
                        require(AiReasoning.valid(effort))
                        val model = io.secure.keyboard.ai.AiPromptStore.normalizeModel(if (row.has("modelId")) string("modelId") else "")
                        val hours = if (row.has("retentionHours")) {
                            val value = row.get("retentionHours")
                            require(value is Int || value is Long)
                            require((value as Number).toLong() in 0L..720L)
                            value.toInt()
                        } else settings.aiConversationHours
                        val webSearch = if (row.has("webSearch")) row.get("webSearch").also { require(it is Boolean) } as Boolean else false
                        val xSearch = if (row.has("xSearch")) row.get("xSearch").also { require(it is Boolean) } as Boolean else false
                        val provider = if (row.has("provider")) string("provider") else "default"
                        require(provider == "default" || AiProviders.valid(provider))
                        item.put("name", name).put("prompt", prompt).put("reasoningEffort", effort).put("modelId", model).put("retentionHours", hours).put("webSearch", webSearch).put("xSearch", xSearch).put("provider", provider)
                        characters += name.length.toLong() + prompt.length
                    }
                    else -> {
                        val value = string("text"); require(value.isNotBlank())
                        require(!row.has("pinned") || row.get("pinned") is Boolean)
                        item.put("text", value).put("pinned", row.optBoolean("pinned"))
                        characters += value.length
                    }
                }
                require(characters <= when {
                    kind != null -> MAX_DICTIONARY_CHARACTERS
                    key == "aiPresets" -> MAX_AI_PRESET_CHARACTERS
                    else -> MAX_PHRASE_CHARACTERS
                })
                checked.put(item)
            }
            clean.put(key, checked)
        }
        val configuration = backup.optJSONObject("settings")
        configuration?.let { SettingsBackup(app).validate(it) }
        val current = synchronized(lock) { read() }
        val names = mapOf("dictionary" to "中文自定义词库", "englishDictionary" to "英文自定义词库", "phrases" to "常用语", "aiPresets" to "自定义 AI")
        val lines = BACKUP_KEYS.map { "${names.getValue(it)}：${array(current,it).length()} → ${clean.getJSONArray(it).length()} 条（替换）" } +
            (configuration?.let { SettingsBackup(app).changes(it) } ?: listOf("旧版备份：不修改设置"))
        return BackupPlan(clean, configuration, lines.joinToString("\n") + "\n\n剪贴板、内置词库文件和 API 密钥不变。AI 会话将清空。")
    }

    fun restoreBackup(plan: BackupPlan) = synchronized(lock) {
        phraseEpoch.incrementAndGet()
        val root = read()
        val old = JSONObject(root.toString())
        val settingsBackup = SettingsBackup(app)
        val previous = settingsBackup.snapshot()
        val apiStore = ApiConfigurationStore(app)
        val previousApi = if (plan.api != null) apiStore.load() else null
        BACKUP_KEYS.forEach { root.put(it, plan.clean.getJSONArray(it)) }
        // Validation precedes every mutation; roll back both stores on an I/O failure.
        try {
            write(JSONObject(root.toString()))
            plan.configuration?.let(settingsBackup::apply)
            plan.api?.let(apiStore::saveDraft)
        } catch (failure: Exception) {
            // Attempt every rollback even if one store is still failing.
            listOf<() -> Unit>(
                { write(old) },
                { if (plan.configuration != null) settingsBackup.apply(previous) },
                { previousApi?.let(apiStore::saveDraft); Unit },
            ).forEach { rollback -> runCatching(rollback).exceptionOrNull()?.let(failure::addSuppressed) }
            throw failure
        }
        AiConversationStore(app).clear()
        DictionaryKind.entries.forEach { advanceDictionaryEpoch(it) }
        invalidateDictionarySnapshots()
        aiPresetEpoch.incrementAndGet(); retentionPolicyEpoch.incrementAndGet()
        settings.lastCustomAiPresetId = null
    }

    fun customAiEpoch(): Long = aiPresetEpoch.get()

    fun aiPresets(): List<AiPreset> = synchronized(lock) {
        var rows = objects("aiPresets")
        if (rows.any { !it.has("retentionHours") }) {
            val initial = settings.aiConversationHours
            mutate("aiPresets") { entries -> entries.filterNot { it.has("retentionHours") }.forEach { it.put("retentionHours", initial) } }
            rows = objects("aiPresets")
        }
        rows.map { AiPreset(it.getString("id"), it.getString("name"), it.getString("prompt"),
            it.optString("reasoningEffort", "low").takeIf(AiReasoning::valid) ?: "low", it.optString("modelId", ""),
            it.optInt("retentionHours", 4).coerceIn(0, 720), it.optBoolean("webSearch"), it.optBoolean("xSearch"), it.optString("provider", "default")) }
    }

    private var cachedRetentionEpoch = -1L
    private var cachedRetention = emptyMap<String, Int>()

    // Cache only IDs/durations (at most 100), never prompt bodies or conversation text.
    // Always acquire content before the conversation lock, including preset edits.
    internal fun <T> withAiRetentionPolicy(action: ((String) -> Int) -> T): T = synchronized(lock) {
        if (cachedRetentionEpoch != retentionPolicyEpoch.get()) {
            cachedRetention = aiPresets().associate { "custom:${it.id}" to it.retentionHours }
            cachedRetentionEpoch = retentionPolicyEpoch.get()
        }
        val custom = cachedRetention
        val default = settings.aiConversationHours
        action { type -> custom[type] ?: default }
    }

    fun setAiConversationHours(hours: Int) = synchronized(lock) {
        require(hours in 0..720)
        aiPresets() // Existing custom presets keep the value from before this change.
        if (settings.aiConversationHours == hours) return@synchronized
        val store = AiConversationStore(app)
        store.list() // Do not revive records that already expired under the old policy.
        check(app.getSharedPreferences("settings", 0).edit().putInt("aiConversationHours", hours).commit())
        store.refreshPolicy()
    }

    fun saveAiPreset(id: String?, name: String, prompt: String, reasoningEffort: String = "low", modelId: String = "", retentionHours: Int? = null, webSearch: Boolean = false, xSearch: Boolean = false, provider: String = "default") = synchronized(lock) {
        require(provider == "default" || AiProviders.valid(provider))
        aiPresets()
        AiConversationStore(app).list()
        var retentionChanged = false
        mutate("aiPresets") { rows ->
            val hours = retentionHours ?: rows.find { it.getString("id") == id }?.optInt("retentionHours") ?: settings.aiConversationHours
            require(hours in 0..720)
            retentionChanged = id != null && rows.find { it.getString("id") == id }?.optInt("retentionHours") != hours
            val title = name.trim(); val instruction = prompt.trim()
            val model = io.secure.keyboard.ai.AiPromptStore.normalizeModel(modelId)
            require(AiReasoning.valid(reasoningEffort)) { "请选择有效推理强度" }
            require(title.isNotBlank() && title.length <= 40) { "名称需为 1–40 个字符" }
            require(instruction.isNotBlank() && instruction.length <= MAX_AI_PROMPT_CHARACTERS) { "提示词需为 1–${MAX_AI_PROMPT_CHARACTERS} 个字符" }
            require(rows.none { it.getString("id") != id && it.getString("name").equals(title, ignoreCase = true) }) { "已有同名自定义 AI，请换个名称" }
            if (id == null) {
                require(rows.size < 100) { "最多保存 100 个自定义 AI" }
                rows.add(JSONObject().put("id", UUID.randomUUID().toString()).put("name", title).put("prompt", instruction).put("reasoningEffort", reasoningEffort).put("modelId", model).put("retentionHours", hours).put("webSearch", webSearch).put("xSearch", xSearch).put("provider", provider))
            } else {
                val row = rows.find { it.getString("id") == id }
                require(row != null) { "此自定义 AI 已删除，请重新添加" }
                if(row.getString("prompt")!=instruction || row.optString("reasoningEffort", "low")!=reasoningEffort || row.optString("modelId", "")!=model || row.optBoolean("webSearch")!=webSearch || row.optBoolean("xSearch")!=xSearch || row.optString("provider", "default")!=provider) {
                    val store=AiConversationStore(app)
                    store.removeType("custom:$id")
                }
                row.put("name", title).put("prompt", instruction).put("reasoningEffort", reasoningEffort).put("modelId", model).put("retentionHours", hours).put("webSearch", webSearch).put("xSearch", xSearch).put("provider", provider)
            }
        }
        if (retentionChanged) AiConversationStore(app).refreshPolicy()
    }

    fun deleteAiPreset(id: String) = mutate("aiPresets") { rows ->
        val store=AiConversationStore(app)
        store.removeType("custom:$id")
        rows.removeAll { it.getString("id") == id }
    }

    fun moveAiPreset(id: String, offset: Int) = mutate("aiPresets") { rows ->
        val index = rows.indexOfFirst { it.getString("id") == id }
        if (index >= 0 && offset in listOf(-1, 1)) {
            val target = (index + offset).coerceIn(0, rows.lastIndex)
            if (index != target) Collections.swap(rows, index, target)
        }
    }

    fun recentEmoji(): List<String> = synchronized(lock) {
        val rows = array(readCategory("emoji"), "emoji")
        (0 until rows.length()).map { rows.getString(it) }.distinct().take(100)
    }

    private fun writeEmoji(values: List<String>) {
        migratePrivateContent()
        privateFiles.getValue("emoji").write(JSONArray(values).toString())
    }

    /** A clear revokes writes already queued by any keyboard instance. */
    fun useEmoji(emoji: String, expectedGeneration: Long? = null) = synchronized(lock) {
        if (expectedGeneration != null && expectedGeneration != emojiEpoch.get()) return@synchronized
        val previous = recentEmoji()
        val values = (listOf(emoji) + previous.filter { it != emoji }).take(100)
        if (values != previous) writeEmoji(values)
    }

    private fun removeCategory(key: String) =
        synchronized(lock) {
            DictionaryKind.entries.find { it.storageKey == key }?.let { invalidateDictionarySnapshot(it) }
            val root = readCategory(key)
            root.remove(key)
            writeCategory(key, root)
            purgeCaches()
        }

    fun clearPhrases() = synchronized(lock) { phraseEpoch.incrementAndGet(); removeCategory("phrases") }

    fun clearDictionary(kind: DictionaryKind = DictionaryKind.CHINESE) =
        synchronized(lock) {
            advanceDictionaryEpoch(kind)
            removeCategory(kind.storageKey)
        }

    fun clearEmoji() = synchronized(lock) {
        emojiEpoch.incrementAndGet()
        writeEmoji(emptyList())
        purgeCaches()
    }

    fun clearAll() =
        synchronized(lock) {
            phraseEpoch.incrementAndGet()
            clipboardEpoch.incrementAndGet()
            emojiEpoch.incrementAndGet()
            // Explicit full erasure must also recover a corrupt content file.
            val removedClips = try { clipboard().map { it.text }.toSet() }
                catch (_: ContentReadException) { emptySet() }
            aiPresetEpoch.incrementAndGet(); retentionPolicyEpoch.incrementAndGet()
            AiConversationStore(app).clear()
            settings.lastCustomAiPresetId = null
            DictionaryKind.entries.forEach { advanceDictionaryEpoch(it) }
            invalidateDictionarySnapshots()
            suppressCurrentClipboard()
            filesIn(dir).forEach { deleteVerified(it) }
            deleteVerified(emojiDir)
            deleteVerified(privateDir)
            clearSystemClipboardIfMatching(removedClips)
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
        private val BACKUP_KEYS = listOf("dictionary", "englishDictionary", "phrases", "aiPresets")
        const val MAX_CLIPBOARD_ITEM_CHARACTERS = 65_536
        const val MAX_CLIPBOARD_CHARACTERS = 1_000_000
        const val MAX_PHRASE_ENTRIES = 10_000
        const val MAX_AI_PROMPT_CHARACTERS = 20_000
        private const val MAX_AI_PRESET_CHARACTERS = 100 * (MAX_AI_PROMPT_CHARACTERS + 40)
        const val MAX_PHRASE_CHARACTERS = 1_000_000
        const val MAX_DICTIONARY_CHARACTERS = 4_000_000
        const val MAX_DICTIONARY_ENTRIES = 10_000
        private const val DICTIONARY_LIMIT_MESSAGE = "用户词库最多保存 10000 条。此次操作未写入任何词条，请减少导入数量或先删除部分词条。"
        private val phraseEpochs = ConcurrentHashMap<String, AtomicLong>()
        private val emojiEpochs = ConcurrentHashMap<String, AtomicLong>()
        private val retentionPolicyEpoch = AtomicLong(0L)
        private val aiPresetEpochs = ConcurrentHashMap<String, AtomicLong>()
        private val lock = Any()
        private val clipboardEpoch = AtomicLong(0L)
        private val dictionaryEpochs = mutableMapOf<DictionaryCacheKey, Long>()
        private val dictionarySnapshots = mutableMapOf<DictionaryCacheKey, DictionarySnapshot>()
        private val dictionaryRevisions = ConcurrentHashMap<DictionaryCacheKey, AtomicLong>()
    }
}
