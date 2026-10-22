package io.secure.keyboard.dictionaries

import android.content.ContextWrapper
import android.test.AndroidTestCase
import io.secure.keyboard.engine.*
import java.io.File
import java.io.StringReader
import java.nio.ByteBuffer

class BuiltinDictionaryTest : AndroidTestCase() {
    private lateinit var directory: File
    private lateinit var repository: DictionaryRepository
    override fun setUp() {
        super.setUp()
        directory=File(context.cacheDir,"dictionary-test-${System.nanoTime()}").apply { mkdirs() }
        repository=DictionaryRepository(object: ContextWrapper(context) { override fun getNoBackupFilesDir()=directory; override fun getApplicationContext()=this })
    }
    override fun tearDown() { directory.deleteRecursively(); super.tearDown() }

    fun testUntouchedPreviewCatalogUpgradePreservesEdits() {
        assertEquals(11,repository.libraries().size)
        assertTrue("Default catalog must only retain metadata", File(directory,"builtin-dictionaries/defaults-v7.db").length() < 1024 * 1024)
        val draft=File(directory,"builtin-dictionaries/files-v2.db")
        android.database.sqlite.SQLiteDatabase.openDatabase(draft.path,null,0).use {
            it.execSQL("DELETE FROM metadata WHERE key='catalog_version'")
            it.execSQL("DELETE FROM libraries WHERE id=10")
        }
        assertEquals(11,repository.libraries().size)
        repository.deleteLibrary(10)
        android.database.sqlite.SQLiteDatabase.openDatabase(draft.path,null,0).use {
            it.execSQL("DELETE FROM metadata WHERE key='catalog_version'")
        }
        assertEquals(10,repository.libraries().size)
        assertTrue(repository.dirty())
    }
    fun testMixedPinyinIsolationAndInitials() {
        repository.libraries().forEach { repository.deleteLibrary(it.id) }
        repository.addFile("pinyin_simp.dict.yaml","pinyin","name: base\n...\n基础词\tji chu ci\t10\n".byteInputStream())
        repository.addFile("extra.tsv","pinyin","额外词\te wai ci\t999\n基础词\tji chu ci\t999999\n".byteInputStream())
        repository.addFile("pinyin_initials.dict.yaml","pinyin","name: initials\n...\n你\tn\t100\n".byteInputStream())
        repository.compile {}
        CompiledDictionary(repository.activeFile()!!).use { db ->
            assertTrue(db.scopedPinyin)
            assertEquals("基础词",db.mixedEngine.candidates("jichuci",false).first().text)
            assertTrue(db.mixedEngine.candidates("ewaici",false).isEmpty())
            assertEquals("额外词",db.engine.decodePinyin("ewaici","",emptyList(),false,emptySet()).first().text)
            assertEquals("你",db.engine.decodePinyin("n","",emptyList(),false,emptySet()).first().text)
            assertTrue(db.mixedEngine.candidates("n",false).isEmpty())
            assertTrue(db.engine.decodePinyin("nn","",emptyList(),false,emptySet()).isEmpty())
            assertEquals(10L,db.mixedPinyin.lookup("exact","jichuci",10).single().weight)
        }
        repository.deleteLibrary(repository.libraries().first { it.filename=="pinyin_simp.dict.yaml" }.id)
        repository.compile {}
        CompiledDictionary(repository.activeFile()!!).use { db ->
            assertTrue(db.mixedEngine.candidates("jichuci",false).isEmpty())
            assertEquals("基础词",db.engine.decodePinyin("jichuci","",emptyList(),false,emptySet()).first().text)
        }
    }

    fun testLegacySnapshotDoesNotLeakExtraPinyinIntoMixed() {
        repository.libraries().forEach { repository.deleteLibrary(it.id) }
        repository.addFile("extra.tsv","pinyin","旧快照独有测试词\tce shi ci\t100\n".byteInputStream())
        repository.addFile("wubi.tsv","wubi","旧五笔词\taaaa\t100\n".byteInputStream())
        repository.compile {}
        android.database.sqlite.SQLiteDatabase.openDatabase(repository.activeFile()!!.path,null,0).use {
            it.execSQL("PRAGMA user_version=0")
        }
        val engine=OfflineEngine(object: ContextWrapper(context) {
            override fun getNoBackupFilesDir()=directory
            override fun getApplicationContext()=this
        })
        assertFalse(engine.candidates("ceshici",false).any { it.text=="旧快照独有测试词" })
        assertEquals("旧五笔词",engine.candidates("aaaa",false).first().text)
        assertTrue(engine.decodePinyin("ceshici","",emptyList(),false,emptySet()).any { it.text=="旧快照独有测试词" })
    }

