package io.secure.keyboard.dictionaries

import android.database.sqlite.SQLiteDatabase
import io.secure.keyboard.engine.DictionaryEngine
import java.io.File

/** Builds an independent file; the caller publishes only after validation. Never mutates live data. */
object DictionaryCompiler {
    fun compile(source: SQLiteDatabase, output: File, progress: (String)->Unit = {}) {
        check(!output.exists())
        val db=SQLiteDatabase.openOrCreateDatabase(output,null)
        try {
            db.rawQuery("PRAGMA journal_mode=DELETE",null).use { check(it.moveToFirst() && it.getString(0).equals("delete",ignoreCase=true)) }
            db.execSQL("PRAGMA user_version=2")
            db.execSQL("CREATE TABLE words(kind TEXT NOT NULL,text TEXT NOT NULL,code TEXT NOT NULL,weight INTEGER NOT NULL,ordinal INTEGER NOT NULL,priority INTEGER NOT NULL,compact TEXT NOT NULL,fuzzy TEXT NOT NULL,syllable TEXT NOT NULL,UNIQUE(kind,text,code))")
            db.beginTransaction()
            try {
                val insert=db.compileStatement("INSERT OR IGNORE INTO words VALUES(?,?,?,?,?,?,?,?,?)")
                val promote=db.compileStatement("UPDATE words SET priority=min(priority,?) WHERE kind=? AND text=? AND code=?")
                insert.use { ins -> promote.use { pro ->
                    source.rawQuery("SELECT l.kind,e.text,e.code,e.weight,e.id,l.priority,l.filename FROM entries e JOIN libraries l ON l.id=e.library ORDER BY l.id,e.id",null).use { c ->
                        var ordinal=0L
                        while(c.moveToNext()) {
                            if (Thread.currentThread().isInterrupted) error("编译已中断")
                            val sourceKind=c.getString(0); val filename=c.getString(6)
                            val kind=sourceKind; val text=c.getString(1); val code=c.getString(2); val weight=c.getLong(3)
                            validate(sourceKind,text,code,weight)
                            val priority=if(c.getInt(5)!=0) ordinal else Long.MAX_VALUE
                            val compact=code.replace(" ","")
                            ins.bindString(1,kind); ins.bindString(2,text); ins.bindString(3,code); ins.bindLong(4,weight)
                            ins.bindLong(5,ordinal); ins.bindLong(6,priority); ins.bindString(7,compact)
                            ins.bindString(8,if(kind=="pinyin") DictionaryEngine.fuzzyInputKey(code) else "")
                            ins.bindString(9,if(kind=="pinyin" && !code.contains(' ') && text.codePointCount(0,text.length)==1) code else "")
                            ins.executeInsert()
                            if (kind=="pinyin" && filename=="skey_pinyin.tsv") {
                                ins.bindString(1,"mixed_pinyin"); ins.executeInsert()
                            }
                            if(priority!=Long.MAX_VALUE) { pro.bindLong(1,priority); pro.bindString(2,kind); pro.bindString(3,text); pro.bindString(4,code); pro.executeUpdateDelete() }
                            ordinal++
                            if(ordinal%10000L==0L) progress("正在编译 $ordinal 条")
                            require(ordinal<=1_000_000) { "词库总条目不能超过 100 万" }
                        }
                    }
                } }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            progress("正在建立查询索引")
            db.execSQL("CREATE INDEX exact_words ON words(kind,compact,priority,weight DESC,ordinal)")
            db.execSQL("CREATE INDEX fuzzy_words ON words(kind,fuzzy,weight DESC,ordinal) WHERE kind='pinyin'")
            db.execSQL("CREATE INDEX syllable_words ON words(kind,syllable,weight DESC,ordinal) WHERE kind='pinyin'")
            db.execSQL("CREATE INDEX mixed_fuzzy_words ON words(kind,fuzzy,weight DESC,ordinal) WHERE kind='mixed_pinyin'")
            db.execSQL("CREATE INDEX mixed_syllable_words ON words(kind,syllable,weight DESC,ordinal) WHERE kind='mixed_pinyin'")
            // Initial-letter candidates are derived from the active single-character pinyin rows.
            // There is no separate legacy initials library to retain or restore.
            db.execSQL("INSERT OR IGNORE INTO words SELECT 'pinyin_initial',text,substr(code,1,1),max(weight),min(ordinal),9223372036854775807,substr(code,1,1),'','' FROM words WHERE kind='pinyin' AND length(text)=1 AND instr(code,' ')=0 GROUP BY text,substr(code,1,1)")
            DictionarySearchIndex.build(db)
            db.rawQuery("PRAGMA integrity_check",null).use { check(it.moveToFirst() && it.getString(0)=="ok") }
        } finally { db.close() }
    }
    fun validate(kind: String,text: String,code: String,weight: Long) {
        require(text.isNotBlank() && text.length<=256 && text.none { Character.isISOControl(it) }) { "词条应为 1–256 个字符，不能包含控制字符" }
        require(weight >= 0) { "词频须为 非负 的整数" }
        require(when(kind) {
            "wubi" -> code.length in 1..4 && code.all { it in 'a'..'z' }
            "pinyin" -> (code.length == 1 && code[0] in 'a'..'z') || code.length in 1..64 && code.split(' ').all { part -> part in PinyinSyllables.values }
            "english" -> text.length in 2..40 && text.all { it in 'a'..'z' } && code==text && weight>0
            else -> false
        }) { "编码格式不正确：五笔为 1–4 个小写字母；拼音用空格分隔音节；英文为 2–40 个小写字母且词频大于 0" }
    }
}
