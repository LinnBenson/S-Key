package io.secure.keyboard.data

import android.content.Context
import android.util.AtomicFile
import io.secure.keyboard.ai.AiConversation
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/** Encrypted local continuation list; excluded from backups. No API secrets or full transcript. */
internal class AiConversationStore(context: Context) {
    private val app = context.applicationContext
    private val content by lazy { ContentStore(app) }
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "ai_conversation.enc"))
    private data class State(val entries: List<AiConversation>, val selected: String?)
    private fun decode(j: JSONObject): AiConversation = AiConversation(
        j.getString("id"), j.getString("scope"), j.getLong("updatedAt"), j.getString("type"),
        j.optString("key", j.getString("id")), j.optLong("startedAt", j.getLong("updatedAt")),
        j.optString("firstMessage"), j.optString("title", j.getString("type")), j.optString("tone", "自然"),
        j.optString("presetName"), j.optString("prompt"), j.optString("reasoning", "low"), j.optString("model"), j.optInt("retentionHours", 4), j.optBoolean("webSearch"), j.optBoolean("xSearch"),
    )
    private fun read(hours: (String) -> Int): State {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return State(emptyList(), null)
        return try {
            val bytes = file.openRead().use { input ->
                check(input.channel.size() in 29..64_000_000)
                input.readBytes()
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, StorageKeys.get("skey_ai_conversation"), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            val plain = cipher.doFinal(bytes, 12, bytes.size - 12)
            try {
                // Stream legacy arrays into the new budget rather than constructing a giant JSON tree.
                var decodedCount = 0
                val state = android.util.JsonReader(java.io.InputStreamReader(java.io.ByteArrayInputStream(plain), Charsets.UTF_8)).use { reader ->
                    val legacy = JSONObject()
                    var entries: List<AiConversation>? = null
                    var selected: String? = null
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (val name = reader.nextName()) {
                            "entries" -> {
                                val retained = ArrayList<Pair<AiConversation, Int>>()
                                var retainedBytes = 512
                                reader.beginArray()
                                while (reader.hasNext()) {
                                    val entry = JSONObject()
                                    reader.beginObject()
                                    while (reader.hasNext()) readField(reader, entry, reader.nextName())
                                    reader.endObject()
                                    decodedCount++
                                    val value = decode(entry)
                                    val size = entryBytes(value)
                                    if (size != null && value.copy(retentionHours = hours(value.type)).valid(System.currentTimeMillis())) {
                                        retained.add(value to size); retainedBytes += size
                                        while (retained.size > MAX_ENTRIES || retainedBytes > MAX_PLAIN_BYTES) {
                                            val oldest = retained.minBy { it.first.updatedAt }
                                            retained.remove(oldest); retainedBytes -= oldest.second
                                        }
                                    }
                                }
                                reader.endArray()
                                entries = retained.map { it.first }
                            }
                            "selected" -> selected = reader.nextString().takeIf { it.isNotEmpty() }
                            else -> readField(reader, legacy, name)
                        }
                    }
                    reader.endObject()
                    entries?.let { State(it, selected) } ?: decode(legacy).let { decodedCount = 1; State(listOf(it), it.key) }
                }
                val valid = state.entries.map { it.copy(retentionHours = hours(it.type)) }.filter { it.valid(System.currentTimeMillis()) }.sortedByDescending { it.updatedAt }
                val clean = bounded(State(valid, state.selected?.takeIf { key -> valid.any { it.key == key } }))
                if (clean != state || decodedCount != clean.entries.size) { revision.incrementAndGet(); runCatching { write(clean) } }
                schedule(clean)
                clean
            } finally { plain.fill(0); bytes.fill(0) }
        } catch (_: Exception) { revision.incrementAndGet(); file.delete(); State(emptyList(), null) }
    }
    private fun readField(reader: android.util.JsonReader, target: JSONObject, name: String) {
        when (reader.peek()) {
            android.util.JsonToken.STRING -> {
                val value = reader.nextString()
                check(value.length <= 40_000) { "会话字段过大" }
                target.put(name, value)
            }
            android.util.JsonToken.NUMBER -> target.put(name, reader.nextLong())
            android.util.JsonToken.BOOLEAN -> target.put(name, reader.nextBoolean())
            else -> reader.skipValue()
        }
    }
    private fun encode(v: AiConversation): JSONObject = JSONObject()
        .put("id", v.id).put("scope", v.scope).put("updatedAt", v.updatedAt).put("type", v.type)
        .put("key", v.key).put("startedAt", v.startedAt).put("firstMessage", v.firstMessage)
        .put("title", v.title).put("tone", v.tone).put("presetName", v.presetName)
        .put("prompt", v.prompt).put("reasoning", v.reasoning).put("model", v.model)
        .put("retentionHours", v.retentionHours).put("webSearch", v.webSearch).put("xSearch", v.xSearch)

    /** Bound individual records and UTF-8 JSON before building a complete array/string. */
    private fun bounded(state: State): State {
        val kept = ArrayList<AiConversation>()
        var bytes = 512 // envelope, selected key, separators
        for (entry in state.entries.sortedByDescending { it.updatedAt }) {
            if (kept.size == MAX_ENTRIES) break
            val size = entryBytes(entry) ?: continue
            if (bytes + size > MAX_PLAIN_BYTES) break
            kept.add(entry); bytes += size
        }
        return State(kept, state.selected?.takeIf { key -> kept.any { it.key == key } })
    }
    private fun entryBytes(entry: AiConversation): Int? {
        val fields = listOf(entry.id, entry.scope, entry.type, entry.key, entry.firstMessage,
            entry.title, entry.tone, entry.presetName, entry.prompt, entry.reasoning, entry.model)
        if (!entry.key.matches(Regex("[A-Za-z0-9_-]{1,256}")) || fields.sumOf { it.length.toLong() } > 40_000) return null
        return encode(entry).toString().toByteArray(Charsets.UTF_8).size + 1
    }
    private fun schedule(state: State) {
        // Scheduling failure must never turn a readable encrypted file into data loss.
        runCatching { AiConversationCleanupJob.schedule(app, state.entries.minOfOrNull { it.updatedAt + it.retentionMillis }) }
    }

    private fun write(state: State) {
        if (state.entries.isEmpty()) { file.delete(); schedule(state); return }
        val entries = JSONArray()
        state.entries.forEach { entries.put(encode(it)) }
        val plain = JSONObject().put("entries", entries).put("selected", state.selected.orEmpty()).toString().toByteArray(Charsets.UTF_8)
        try {
            check(plain.size <= MAX_PLAIN_BYTES) { "会话列表过大" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, StorageKeys.get("skey_ai_conversation"))
            val out = file.startWrite()
            try { out.write(cipher.iv); out.write(cipher.doFinal(plain)); file.finishWrite(out); schedule(state) }
            catch (e: Exception) { file.failWrite(out); throw e }
        } finally { plain.fill(0) }
    }
    private fun <T> withPolicy(action: ((String) -> Int) -> T): T =
        content.withAiRetentionPolicy { hours -> synchronized(lock) { action(hours) } }

    fun list(): List<AiConversation> = withPolicy { read(it).entries }
    fun load(): AiConversation? = snapshot().first
    fun snapshot(): Pair<AiConversation?, Long> = withPolicy { hours ->
        val state = read(hours)
        state.entries.firstOrNull { it.key == state.selected } to revision.get()
    }
    fun save(value: AiConversation, expectedRevision: Long? = null, isCurrent: () -> Boolean = { true }): AiConversation? = withPolicy { hours ->
        val state = read(hours)
        check(expectedRevision == null || expectedRevision == revision.get()) { "会话已重置" }
        check(isCurrent()) { "会话已关闭" }
        val current = value.copy(retentionHours = hours(value.type))
        if (!current.valid(System.currentTimeMillis())) {
            write(State(state.entries.filterNot { it.key == current.key }, state.selected?.takeUnless { it == current.key }))
            return@withPolicy null
        }
        val next = bounded(State(state.entries.filterNot { it.key == current.key } + current, current.key))
        check(isCurrent()) { "会话已关闭" }
        write(next)
        next.entries.firstOrNull { it.key == current.key }
    }
    fun select(key: String?) = withPolicy { hours ->
        val state = read(hours)
        check(key == null || state.entries.any { it.key == key }) { "会话已过期" }
        write(state.copy(selected = key)); revision.incrementAndGet()
    }
    fun remove(key: String?) = withPolicy { hours ->
        val state = read(hours)
        val target = key ?: state.selected
        write(State(state.entries.filterNot { it.key == target }, state.selected?.takeUnless { it == target }))
        revision.incrementAndGet()
    }
    fun removeType(type: String) = withPolicy { hours ->
        val state = read(hours)
        val kept = state.entries.filterNot { it.type == type }
        if (kept.size != state.entries.size) {
            write(State(kept, state.selected?.takeIf { key -> kept.any { it.key == key } }))
        }
        revision.incrementAndGet()
    }
    fun refreshPolicy() = withPolicy { hours -> read(hours); revision.incrementAndGet() }
    fun revision(): Long = revision.get()
    fun clearAsync() {
        revision.incrementAndGet()
        ConversationIo.executor.execute { clear() }
    }
    fun clear() = synchronized(lock) { revision.incrementAndGet(); file.delete(); schedule(State(emptyList(), null)) }
    companion object { private val lock = Any(); private val revision = java.util.concurrent.atomic.AtomicLong()
        const val MAX_ENTRIES = 100
        const val MAX_PLAIN_BYTES = 2 * 1024 * 1024 }
}