    fun testFullInitialsAndFactoryUpgrade() {
        CompiledDictionary(repository.defaultRuntime()).use { db ->
            val choices=db.engine.decodePinyin("n","",emptyList(),false,emptySet())
            assertEquals(381, db.initials("n").size)
            assertEquals("你",choices.first().text)
            assertTrue(choices.any { it.text==db.initials("n").last().text })
            assertTrue(db.initials("i").isEmpty())
            assertTrue(db.mixedEngine.candidates("n",false).none { it.text=="你" })
        }
        val lib=repository.libraries().single { it.filename=="pinyin_initials.dict.yaml" }
        val folder=File(directory,"builtin-dictionaries")
        File(folder,"original-${lib.id}.txt").writeText("name: old\n...\n你\tn\t1\n")
        android.database.sqlite.SQLiteDatabase.openDatabase(File(folder,"files-v2.db").path,null,0).use {
            it.execSQL("DELETE FROM metadata WHERE key='initials_version'")
        }
        assertTrue(repository.page(lib.id,0).text.contains("version: \"2\""))
        assertTrue(repository.dirty())
        val page=repository.page(lib.id,0)
        repository.savePage(lib.id,page,page.text.replace("啊\ta","自定义\ta"))
        android.database.sqlite.SQLiteDatabase.openDatabase(File(folder,"files-v2.db").path,null,0).use {
            it.execSQL("DELETE FROM metadata WHERE key='initials_version'")
        }
        assertTrue(repository.page(lib.id,0).text.contains("自定义\ta"))
    }

    fun testInitialsCatalogMigrationIsOnceOnly() {
        val initial=repository.libraries().single { it.filename=="pinyin_initials.dict.yaml" }
        repository.deleteLibrary(initial.id)
        assertFalse(repository.libraries().any { it.filename=="pinyin_initials.dict.yaml" })
        val file=File(directory,"builtin-dictionaries/files-v2.db")
        android.database.sqlite.SQLiteDatabase.openDatabase(file.path,null,0).use {
            it.execSQL("DELETE FROM metadata WHERE key='initials_added'")
        }
        assertEquals(1,repository.libraries().count { it.filename=="pinyin_initials.dict.yaml" })
        val added=repository.libraries().single { it.filename=="pinyin_initials.dict.yaml" }
        val page=repository.page(added.id,0)
        repository.savePage(added.id,page,page.text.replace("啊\ta", "测试\ta"))
        assertTrue(repository.page(added.id,0).text.contains("测试\ta"))
        repository.deleteLibrary(added.id)
        assertFalse(repository.libraries().any { it.filename=="pinyin_initials.dict.yaml" })
    }

