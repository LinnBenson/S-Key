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
    private val root=File(app.noBackupFilesDir,"builtin-dictionaries-v3").apply { mkdirs() }
    val identity get()=root.path
    private val draft=File(root,"files.db")
    init {
        synchronized(lock) {
            synchronized(publicationLock) {
                if (draft.exists()) cleanupSources()
                cleanupTemporaryFiles()
                cleanupSnapshots(activeFile() ?: File(root, "no-active-snapshot"))
            }
        }
    }
    private fun cleanupTemporaryFiles() {
        val names = setOf("defaults.tmp", "original.tmp", "restore.tmp", "runtime.tmp")
        root.listFiles()?.filter { it.name in names || it.name.startsWith("building-") || it.name.startsWith("parsing-") }
            ?.forEach { it.delete() }
    }
    data class Library(val id: Long,val name: String,val kind: String,val filename: String,val sourcePath: String,val notice: String)
    private fun defaults()=File(root,"defaults.db").also { file -> if(!file.exists()) {
        val temp=File(root,"defaults.tmp")
        app.assets.open("dictionaries/catalog.db").use { input -> temp.outputStream().use { input.copyTo(it) } }
        SQLiteDatabase.openDatabase(temp.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP TABLE IF EXISTS entries")
            it.execSQL("VACUUM")
        }
        check(temp.renameTo(file))
    } }
    private fun <T> database(work: (SQLiteDatabase)->T): T = synchronized(lock) {
        if(!draft.exists()) defaults().copyTo(draft)
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
    private fun priority(id: Long) = database { db ->
        db.rawQuery("SELECT priority FROM libraries WHERE id=?",arrayOf(id.toString())).use { it.moveToFirst() && it.getInt(0)!=0 }
    }
    private fun priorityFilename(kind: String,filename: String) = BuiltinDictionaryFiles.isHighPriority(filename,kind)
    fun savePage(id: Long,page: DictionarySource.Page,text: String)=synchronized(lock) {
        val lib=current(id); check(lib.sourcePath==page.version) { "文件已更新，请重新打开后编辑" }
        val temp=File(root,"edit-${UUID.randomUUID()}.txt")
        try { DictionarySource.replace(source(lib),page,text,temp); DictionarySource.validate(temp,lib.kind,lib.filename,allowEmpty=priority(id)); publishSource(id,temp); cleanupSources() }
        finally { if(current(id).sourcePath!=temp.name) temp.delete() }
    }
    private fun publishSource(id: Long,file: File) {
        require(file.length()<=64*1024*1024) { "词库文件不能超过 64 MiB" }
        // Callers have already validated every line and UTF-8 byte before publication.
        java.io.RandomAccessFile(file,"rw").use { it.fd.sync() }
        mutate { it.execSQL("UPDATE libraries SET source_path=? WHERE id=?",arrayOf<Any>(file.name,id)) }
    }
    fun addFile(filename: String,kind: String,input: InputStream,allowEmptyTemplate: Boolean = false,
                replaceExisting: Boolean = false) = synchronized(lock) {
        input.use { stream ->
            checkName(filename); require(kind in listOf("wubi","pinyin","english"))
            val existing=libraries().firstOrNull { it.filename==filename }
            require(existing==null || replaceExisting) { "已存在同名文件" }
            require(existing==null || existing.kind==kind) { "同名词库类型不一致，请选择原词库所属类型后导入" }
            val file=File(root,"edit-${UUID.randomUUID()}.txt")
            try {
                DictionarySource.copy(stream,file)
                val allowEmpty=if(existing!=null) priority(existing.id) else allowEmptyTemplate || priorityFilename(kind,filename)
                DictionarySource.validate(file,kind,filename,allowEmpty)
                if(existing!=null) {
                    // Publish only after validating the entire replacement; keep identity and ordering.
                    publishSource(existing.id,file)
                } else {
                    java.io.RandomAccessFile(file,"rw").use { it.fd.sync() }
                    mutate { it.execSQL("INSERT INTO libraries(name,kind,filename,source_path,priority) VALUES(?,?,?,?,?)",arrayOf(filename,kind,filename,file.name,if(priorityFilename(kind,filename)) 1 else 0)) }
                }
                cleanupSources()
            } finally {
                if(libraries().none { it.sourcePath==file.name }) file.delete()
            }
        }
    }
    fun protectedFilename(filename: String) = BuiltinDictionaryFiles.isProtected(filename)

    fun renameFile(id: Long,filename: String)=synchronized(lock) {
        checkName(filename)
        require(libraries().none { it.id!=id && it.filename==filename }) { "已存在同名文件" }
        val lib=current(id)
        require(!protectedFilename(lib.filename)) { "内置词库不能重命名" }
        DictionarySource.validate(source(lib),lib.kind,filename,allowEmpty=priority(id))
        // A renamed default must keep pointing to the original bytes, not the renamed asset path.
        if(lib.sourcePath.isEmpty()) {
            val copy=File(root,"edit-${UUID.randomUUID()}.txt"); source(lib).copyTo(copy); publishSource(id,copy)
        }
        mutate { it.execSQL("UPDATE libraries SET filename=? WHERE id=?",arrayOf<Any>(filename,id)) }
    }
    fun deleteLibrary(id: Long)=synchronized(lock) {
        require(!protectedFilename(current(id).filename)) { "内置词库不能删除" }
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
                    staging.execSQL("CREATE TABLE libraries(id INTEGER PRIMARY KEY,kind TEXT,priority INTEGER,filename TEXT)")
                    staging.execSQL("CREATE TABLE entries(id INTEGER PRIMARY KEY,library INTEGER,text TEXT,code TEXT,weight INTEGER)")
                    staging.beginTransaction()
                    try {
                        var count=0
                        for(lib in libraries()) {
                            progress("正在读取 ${lib.filename}")
                            val priority=db.rawQuery("SELECT priority FROM libraries WHERE id=?",arrayOf(lib.id.toString())).use { it.moveToFirst(); it.getInt(0) }
                            staging.execSQL("INSERT INTO libraries VALUES(?,?,?,?)",arrayOf<Any>(lib.id,lib.kind,priority,lib.filename))
                            val statement=staging.compileStatement("INSERT INTO entries(library,text,code,weight) VALUES(?,?,?,?)")
                            val previousCount = count
                            statement.use { ins -> DictionarySource.parse(source(lib),lib.kind,lib.filename) { text,code,weight ->
                                require(++count<=1_000_000) { "总词条不能超过 100 万" }
                                ins.bindLong(1,lib.id); ins.bindString(2,text); ins.bindString(3,code); ins.bindLong(4,weight); ins.executeInsert()
                            } }
                            require(count > previousCount || priority!=0) { "${lib.filename}：没有有效词条，请填写内容或删除空词库后再编译" }
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
    }.also {
        // Drop process-wide references even if the IME is hidden and no new query occurs.
        io.secure.keyboard.engine.OfflineEngine.releaseSnapshot(identity)
        activeFile()?.let { cleanupSnapshots(it) }
    }
    fun activeFile(): File?=synchronized(publicationLock) {
        val name=runCatching { String(AtomicFile(File(root,"active")).readFully()) }.getOrNull() ?: return@synchronized null
        require(Regex("compiled-[a-f0-9-]+\\.db").matches(name)); File(root,name).takeIf { it.isFile }
    }
    fun defaultRuntime(): File=synchronized(publicationLock) {
        File(root,"runtime.db").also { file -> if(!file.exists()) {
            val temp=File(root,"runtime.tmp")
            app.assets.open("dictionaries/runtime.db").use { input -> temp.outputStream().use { input.copyTo(it) } }; check(temp.renameTo(file))
        } }
    }
    fun cleanupSnapshots(keep: File)=synchronized(publicationLock) {
        root.listFiles()?.filter { it.name.startsWith("compiled-") && it.extension=="db" && it!=keep && it!=activeFile() }?.forEach { CompiledDictionary.deleteIfUnused(it) }
    }
    companion object { private val lock=Any(); private val publicationLock=Any() }
}
