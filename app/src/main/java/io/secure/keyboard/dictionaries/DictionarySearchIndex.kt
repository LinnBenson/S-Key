package io.secure.keyboard.dictionaries

import android.database.sqlite.SQLiteDatabase

/** Derived only from dictionary rows, never from typed text. */
internal object DictionarySearchIndex {
    fun build(db: SQLiteDatabase) {
        // Wubi alone uses priority. Other exact lookups can read directly in frequency order.
        db.execSQL("CREATE INDEX ranked_exact_words ON words(kind,compact,weight DESC,ordinal) WHERE kind='pinyin_initial'")
        db.execSQL("CREATE INDEX prediction_words ON words(kind,text,weight DESC) WHERE kind IN ('pinyin','mixed_pinyin')")
        db.execSQL("CREATE TABLE short_prefixes(kind TEXT NOT NULL,prefix TEXT NOT NULL,rank INTEGER NOT NULL,word_id INTEGER NOT NULL,PRIMARY KEY(kind,prefix,rank)) WITHOUT ROWID")
        db.beginTransaction()
        try {
            db.compileStatement("INSERT INTO short_prefixes VALUES(?,?,?,?)").use { insert ->
                for ((kind, limit, order) in listOf(Triple("wubi",30,"weight DESC,ordinal"),Triple("english",12,"weight DESC,text"))) {
                    val counts = HashMap<String,Int>()
                    db.rawQuery("SELECT rowid,compact FROM words WHERE kind='$kind' ORDER BY $order",null).use { c ->
                        while(c.moveToNext()) {
                            if (Thread.currentThread().isInterrupted) error("编译已中断")
                            val code = c.getString(1)
                            for (length in 1..minOf(2,code.length-1)) {
                                val prefix = code.substring(0,length)
                                val rank = counts[prefix] ?: 0
                                if (rank >= limit) continue
                                insert.bindString(1,kind); insert.bindString(2,prefix)
                                insert.bindLong(3,rank.toLong()); insert.bindLong(4,c.getLong(0))
                                insert.executeInsert()
                                counts[prefix] = rank+1
                            }
                        }
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        db.execSQL("PRAGMA user_version=3")
    }
}