    fun testPackagedIndexesMatchExistingReaders() {
        val started=System.nanoTime()
        CompiledDictionary(repository.defaultRuntime()).use { compiled ->
            val baseline=CompactWubiTable(ByteBuffer.wrap(context.assets.open("dictionaries/compact-wubi.skwb").use { it.readBytes() }))
            assertEquals(baseline.entryCount,compiled.wubi.entryCount)
            for(code in listOf("a","b","t","zz","tmwy","rwtk","klkl","aaaa","zzzz","iuiu","pvhj","r","com")) {
                assertEquals("exact $code",baseline.exact(code,10000),compiled.wubi.exact(code,10000))
                assertEquals("prefix $code",baseline.prefix(code,30),compiled.wubi.prefix(code,30))
            }
            val english=EnglishLexicon(StringReader(listOf("english_frequency.tsv","english_extended.tsv").joinToString("\n") { name -> context.assets.open("dictionaries/$name").bufferedReader().use { it.readText() } }))
            for(prefix in listOf("a","w","hel","Hel","HE","compu","january","","hello ","z","q","trans","n"))
                assertEquals(prefix,english.complete(prefix,12),compiled.english.complete(prefix,12))
            val reference=DictionaryEngine(StringReader("...\n"),context.assets.open("dictionaries/pinyin_simp.dict.yaml").reader())
            val emptyWubi=object: WubiLookup { override val entryCount=0; override fun exactCandidateCount(code:String)=0
                override fun exact(code:String,limit:Int)=emptyList<Candidate>(); override fun prefix(code:String,limit:Int)=emptyList<Candidate>() }
            val actual=DictionaryEngine(emptyWubi,compiled.pinyin,compiled.english)
            val pairs=listOf(emptySet(),DictionaryEngine.DEFAULT_FUZZY_PAIRS, setOf("r_l"),setOf("n_l","r_l"),setOf("zh_z"))
            for(code in listOf("ni","nihao","zhongguo","xian","xi'an","xi an","ren","len","neng","beijing","zhongguoren","shanghai","zhang","zang","qwerty"))
                for(selected in pairs) assertEquals("$code $selected",reference.candidates(code,true,40,selected),actual.candidates(code,true,40,selected))
        }
        android.util.Log.w("DictionaryTest","Parity elapsed ms="+(System.nanoTime()-started)/1_000_000)
    }
    fun testDraftAddEditDeleteCompileAndRestore() {
        assertEquals(11,repository.libraries().size)
        assertEquals("wubi86.dict.yaml",repository.libraries().first().filename)
        val original=java.io.ByteArrayOutputStream()
        repository.export(repository.libraries().first().id,original)
        assertTrue(context.assets.open("dictionaries/wubi86.dict.yaml").use { it.readBytes() }.contentEquals(original.toByteArray()))
        assertFalse(repository.dirty())
        for(lib in repository.libraries()) repository.deleteLibrary(lib.id)
        repository.addFile("test_wubi.tsv","wubi","# preserved header\n测试\taaaa\t4250000000\n".byteInputStream())
        repository.addFile("test_pinyin.tsv","pinyin","测试\tce shi\t10\n".byteInputStream())
        repository.addFile("test_english.tsv","english","testing\t10\n".byteInputStream())
        val libs=repository.libraries()
        val w=libs.first { it.kind=="wubi" };val e=libs.first { it.kind=="english" }
        assertNull(repository.activeFile())
        repository.compile {}
        val first=checkNotNull(repository.activeFile())
        assertFalse(repository.dirty())
        CompiledDictionary(first).use { snapshot ->
            assertEquals("测试",snapshot.wubi.exact("aaaa",1).single().text)
            assertEquals("测试",snapshot.engine.candidates("ceshi",false).single().text)
            assertEquals(listOf("testing"),snapshot.english.complete("tes",8))
            val page=repository.page(w.id,0)
            assertTrue(page.text.startsWith("# preserved header"))
            repository.savePage(w.id,page,page.text.replace("测试","修改后"))
            repository.renameFile(w.id,"renamed.tsv")
            repository.deleteLibrary(e.id)
            assertTrue(repository.dirty())
            assertEquals(first,repository.activeFile())
            assertEquals("测试",snapshot.wubi.exact("aaaa",1).single().text)
            repository.compile {}
            CompiledDictionary(checkNotNull(repository.activeFile())).use { next ->
                assertEquals("修改后",next.wubi.exact("aaaa",1).single().text)
                assertTrue(next.english.complete("tes",8).isEmpty())
            }
            assertEquals("测试",snapshot.wubi.exact("aaaa",1).single().text)
        }
        val current=repository.activeFile()
        repository.restoreDefaults()
        assertEquals(11,repository.libraries().size)
        assertEquals(current,repository.activeFile())
        assertTrue(repository.dirty())
    }
    fun testInvalidSourceAndInterruptedCompileKeepActive() {
        repository.libraries().forEach { repository.deleteLibrary(it.id) }
        repository.addFile("english.tsv","english","hello\t10\n".byteInputStream())
        val lib=repository.libraries().single()
        repository.compile {}
        val current=repository.activeFile()
        val page=repository.page(lib.id,0)
        try { repository.savePage(lib.id,page,"hello\t10\ninvalid!\t3\n"); fail("invalid source accepted") } catch(e: IllegalArgumentException) { assertTrue(e.message!!.contains("第 2 行")) }
        assertEquals(current,repository.activeFile())
        assertFalse(repository.dirty())
        assertEquals(page.text,repository.page(lib.id,0).text)
        try { repository.addFile("bad.tsv","pinyin","错误\tinvalid\t1\n".byteInputStream()); fail("invalid import accepted") } catch(_: IllegalArgumentException) {}
        assertEquals(1,repository.libraries().size)
        repository.savePage(lib.id,repository.page(lib.id,0),"hello\t10\nworld\t8\n")
        try { repository.compile { error("injected compile failure") }; fail("compile should fail") } catch(_: IllegalStateException) {}
        assertEquals(current,repository.activeFile())
        CompiledDictionary(checkNotNull(current)).use { assertEquals(listOf("hello"),it.english.complete("he",8)); assertTrue(it.english.complete("wo",8).isEmpty()) }
    }
    fun testRepeatedCompilesAndRecoveryBoundFilesWithoutRemovingLiveReader() {
        repository.libraries().forEach { repository.deleteLibrary(it.id) }
        repository.addFile("words.tsv", "english", "hello\t10\n".byteInputStream())
        repository.compile {}
        val first = repository.activeFile()!!
        val reader = CompiledDictionary(first)
        try {
            repeat(6) { repository.compile {} }
            assertTrue(first.exists())
            assertEquals(listOf("hello"), reader.english.complete("he", 8))
            val root = first.parentFile!!
            assertEquals(2, root.listFiles()!!.count { it.name.startsWith("compiled-") && it.extension == "db" })
            reader.close()
            repository.cleanupSnapshots(repository.activeFile()!!)
            assertFalse(first.exists())
            listOf("edit-orphan.txt", "original-999.txt", "building-dead.db", "parsing-dead.db-journal", "restore.tmp").forEach {
                File(root, it).writeText("synthetic abandoned data")
            }
            val recovered = DictionaryRepository(object : ContextWrapper(context) {
                override fun getNoBackupFilesDir() = directory
                override fun getApplicationContext() = this
            })
            assertEquals(1, recovered.libraries().size)
            assertTrue(recovered.page(recovered.libraries().single().id, 0).text.contains("hello"))
            assertEquals(1, root.listFiles()!!.count { it.name.startsWith("compiled-") && it.extension == "db" })
            assertFalse(File(root, "edit-orphan.txt").exists())
            assertFalse(File(root, "original-999.txt").exists())
            assertFalse(File(root, "building-dead.db").exists())
            assertFalse(File(root, "parsing-dead.db-journal").exists())
            assertFalse(File(root, "restore.tmp").exists())
        } finally { reader.close() }
    }

