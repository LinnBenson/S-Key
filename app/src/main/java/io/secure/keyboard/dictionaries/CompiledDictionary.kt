package io.secure.keyboard.dictionaries

import android.database.sqlite.SQLiteDatabase
import io.secure.keyboard.engine.*
import java.io.File
import java.util.Locale

/** Immutable indexed snapshot. SQLite's bounded page cache avoids retaining every word on heap. */
class CompiledDictionary(file: File) : AutoCloseable {
    private val path = file.absolutePath
    private val db = synchronized(openFiles) {
        val opened = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            opened.rawQuery("SELECT text,code,weight,kind,compact,fuzzy,syllable,priority,ordinal FROM words LIMIT 0", null).use { }
            val version = opened.rawQuery("PRAGMA user_version", null).use { it.moveToFirst(); it.getInt(0) }
            require(version in 2..3) { "词库索引版本不受支持，请重新编译" }
            if (version == 3) opened.rawQuery("SELECT kind,prefix,rank,word_id FROM short_prefixes LIMIT 0", null).use { }
            opened.execSQL("PRAGMA cache_size=-2048")
            openFiles[path] = (openFiles[path] ?: 0) + 1
            opened
        } catch (error: Throwable) { opened.close(); throw error }
    }
    private val indexedPrefixes = db.rawQuery("PRAGMA user_version",null).use { it.moveToFirst(); it.getInt(0)>=3 }
    private fun shortPrefix(kind: String, code: String, limit: Int) = rows(
        "SELECT w.text,w.code,w.weight FROM short_prefixes p JOIN words w ON w.rowid=p.word_id WHERE p.kind=? AND p.prefix=? ORDER BY p.rank LIMIT ?",
        arrayOf(kind,code,limit.toString())
    )
    private fun count(kind: String) = db.rawQuery("SELECT count(*) FROM words WHERE kind=?", arrayOf(kind)).use { it.moveToFirst(); it.getInt(0) }
    private fun rows(sql: String, args: Array<String>): List<DictionaryEngine.Entry> = db.rawQuery(sql,args).use { c ->
        buildList { while(c.moveToNext()) add(DictionaryEngine.Entry(c.getString(0), c.getString(1), c.getLong(2), if(c.columnCount>3) c.getLong(3) else Long.MAX_VALUE)) }
    }
    val wubi = object : WubiLookup {
        override val entryCount = count("wubi")
        override fun exactCandidateCount(code: String) = db.rawQuery("SELECT count(*) FROM words WHERE kind='wubi' AND compact=?", arrayOf(code)).use { it.moveToFirst(); it.getInt(0) }
        override fun exact(code: String, limit: Int) = if (limit <= 0) emptyList() else rows(
            "SELECT text,code,weight FROM words WHERE kind='wubi' AND compact=? ORDER BY priority,weight DESC,ordinal LIMIT ?", arrayOf(code,limit.toString())
        ).map { Candidate(it.text,it.code,Source.WUBI) }
        override fun prefix(code: String, limit: Int) = if (limit <= 0 || code.isEmpty() || code.length>=4) emptyList() else (if (indexedPrefixes && code.length<=2) shortPrefix("wubi",code,minOf(limit,30)) else rows(
            "SELECT text,code,weight FROM words WHERE kind='wubi' AND compact>? AND compact<? ORDER BY weight DESC,ordinal LIMIT ?", arrayOf(code,code+"{",minOf(limit,30).toString())
        )).map { Candidate(it.text,it.code,Source.WUBI) }
    }
    val scopedPinyin = db.rawQuery("PRAGMA user_version",null).use { it.moveToFirst(); it.getInt(0)>=2 }
    private fun pinyinIndex(namespace: String) = object : PinyinLookup {
        override val entryCount = count(namespace)
        override fun lookup(kind: String, key: String, limit: Int) = lookupPage(kind,key,0,limit)
        override fun lookupPage(kind: String, key: String, offset: Int, limit: Int): List<DictionaryEngine.Entry> {
            if (limit<=0) return emptyList()
            val column = when(kind) { "exact" -> "compact"; "syllable" -> "syllable"; else -> "fuzzy" }
            val ranking = if(kind=="exact") "priority,weight DESC,ordinal" else "weight DESC,ordinal"
            val columns = if(kind=="exact") "text,code,weight,priority" else "text,code,weight"
            return rows("SELECT $columns FROM words WHERE kind='$namespace' AND kind IN ('pinyin','mixed_pinyin','pinyin_initial') AND $column=? ORDER BY $ranking LIMIT ? OFFSET ?", arrayOf(key, limit.toString(),offset.toString()))
        }
        override fun predictions(context: String): List<String> {
            for (length in minOf(context.length, 8) downTo 1) {
                val prefix = context.takeLast(length)
                if (prefix.any { !Character.isIdeographic(it.code) }) continue
                val results = db.rawQuery("SELECT text FROM words WHERE kind='$namespace' AND kind IN ('pinyin','mixed_pinyin') AND text>? AND text<? ORDER BY weight DESC LIMIT 12", arrayOf(prefix, prefix + "\uffff")).use { c ->
                    buildList { while(c.moveToNext()) c.getString(0).removePrefix(prefix).takeIf { it.isNotEmpty() }?.let { add(it) } }
                }
                if (results.isNotEmpty()) return results
            }
            return emptyList()
        }
        override fun index(kind: String): Map<String,List<DictionaryEngine.Entry>> {
            val column = when(kind) { "exact" -> "compact"; "fuzzy" -> "fuzzy"; "syllable" -> "syllable"; else -> error("Unknown index") }
            val ranking = if(kind=="exact") "priority,weight DESC,ordinal" else "weight DESC,ordinal"
            val columns = if(kind=="exact") "text,code,weight,priority" else "text,code,weight"
            return object : AbstractMap<String,List<DictionaryEngine.Entry>>() {
                override val entries: Set<Map.Entry<String,List<DictionaryEngine.Entry>>> get() = error("Query-only index")
                override fun get(key: String): List<DictionaryEngine.Entry>? = rows(
                    "SELECT $columns FROM words WHERE kind='$namespace' AND kind IN ('pinyin','mixed_pinyin','pinyin_initial') AND $column=? ORDER BY $ranking", arrayOf(key)
                ).takeIf { it.isNotEmpty() }
            }
        }
    }
    val pinyin = pinyinIndex("pinyin")
    val mixedPinyin = pinyinIndex("mixed_pinyin")
    fun initials(code: String) = if(code.length != 1) emptyList() else rows(
        "SELECT text,code,weight FROM words WHERE kind='pinyin_initial' AND kind IN ('pinyin','mixed_pinyin','pinyin_initial') AND compact=? ORDER BY weight DESC,ordinal", arrayOf(code))
    val english = object : EnglishLookup {
        override fun complete(prefix: String, limit: Int): List<String> {
            if (prefix.isEmpty() || prefix.length>40 || limit<=0 || prefix.any { it !in 'a'..'z' && it !in 'A'..'Z' }) return emptyList()
            val key=prefix.lowercase(Locale.ROOT)
            val caps=prefix.length>1 && prefix.all { it in 'A'..'Z' }
            val exact = rows("SELECT text,code,weight FROM words WHERE kind='english' AND compact=? AND priority<9223372036854775807 ORDER BY priority,weight DESC,ordinal LIMIT ?",arrayOf(key,limit.toString()))
            val remaining = limit-exact.size
            val extensions = if(remaining<=0) emptyList() else if (indexedPrefixes && key.length<=2 && remaining<=12) shortPrefix("english",key,remaining) else rows("SELECT text,code,weight FROM words WHERE kind='english' AND compact>? AND compact<? ORDER BY weight DESC,text LIMIT ?", arrayOf(key,key+"{",remaining.toString()))
            return (exact+extensions).map {
                if(caps) it.text.uppercase(Locale.ROOT) else prefix+it.text.substring(prefix.length)
            }
        }
    }
    fun exactWubiPage(code: String, offset: Int, limit: Int) = rows(
        "SELECT text,code,weight FROM words WHERE kind='wubi' AND compact=? ORDER BY priority,weight DESC,ordinal LIMIT ? OFFSET ?",
        arrayOf(code,limit.toString(),offset.toString())).map { it.text }

    fun initialsPage(code: String, offset: Int, limit: Int) = rows(
        "SELECT text,code,weight FROM words WHERE kind='pinyin_initial' AND kind IN ('pinyin','mixed_pinyin','pinyin_initial') AND compact=? ORDER BY weight DESC,ordinal LIMIT ? OFFSET ?",
        arrayOf(code,limit.toString(),offset.toString())).map { it.text }

    fun pinyinPage(raw: String, offset: Int, limit: Int, priorityOnly: Boolean = false): List<String> {
        val key=raw.replace("'", "").replace(" ", "")
        if(key.isEmpty()) return emptyList()
        val segmented=raw.contains("'") || raw.contains(" ")
        val filter=(if(segmented) " AND code=?" else "") + if(priorityOnly) " AND priority<9223372036854775807" else ""
        val args=mutableListOf(key)
        if(segmented) args.add(raw.split('\'', ' ').filter { it.isNotEmpty() }.joinToString(" "))
        args.add(limit.toString());args.add(offset.toString())
        return rows("SELECT text,code,weight FROM words WHERE kind='pinyin' AND kind IN ('pinyin','mixed_pinyin','pinyin_initial') AND compact=?$filter ORDER BY priority,weight DESC,ordinal LIMIT ? OFFSET ?",args.toTypedArray()).map { it.text }
    }

    fun englishPage(prefix: String, offset: Int, limit: Int): List<String> {
        if(prefix.isEmpty() || prefix.length>40 || prefix.any { it !in 'a'..'z' && it !in 'A'..'Z' }) return emptyList()
        val key=prefix.lowercase(Locale.ROOT)
        val exactCount=db.rawQuery("SELECT count(*) FROM words WHERE kind='english' AND compact=? AND priority<9223372036854775807",arrayOf(key)).use { it.moveToFirst();it.getInt(0) }
        val first=if(offset>=exactCount) emptyList() else rows("SELECT text,code,weight FROM words WHERE kind='english' AND compact=? AND priority<9223372036854775807 ORDER BY priority,weight DESC,ordinal LIMIT ? OFFSET ?",arrayOf(key,limit.toString(),offset.toString()))
        val rest=if(first.size==limit) emptyList() else rows(
            "SELECT text,code,weight FROM words WHERE kind='english' AND compact>? AND compact<? ORDER BY weight DESC,text LIMIT ? OFFSET ?",
            arrayOf(key,key+"{",(limit-first.size).toString(),(offset-exactCount).coerceAtLeast(0).toString()))
        val caps=prefix.length>1 && prefix.all { it in 'A'..'Z' }
        return (first+rest).map { if(caps) it.text.uppercase(Locale.ROOT) else prefix+it.text.substring(prefix.length) }
    }

    val engine = DictionaryEngine(wubi,pinyin,english,::initials)
    val mixedEngine = DictionaryEngine(wubi,mixedPinyin,english)
    private var closed = false
    override fun close() = synchronized(openFiles) {
        if (!closed) {
            db.close()
            closed = true
            val remaining = (openFiles[path] ?: 1) - 1
            if (remaining == 0) openFiles.remove(path) else openFiles[path] = remaining
        }
    }
    companion object {
        private val openFiles = mutableMapOf<String, Int>()
        internal fun openSnapshotPaths(): Set<String> = synchronized(openFiles) { openFiles.keys.toSet() }
        internal fun deleteIfUnused(file: File) = synchronized(openFiles) {
            if (file.absolutePath !in openFiles) SQLiteDatabase.deleteDatabase(file) else false
        }
    }
}
