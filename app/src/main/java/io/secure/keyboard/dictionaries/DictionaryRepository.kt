package io.secure.keyboard.dictionaries

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.AtomicFile
import java.io.File
import java.io.InputStream
import java.util.UUID

/** Original files are editable drafts; only a successful compile changes the runtime snapshot. */
class DictionaryRepository(context: Context) {
    private val app=context.applicationContext
    private val root=File(app.noBackupFilesDir,"builtin-dictionaries").apply { mkdirs() }
    val identity get()=root.path
    private val draft=File(root,"files-v2.db")
    init {
        synchronized(lock) {
            synchronized(publicationLock) {
                if (draft.exists()) cleanupSources()
                cleanupTemporaryFiles()
                cleanupSnapshots(activeFile() ?: File(root, "no-active-snapshot"))
                // Only obsolete packaged copies: never user drafts or imported libraries.
                listOf("defaults-v1.db", "defaults-v2.db", "defaults-v3.db", "defaults-v4.db", "runtime-v1.db")
                    .forEach { CompiledDictionary.deleteIfUnused(File(root, it)) }
            }
        }
    }
    private fun cleanupTemporaryFiles() {
        val names = setOf("defaults.tmp", "original.tmp", "restore.tmp", "catalog-update.tmp", "runtime.tmp")
        root.listFiles()?.filter { it.name in names || it.name.startsWith("building-") || it.name.startsWith("parsing-") }
            ?.forEach { it.delete() }
    }
    data class Library(val id: Long,val name: String,val kind: String,val filename: String,val sourcePath: String,val notice: String)
    private fun defaults()=File(root,"defaults-v5.db").also { file -> if(!file.exists()) {
        val temp=File(root,"defaults.tmp")
        app.assets.open("dictionaries/catalog.db").use { input -> temp.outputStream().use { input.copyTo(it) } }
        SQLiteDatabase.openDatabase(temp.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP TABLE IF EXISTS entries")
            it.execSQL("VACUUM")
        }
        check(temp.renameTo(file))
    } }
    private fun <T> database(work: (SQLiteDatabase)->T): T = synchronized(lock) {
        // Upgrade only the untouched preview catalog; user edits and compiled versions survive.
        if(draft.exists() && activeFile()==null) {
            val pristineOldCatalog=SQLiteDatabase.openDatabase(draft.path,null,SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT (SELECT value FROM metadata WHERE key='dirty')='0' AND NOT EXISTS(SELECT 1 FROM metadata WHERE key='catalog_version') AND NOT EXISTS(SELECT 1 FROM libraries WHERE source_path!='')",null).use { it.moveToFirst(); it.getInt(0)==1 }
            }
            if(pristineOldCatalog) {
                val update=File(root,"catalog-update.tmp")
                defaults().copyTo(update,overwrite=true)
                SQLiteDatabase.openDatabase(update.path,null,SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("DROP TABLE IF EXISTS entries"); it.execSQL("VACUUM") }
                check(update.renameTo(draft))
            }
        }
        if(!draft.exists()) {
            defaults().copyTo(draft)
            SQLiteDatabase.openDatabase(draft.path,null,SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("DROP TABLE IF EXISTS entries"); it.execSQL("VACUUM") }
        }
        SQLiteDatabase.openDatabase(draft.path,null,SQLiteDatabase.OPEN_READWRITE).use(work)
    }
    fun libraries(): List<Library> = database { db -> db.rawQuery("SELECT id,name,kind,filename,source_path,notice FROM libraries ORDER BY id",null).use { c ->
        buildList { while(c.moveToNext()) add(Library(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5))) }
    } }
    fun dirty()=database { db -> db.rawQuery("SELECT value FROM metadata WHERE key='dirty'",null).use { it.moveToFirst(); it.getString(0)=="1" } }
    private fun mutate(work: (SQLiteDatabase)->Unit)=database { db ->
        db.beginTransaction()
        try { work(db); db.execSQL("UPDATE metadata SET value='1' WHERE key='dirty'"); db.setTransactionSuccessful() }
        finally { db.endTransaction() }
    }
    private fun checkName(name: String) {
        require(name.length in 1..100 && name.none { it=='/' || it=='\\' || it.isISOControl() }) { "文件名不合法" }
        require(listOf(".yaml",".yml",".tsv",".txt").any { name.endsWith(it,true) }) { "请选择 .dict.yaml、.tsv 或 .txt 文件名" }
    }
    private fun source(lib: Library): File {
        if(lib.sourcePath.isNotEmpty()) return File(root,lib.sourcePath).also { check(it.isFile) { "词库源文件缺失" } }
        return File(root,"original-${lib.id}.txt").also { file -> if(!file.exists()) {
            val temp=File(root,"original.tmp")
            app.assets.open("dictionary-sources/${lib.filename}").use { input -> temp.outputStream().use { input.copyTo(it) } }
            check(temp.renameTo(file))
        } }
    }
    private fun current(id: Long)=libraries().firstOrNull { it.id==id } ?: error("文件已删除")
    fun page(id: Long,start: Int)=synchronized(lock) { val lib=current(id); DictionarySource.page(source(lib),start,lib.sourcePath) }
    fun find(id: Long,query: String,after: Int)=synchronized(lock) { DictionarySource.find(source(current(id)),query,after) }
    fun export(id: Long,output: java.io.OutputStream)=synchronized(lock) { output.use { out -> source(current(id)).inputStream().use { it.copyTo(out) } } }
    fun savePage(id: Long,page: DictionarySource.Page,text: String)=synchronized(lock) {
        val lib=current(id); check(lib.sourcePath==page.version) { "文件已更新，请重新打开后编辑" }
        val temp=File(root,"edit-${UUID.randomUUID()}.txt")
        try { DictionarySource.replace(source(lib),page,text,temp); publishSource(id,temp); cleanupSources() }
        finally { if(current(id).sourcePath!=temp.name) temp.delete() }
    }
    private fun publishSource(id: Long,file: File) {
        require(file.length()<=64*1024*1024) { "词库文件不能超过 64 MiB" }
        DictionarySource.reader(file.inputStream()).use { r -> while(r.read()!=-1) {} }
        java.io.RandomAccessFile(file,"rw").use { it.fd.sync() }
        mutate { it.execSQL("UPDATE libraries SET source_path=? WHERE id=?",arrayOf<Any>(file.name,id)) }
    }
    fun addFile(filename: String,kind: String,input: InputStream) = synchronized(lock) {
        checkName(filename); require(kind in listOf("wubi","pinyin","english"))
        require(libraries().none { it.filename==filename }) { "已存在同名文件" }
        val file=File(root,"edit-${UUID.randomUUID()}.txt")
        try {
            DictionarySource.copy(input,file)
            java.io.RandomAccessFile(file,"rw").use { it.fd.sync() }
            mutate { it.execSQL("INSERT INTO libraries(name,kind,filename,source_path) VALUES(?,?,?,?)",arrayOf(filename,kind,filename,file.name)) }
        } catch(e: Exception) { file.delete(); throw e }
    }
    fun renameFile(id: Long,filename: String)=synchronized(lock) {
        checkName(filename)
        require(libraries().none { it.id!=id && it.filename==filename }) { "已存在同名文件" }
        val lib=current(id)
        // A renamed default must keep pointing to the original bytes, not the renamed asset path.
        if(lib.sourcePath.isEmpty()) {
            val copy=File(root,"edit-${UUID.randomUUID()}.txt"); source(lib).copyTo(copy); publishSource(id,copy)
        }
        mutate { it.execSQL("UPDATE libraries SET filename=? WHERE id=?",arrayOf<Any>(filename,id)) }
    }
    fun deleteLibrary(id: Long)=synchronized(lock) {
        mutate { it.execSQL("DELETE FROM libraries WHERE id=?",arrayOf(id)) }
        cleanupSources()
    }
    private fun cleanupSources() {
        val retained=libraries().map { if (it.sourcePath.isEmpty()) "original-${it.id}.txt" else it.sourcePath }.toSet()
        root.listFiles()?.filter {
            (it.name.startsWith("edit-") || Regex("original-\\d+\\.txt").matches(it.name)) && it.name !in retained
        }?.forEach { it.delete() }
    }
    fun restoreDefaults()=synchronized(lock) {
        val temp=File(root,"restore.tmp"); defaults().copyTo(temp,overwrite=true)
        SQLiteDatabase.openDatabase(temp.path,null,SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP TABLE IF EXISTS entries"); it.execSQL("UPDATE metadata SET value='1' WHERE key='dirty'"); it.execSQL("VACUUM")
        }
        check(temp.renameTo(draft))
        cleanupSources()
    }
    fun compile(progress: (String)->Unit)=synchronized(lock) {
        root.listFiles()?.filter { it.name.startsWith("building-") || it.name.startsWith("parsing-") }?.forEach { it.delete() }
        val id=UUID.randomUUID().toString(); val file=File(root,"building-$id.db"); val parsed=File(root,"parsing-$id.db")
        val published=File(root,"compiled-$id.db")
        try {
            database { db ->
                SQLiteDatabase.openOrCreateDatabase(parsed,null).use { staging ->
                    staging.execSQL("CREATE TABLE libraries(id INTEGER PRIMARY KEY,kind TEXT,priority INTEGER)")
                    staging.execSQL("CREATE TABLE entries(id INTEGER PRIMARY KEY,library INTEGER,text TEXT,code TEXT,weight INTEGER)")
                    staging.beginTransaction()
                    try {
                        var count=0
                        for(lib in libraries()) {
                            progress("正在读取 ${lib.filename}")
                            val priority=db.rawQuery("SELECT priority FROM libraries WHERE id=?",arrayOf(lib.id.toString())).use { it.moveToFirst(); it.getInt(0) }
                            staging.execSQL("INSERT INTO libraries VALUES(?,?,?)",arrayOf<Any>(lib.id,lib.kind,priority))
                            val statement=staging.compileStatement("INSERT INTO entries(library,text,code,weight) VALUES(?,?,?,?)")
                            statement.use { ins -> DictionarySource.parse(source(lib),lib.kind,lib.filename) { text,code,weight ->
                                require(++count<=1_000_000) { "总词条不能超过 100 万" }
                                ins.bindLong(1,lib.id); ins.bindString(2,text); ins.bindString(3,code); ins.bindLong(4,weight); ins.executeInsert()
                            } }
                        }
                        staging.setTransactionSuccessful()
                    } finally { staging.endTransaction() }
                    DictionaryCompiler.compile(staging,file,progress)
                }
            }
            CompiledDictionary(file).use { }
            java.io.RandomAccessFile(file,"rw").use { it.fd.sync() }
            synchronized(publicationLock) {
                check(file.renameTo(published))
                val pointer=AtomicFile(File(root,"active")); val out=pointer.startWrite()
                try { out.write(published.name.toByteArray()); pointer.finishWrite(out) }
                catch(e: Exception) { pointer.failWrite(out); throw e }
            }
            database { it.execSQL("UPDATE metadata SET value='0' WHERE key='dirty'") }
            app.getSharedPreferences("settings",0).edit().putString("builtin_dictionary_revision",id).commit()
            cleanupSnapshots(published)
        } catch(e: Exception) { file.delete(); if(activeFile()?.name!=published.name) published.delete(); throw e }
        finally { SQLiteDatabase.deleteDatabase(parsed); SQLiteDatabase.deleteDatabase(file) }
    }
    fun activeFile(): File?=synchronized(publicationLock) {
        val name=runCatching { String(AtomicFile(File(root,"active")).readFully()) }.getOrNull() ?: return@synchronized null
        require(Regex("compiled-[a-f0-9-]+\\.db").matches(name)); File(root,name).takeIf { it.isFile }
    }
    fun defaultRuntime(): File=synchronized(publicationLock) {
        File(root,"runtime-v2.db").also { file -> if(!file.exists()) {
            val temp=File(root,"runtime.tmp")
            app.assets.open("dictionaries/runtime.db").use { input -> temp.outputStream().use { input.copyTo(it) } }; check(temp.renameTo(file))
        } }
    }
    fun cleanupSnapshots(keep: File)=synchronized(publicationLock) {
        root.listFiles()?.filter { it.name.startsWith("compiled-") && it.extension=="db" && it!=keep && it!=activeFile() }?.forEach { CompiledDictionary.deleteIfUnused(it) }
    }
    companion object { private val lock=Any(); private val publicationLock=Any() }
}