    fun testSourcePageRoundTripAndStaleEdit() {
        repository.libraries().forEach { repository.deleteLibrary(it.id) }
        val original="# header\r\n"+(1..350).joinToString("") { "word${(it/26+97).toChar()}${(it%26+97).toChar()}\t$it\r\n" }
        repository.addFile("roundtrip.tsv","english",original.byteInputStream())
        val lib=repository.libraries().single()
        val page=repository.page(lib.id,120)
        assertEquals(120,page.count)
        repository.savePage(lib.id,page,page.text.replace("wordfa","replacement"))
        val output=java.io.ByteArrayOutputStream();repository.export(lib.id,output)
        assertEquals(original.replace("wordfa","replacement"),output.toString("UTF-8"))
        try { repository.savePage(lib.id,page,"stale");fail("stale page accepted") } catch(_: IllegalStateException) {}
    }
    fun testFullDefaultCompilation() {
        repository.compile {}
        CompiledDictionary(checkNotNull(repository.activeFile())).use { built ->
            CompiledDictionary(repository.defaultRuntime()).use { packaged ->
                assertEquals(packaged.wubi.entryCount,built.wubi.entryCount)
                assertEquals(packaged.pinyin.entryCount,built.pinyin.entryCount)
                for(code in listOf("a","t","tmwy","rwtk","nihao","xi'an","ren","zhongguo"))
                    assertEquals(code,packaged.engine.candidates(code),built.engine.candidates(code))
                assertEquals(packaged.english.complete("com",8),built.english.complete("com",8))
            }
        }
    }
}
