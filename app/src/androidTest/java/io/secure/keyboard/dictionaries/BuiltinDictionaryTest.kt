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

    // Isolate compiler fixtures directly; public APIs must never bypass protected files.
    private fun emptyFixture(keep: Set<String> = emptySet()) {
        repository.libraries()
        android.database.sqlite.SQLiteDatabase.openDatabase(File(directory,"builtin-dictionaries/files-v2.db").path,null,0).use { db ->
            repository.libraries().filter { it.filename !in keep }.forEach { db.execSQL("DELETE FROM libraries WHERE id=?", arrayOf(it.id)) }
        }
    }

    fun testUntouchedPreviewCatalogUpgradePreservesEdits() {
        assertEquals(13,repository.libraries().size)
        assertTrue("Default catalog must only retain metadata", File(directory,"builtin-dictionaries/defaults-v8.db").length() < 1024 * 1024)
        val draft=File(directory,"builtin-dictionaries/files-v2.db")
        android.database.sqlite.SQLiteDatabase.openDatabase(draft.path,null,0).use {
            it.execSQL("DELETE FROM metadata WHERE key='catalog_version'")
            it.execSQL("DELETE FROM libraries WHERE id=10")
        }
        assertEquals(13,repository.libraries().size)
        repository.deleteLibrary(10)
        android.database.sqlite.SQLiteDatabase.openDatabase(draft.path,null,0).use {
            it.execSQL("DELETE FROM metadata WHERE key='catalog_version'")
        }
        assertEquals(12,repository.libraries().size)
        assertTrue(repository.dirty())
    }
    fun testMixedPinyinIsolationAndInitials() {
        emptyFixture()
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
        val protected=repository.libraries().first { it.filename=="pinyin_simp.dict.yaml" }
        try { repository.deleteLibrary(protected.id); fail("Protected library deleted") } catch(_: IllegalArgumentException) { }
        try { repository.renameFile(protected.id,"other.yaml"); fail("Protected library renamed") } catch(_: IllegalArgumentException) { }
        assertEquals("pinyin_simp.dict.yaml",repository.libraries().first { it.id==protected.id }.filename)
    }

    fun testLegacySnapshotDoesNotLeakExtraPinyinIntoMixed() {
        emptyFixture()
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
        assertEquals(13,repository.libraries().size)
        assertEquals("wubi86.dict.yaml",repository.libraries().first().filename)
        val original=java.io.ByteArrayOutputStream()
        repository.export(repository.libraries().first().id,original)
        assertTrue(context.assets.open("dictionaries/wubi86.dict.yaml").use { it.readBytes() }.contentEquals(original.toByteArray()))
        assertFalse(repository.dirty())
        emptyFixture()
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
        assertEquals(13,repository.libraries().size)
        assertEquals(current,repository.activeFile())
        assertTrue(repository.dirty())
    }
    fun testInvalidSourceAndInterruptedCompileKeepActive() {
        emptyFixture()
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
        emptyFixture()
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
        emptyFixture()
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
    fun testEmptyPriorityLibrariesCompileAndRankOnlyExactMatches() {
        val priorityNames=setOf("pinyin_high_priority.dict.yaml","english_high_priority.tsv")
        emptyFixture(priorityNames)
        repository.compile {}
        assertFalse(repository.dirty())
        CompiledDictionary(repository.activeFile()!!).use {
            assertTrue(it.engine.decodePinyin("nihao","",emptyList(),false,emptySet()).isEmpty())
            assertTrue(it.english.complete("cat",12).isEmpty())
        }
        emptyFixture()
        repository.addFile("pinyin_simp.dict.yaml","pinyin","name: base\n...\n你好\tni hao\t1000000\n你\tni\t1000\n好\thao\t1000\n".byteInputStream())
        repository.addFile("normal.tsv","english","cat\t1\ncats\t99999\ncap\t100\n".byteInputStream())
        repository.addFile("pinyin_high_priority.dict.yaml","pinyin","name: priority\n...\n".byteInputStream())
        repository.addFile("english_high_priority.tsv","english","# empty\n".byteInputStream())
        val py=repository.libraries().single { it.filename=="pinyin_high_priority.dict.yaml" }
        val en=repository.libraries().single { it.filename=="english_high_priority.tsv" }
        val page=repository.page(py.id,0)
        repository.savePage(py.id,page,page.text+"拟好\tni hao\t0\n你好\tni hao\t0\n")
        val englishPage=repository.page(en.id,0)
        repository.savePage(en.id,englishPage,englishPage.text+"cat\t1\n")
        repository.compile {}
        CompiledDictionary(repository.activeFile()!!).use { compiled ->
            assertEquals(listOf("拟好","你好"),compiled.pinyin.lookup("exact","nihao",2).map { it.text })
            assertEquals(listOf("拟好","你好"),compiled.engine.decodePinyin("nihao","",emptyList(),false,emptySet()).take(2).map { it.text })
            assertEquals("你好",compiled.engine.decodePinyin("lihao","",emptyList(),true,setOf("n_l")).first().text)
            assertEquals("你好",compiled.mixedEngine.candidates("nihao",false).first().text)
            assertFalse(compiled.mixedEngine.candidates("nihao",false).any { it.text=="拟好" })
            assertEquals(listOf("cat","cats"),compiled.english.complete("cat",12))
            assertEquals(listOf("CAT","CATS"),compiled.english.complete("CAT",12))
            assertEquals(listOf("cat"),compiled.english.complete("cat",1))
            assertEquals(listOf("cats","cap","cat"),compiled.english.complete("ca",12))
            assertEquals(1000000L,compiled.pinyin.lookup("exact","nihao",2)[1].weight)
        }
        // Empty high-priority files remain valid after editing and renaming.
        repository.savePage(py.id,repository.page(py.id,0),"name: priority\n...\n")
        repository.savePage(en.id,repository.page(en.id,0),"# empty\n")
        repository.renameFile(en.id,"my-priority.tsv")
        repository.compile {}
        CompiledDictionary(repository.activeFile()!!).use {
            assertEquals("你好",it.engine.decodePinyin("nihao","",emptyList(),false,emptySet()).first().text)
            assertEquals(listOf("cats"),it.english.complete("cat",12))
        }
    }

    fun testPriorityCatalogMigrationPreservesDraftsAndDeletion() {
        val names=setOf("pinyin_high_priority.dict.yaml","english_high_priority.tsv")
        repository.libraries().filter { it.filename in names }.forEach { repository.deleteLibrary(it.id) }
        val catalog=File(directory,"builtin-dictionaries/files-v2.db")
        android.database.sqlite.SQLiteDatabase.openDatabase(catalog.path,null,0).use {
            it.execSQL("DELETE FROM metadata WHERE key='priority_libraries_added'")
        }
        assertEquals(names,repository.libraries().filter { it.filename in names }.map { it.filename }.toSet())
        val py=repository.libraries().single { it.filename=="pinyin_high_priority.dict.yaml" }
        repository.savePage(py.id,repository.page(py.id,0),"name: custom\n...\n自定\tzi ding\t1\n")
        android.database.sqlite.SQLiteDatabase.openDatabase(catalog.path,null,0).use {
            it.execSQL("DELETE FROM metadata WHERE key='priority_libraries_added'")
        }
        assertTrue(repository.page(py.id,0).text.contains("自定"))
        assertEquals(1,repository.libraries().count { it.filename==py.filename })
        repository.deleteLibrary(py.id)
        assertFalse(repository.libraries().any { it.filename==py.filename })
    }

    fun testSearchIndexesPreserveLimitsAndLegacySnapshots() {
        emptyFixture()
        val words=(0 until 40).joinToString("\n") { i -> "a${('a'.code+i/26).toChar()}${('a'.code+i%26).toChar()}\t${40-i}" }
        repository.addFile("english.tsv","english",words.byteInputStream())
        repository.addFile("wubi.tsv","wubi","甲\taa\t9\n乙\tab\t7\n丙\ta\t999\n".byteInputStream())
        repository.compile {}
        val file=repository.activeFile()!!
        val expected=EnglishLexicon(StringReader(words))
        CompiledDictionary(file).use { compiled ->
            for (prefix in listOf("a","A","AA","aa","ab","abc","zz")) for(limit in listOf(0,1,8,12,13,100))
                assertEquals("$prefix $limit",expected.complete(prefix,limit),compiled.english.complete(prefix,limit))
            assertEquals(listOf("甲","乙"),compiled.wubi.prefix("a",30).map { it.text })
            assertEquals(listOf("甲"),compiled.wubi.prefix("a",1).map { it.text })
            assertEquals("丙",compiled.wubi.exact("a",1).single().text)
            assertTrue(compiled.pinyin.lookup("exact","a",-1).isEmpty())
        }
        android.database.sqlite.SQLiteDatabase.openDatabase(file.path,null,0).use {
            it.execSQL("DROP TABLE short_prefixes")
            it.execSQL("DROP INDEX ranked_exact_words")
            it.execSQL("DROP INDEX prediction_words")
            it.execSQL("PRAGMA user_version=2")
        }
        CompiledDictionary(file).use { compiled ->
            assertEquals(expected.complete("a",12),compiled.english.complete("a",12))
            assertEquals(listOf("甲","乙"),compiled.wubi.prefix("a",30).map { it.text })
        }
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
