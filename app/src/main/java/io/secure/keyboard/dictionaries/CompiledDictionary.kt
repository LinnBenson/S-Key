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
            openFiles[path] = (openFiles[path] ?: 0) + 1
            opened
        } catch (error: Throwable) { opened.close(); throw error }
    }
    init { db.execSQL("PRAGMA cache_size=-2048") }
    private fun count(kind: String) = db.rawQuery("SELECT count(*) FROM words WHERE kind=?", arrayOf(kind)).use { it.moveToFirst(); it.getInt(0) }
    private fun rows(sql: String, args: Array<String>): List<DictionaryEngine.Entry> = db.rawQuery(sql,args).use { c ->
        buildList { while(c.moveToNext()) add(DictionaryEngine.Entry(c.getString(0), c.getString(1), c.getLong(2))) }
    }
    val wubi = object : WubiLookup {
        override val entryCount = count("wubi")
        override fun exactCandidateCount(code: String) = db.rawQuery("SELECT count(*) FROM words WHERE kind='wubi' AND compact=?", arrayOf(code)).use { it.moveToFirst(); it.getInt(0) }
        override fun exact(code: String, limit: Int) = if (limit <= 0) emptyList() else rows(
            "SELECT text,code,weight FROM words WHERE kind='wubi' AND compact=? ORDER BY priority,weight DESC,ordinal LIMIT ?", arrayOf(code,limit.toString())
        ).map { Candidate(it.text,it.code,Source.WUBI) }
        override fun prefix(code: String, limit: Int) = if (limit <= 0 || code.isEmpty() || code.length>=4) emptyList() else rows(
            "SELECT text,code,weight FROM words WHERE kind='wubi' AND compact>? AND compact<? ORDER BY weight DESC,ordinal LIMIT ?", arrayOf(code,code+"{",minOf(limit,30).toString())
        ).map { Candidate(it.text,it.code,Source.WUBI) }
    }
    val pinyin = object : PinyinLookup {
        override val entryCount = count("pinyin")
        override fun index(kind: String): Map<String,List<DictionaryEngine.Entry>> {
            val column = when(kind) { "exact" -> "compact"; "fuzzy" -> "fuzzy"; "syllable" -> "syllable"; else -> error("Unknown index") }
            return object : AbstractMap<String,List<DictionaryEngine.Entry>>() {
                override val entries: Set<Map.Entry<String,List<DictionaryEngine.Entry>>> get() = error("Query-only index")
                override fun get(key: String): List<DictionaryEngine.Entry>? = rows(
                    "SELECT text,code,weight FROM words WHERE kind='pinyin' AND $column=? ORDER BY weight DESC,ordinal", arrayOf(key)
                ).takeIf { it.isNotEmpty() }
            }
        }
    }
    val english = object : EnglishLookup {
        override fun complete(prefix: String, limit: Int): List<String> {
            if (prefix.isEmpty() || prefix.length>40 || limit<=0 || prefix.any { it !in 'a'..'z' && it !in 'A'..'Z' }) return emptyList()
            val key=prefix.lowercase(Locale.ROOT)
            val caps=prefix.length>1 && prefix.all { it in 'A'..'Z' }
            return rows("SELECT text,code,weight FROM words WHERE kind='english' AND compact>? AND compact<? ORDER BY weight DESC,text LIMIT ?", arrayOf(key,key+"{",limit.toString())).map {
                if(caps) it.text.uppercase(Locale.ROOT) else prefix+it.text.substring(prefix.length)
            }
        }
    }
    val engine = DictionaryEngine(wubi,pinyin,english)
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
        internal fun deleteIfUnused(file: File) = synchronized(openFiles) {
            if (file.absolutePath !in openFiles) SQLiteDatabase.deleteDatabase(file) else false
        }
    }
}
