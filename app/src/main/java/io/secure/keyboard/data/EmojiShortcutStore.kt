package io.secure.keyboard.data

import android.content.Context
import io.secure.keyboard.engine.EmojiLexicon
import org.json.JSONArray
import org.json.JSONObject

/** A complete override, including an explicitly empty list, preserves user deletions. */
class EmojiShortcutStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("settings", 0)
    private var revisionRaw: String? = null
    private var revisionToken = "default"
    val revision: String @Synchronized get() {
        val raw = prefs.getString(KEY, null)
        if (raw != revisionRaw) {
            revisionToken = raw?.let { java.security.MessageDigest.getInstance("SHA-256")
                .digest(it.toByteArray(Charsets.UTF_8)).joinToString("") { byte -> "%02x".format(byte) } } ?: "default"
            revisionRaw = raw
        }
        return revisionToken
    }
    private val defaults by lazy {
        app.assets.open("dictionaries/emoji_keywords.tsv").bufferedReader().useLines { lines ->
            lines.filter(String::isNotBlank).map { line ->
                val fields = line.split('\t'); Entry(fields.first(), fields.drop(1))
            }.toList()
        }
    }
    data class Entry(val emoji: String, val keywords: List<String>)
    fun entries(): List<Entry> = prefs.getString(KEY, null)?.let(::decode) ?: defaults
    fun save(original: String?, emoji: String, keywords: String) {
        val entry = Entry(emoji.trim(), keywords.split(',').map(String::trim).filter(String::isNotEmpty).distinct())
        validate(entry)
        val rows = entries().toMutableList()
        require(rows.none { it.emoji == entry.emoji && it.emoji != original }) { "此表情已存在，请编辑原有关键词" }
        val index = rows.indexOfFirst { it.emoji == original }
        require(original == null || index >= 0) { "此快捷表情已删除，请重新添加" }
        if (index >= 0) rows[index] = entry else rows.add(entry)
        write(rows)
    }
    fun reset() { prefs.edit().remove(KEY).apply() }
    fun delete(emoji: String) = write(entries().filterNot { it.emoji == emoji })
    private fun write(rows: List<Entry>) {
        require(rows.size <= 1000) { "最多保存 1000 个快捷表情" }
        val raw = JSONArray().also { array -> rows.forEach { array.put(JSONObject().put("emoji", it.emoji).put("keywords", JSONArray(it.keywords))) } }.toString()
        require(raw.length <= 100_000) { "快捷表情内容过多" }
        prefs.edit().putString(KEY, raw).apply()
    }
    private var cachedRaw: String? = null
    private var cachedLexicon: EmojiLexicon? = null
    @Synchronized fun lexicon(): EmojiLexicon {
        val raw = revision
        if (cachedLexicon == null || cachedRaw != raw) {
            // A damaged optional shortcut configuration must never disable normal typing.
            // Keep the original preference untouched so the settings page can report it.
            cachedLexicon = try {
                EmojiLexicon(entries().joinToString("\n") { it.emoji + "\t" + it.keywords.joinToString("\t") }.reader())
            } catch (_: Exception) { EmojiLexicon("".reader()) }
            cachedRaw = raw
        }
        return checkNotNull(cachedLexicon)
    }
    companion object {
        const val KEY = "emojiShortcuts"
        private fun validate(row: Entry) {
            require(row.emoji.isNotBlank() && row.emoji.length <= 64 && row.emoji.none { it.isWhitespace() || it.isISOControl() }) { "请输入有效表情（最多 64 个字符）" }
            require(row.keywords.isNotEmpty() && row.keywords.size <= 32 && row.keywords.distinct().size == row.keywords.size) { "请输入关键词，多个关键词用 , 分隔（最多 32 个）" }
            require(row.keywords.all { it.isNotBlank() && it.length <= 64 && it == it.trim() && it.none { c -> c.isISOControl() || c == ',' } }) { "每个关键词最多 64 个字符，不能包含控制字符" }
        }
        internal fun decode(raw: String): List<Entry> {
            require(raw.length <= 100_000)
            val array = JSONArray(raw); require(array.length() <= 1000)
            val rows = (0 until array.length()).map { index ->
                val row = array.getJSONObject(index)
                require(row.keys().asSequence().toSet() == setOf("emoji", "keywords") && row.get("emoji") is String)
                val words = row.getJSONArray("keywords")
                Entry(row.getString("emoji"), (0 until words.length()).map { require(words.get(it) is String); words.getString(it) }).also(::validate)
            }
            require(rows.map { it.emoji }.distinct().size == rows.size)
            return rows
        }
    }
}
