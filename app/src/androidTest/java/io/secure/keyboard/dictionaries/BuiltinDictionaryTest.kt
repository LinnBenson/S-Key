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

    fun testPredictionCancellationDoesNotCloseSharedDatabase() {
        CompiledDictionary(repository.defaultRuntime()).use { db ->
            val signal = android.os.CancellationSignal().apply { cancel() }
            try { db.pinyin.predictions("中", signal); fail("Canceled query ran") }
            catch (_: android.os.OperationCanceledException) { }
            assertTrue(db.pinyin.predictions("中", android.os.CancellationSignal()).isNotEmpty())
        }
    }

    // Isolate compiler fixtures directly; public APIs must never bypass protected files.
    private fun emptyFixture(keep: Set<String> = emptySet()) {
        repository.libraries()
        android.database.sqlite.SQLiteDatabase.openDatabase(File(directory,"builtin-dictionaries-v3/files.db").path,null,0).use { db ->
            repository.libraries().filter { it.filename !in keep }.forEach { db.execSQL("DELETE FROM libraries WHERE id=?", arrayOf(it.id)) }
        }
    }

    fun testCurrentNamesAndCustomLibrariesRemainEditable() {
        assertEquals(listOf("skey_wubi86.tsv","skey_wubi86_high.tsv","skey_pinyin.tsv","skey_pinyin_high.tsv","skey_english.tsv","skey_english_high.tsv"),repository.libraries().map { it.filename })
        repository.addFile("custom.tsv","english","hello\t7\n".byteInputStream())
        val lib=repository.libraries().last()
        assertFalse(repository.protectedFilename(lib.filename))
        repository.renameFile(lib.id,"renamed.tsv")
        repository.deleteLibrary(lib.id)
        assertEquals(6,repository.libraries().size)
        val high=repository.libraries().first { it.filename=="skey_wubi86_high.tsv" }
        repository.savePage(high.id,repository.page(high.id,0),"保留\taaaa\t0\n")
        repository=DictionaryRepository(object: ContextWrapper(context) { override fun getNoBackupFilesDir()=directory; override fun getApplicationContext()=this })
        assertEquals("保留\taaaa\t0\n",repository.page(high.id,0).text)
    }

    fun testAllBuiltinLibrariesRejectDeleteAndRename() {
        val bases=repository.libraries()
        assertEquals(6,bases.size)
        for (lib in bases) {
            assertTrue(repository.protectedFilename(lib.filename))
            try { repository.deleteLibrary(lib.id); fail("Base library deleted") } catch(_: IllegalArgumentException) { }
            try { repository.renameFile(lib.id,"renamed.tsv"); fail("Base library renamed") } catch(_: IllegalArgumentException) { }
            assertTrue(repository.page(lib.id,0).text.isNotEmpty())
        }
        assertEquals(6,repository.libraries().size)
    }
    fun testMixedPinyinIsolationAndInitials() {
        emptyFixture()
        repository.addFile("skey_pinyin.tsv","pinyin","基础词\tji chu ci\t10\n".byteInputStream())
        repository.addFile("extra.tsv","pinyin","额外词\te wai ci\t999\n基础词\tji chu ci\t999999\n".byteInputStream())
        repository.addFile("custom_initials.tsv","pinyin","你\tn\t100\n".byteInputStream())
        repository.compile {}
        CompiledDictionary(repository.activeFile()!!).use { db ->
            assertTrue(db.scopedPinyin)
            assertEquals("基础词",db.mixedEngine.candidates("jichuci",false).first().text)
            assertTrue(db.mixedEngine.candidates("ewaici",false).isEmpty())
            assertEquals("额外词",db.engine.decodePinyin("ewaici","",emptyList(),false,emptySet()).first().text)
            assertEquals("你",db.engine.decodePinyin("n","",emptyList(),false,emptySet()).first().text)
            assertTrue(db.mixedEngine.candidates("n",false).isEmpty())
            assertEquals("你你",db.engine.decodePinyin("nn","",emptyList(),false,emptySet()).first().text)
            assertEquals(10L,db.mixedPinyin.lookup("exact","jichuci",10).single().weight)
        }
        val protected=repository.libraries().first { it.filename=="skey_pinyin.tsv" }
        try { repository.deleteLibrary(protected.id); fail("Protected library deleted") } catch(_: IllegalArgumentException) { }
        try { repository.renameFile(protected.id,"other.yaml"); fail("Protected library renamed") } catch(_: IllegalArgumentException) { }
        assertEquals("skey_pinyin.tsv",repository.libraries().first { it.id==protected.id }.filename)
    }


    fun testInitialsAreDerivedFromBaseAndOldAssetsAreAbsent() {
        CompiledDictionary(repository.defaultRuntime()).use { db ->
            assertEquals("你",db.engine.decodePinyin("n","",emptyList(),false,emptySet()).first().text)
            assertTrue(db.initials("n").isNotEmpty())
            assertTrue(db.initials("i").isEmpty())
            assertTrue(db.mixedEngine.candidates("n",false).none { it.text=="你" })
        }
        assertFalse(repository.libraries().any { it.filename=="pinyin_initials.dict.yaml" })
        for(name in listOf("compact-wubi.skwb","wubi86.dict.yaml","pinyin_simp.dict.yaml","english_frequency.tsv","english_extended.tsv")) {
            assertFalse(context.assets.list("dictionaries")!!.contains(name))
        }
    }

    fun testAllThreePriorityTemplatesCanRemainEmpty() {
        val priorities=repository.libraries().filter { it.filename.endsWith("_high.tsv") }
        assertEquals(3,priorities.size)
        for(lib in priorities) {
            val page=repository.page(lib.id,0)
            repository.savePage(lib.id,page,page.text)
        }
        emptyFixture(priorities.map { it.filename }.toSet())
        repository.compile {}
        CompiledDictionary(repository.activeFile()!!).use {
            assertEquals(0,it.wubi.entryCount)
            assertTrue(it.engine.decodePinyin("nihao","",emptyList(),false,emptySet()).isEmpty())
            assertTrue(it.english.complete("he",12).isEmpty())
        }
    }

    fun testPackagedIndexesAndPhoneCompilationPreserveOrdering() {
        val packaged=repository.defaultRuntime()
        repository.compile {}
        CompiledDictionary(packaged).use { original -> CompiledDictionary(repository.activeFile()!!).use { rebuilt ->
            assertEquals(136916,rebuilt.wubi.entryCount)
            assertEquals(65125,rebuilt.pinyin.entryCount)
            assertEquals(65125,rebuilt.mixedPinyin.entryCount)
            for(code in listOf("a","b","wq","wqvb","tmwy","khlg","aaaa","wg")) {
                assertEquals(code,original.wubi.exact(code,10000),rebuilt.wubi.exact(code,10000))
                assertEquals(code,original.wubi.prefix(code,30),rebuilt.wubi.prefix(code,30))
            }
            for(code in listOf("nihao","weixin","zhongguo","shurufa","ni")) {
                assertEquals(code,original.pinyin.lookup("exact",code,100),rebuilt.pinyin.lookup("exact",code,100))
                assertEquals(code,original.mixedPinyin.lookup("exact",code,100),rebuilt.mixedPinyin.lookup("exact",code,100))
            }
            for(prefix in listOf("he","hel","compu","w","a","key","Hel","HE"))
                assertEquals(prefix,original.english.complete(prefix,12),rebuilt.english.complete(prefix,12))
            for(initial in listOf("n","b","z")) assertEquals(original.initials(initial),rebuilt.initials(initial))
        } }
    }
    fun testDraftAddEditDeleteCompileAndRestore() {
        assertEquals(6,repository.libraries().size)
        assertEquals("skey_wubi86.tsv",repository.libraries().first().filename)
        val original=java.io.ByteArrayOutputStream()
        repository.export(repository.libraries().first().id,original)
        assertTrue(context.assets.open("dictionary-sources/skey_wubi86.tsv").use { it.readBytes() }.contentEquals(original.toByteArray()))
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
        assertEquals(6,repository.libraries().size)
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
    fun testSameNameImportValidatesBeforeReplacingAllSixLibraries() {
        val original=repository.libraries()
        original.forEach { lib ->
            val content=when(lib.kind) { "wubi" -> "测试\taaaa\t7\n"; "pinyin" -> "测试\tce shi\t7\n"; else -> "hello\t7\n" }
            val oldPage=repository.page(lib.id,0)
            repository.addFile(lib.filename,lib.kind,content.byteInputStream(),replaceExisting=true)
            assertEquals(content,repository.page(lib.id,0).text)
            assertEquals(original.map { it.id },repository.libraries().map { it.id })
            try { repository.savePage(lib.id,oldPage,content); fail("stale edit accepted") } catch(_: IllegalStateException) {}
            if(lib.filename.endsWith("_high.tsv")) {
                repository.addFile(lib.filename,lib.kind,"# empty\n".byteInputStream(),replaceExisting=true)
                assertEquals("# empty\n",repository.page(lib.id,0).text)
            }
        }
        repository.compile {}
        val active=repository.activeFile()
        original.forEach { lib ->
            val page=repository.page(lib.id,0)
            val sources=File(directory,"builtin-dictionaries-v3").list()!!.filter { it.startsWith("edit-") }.toSet()
            for(content in listOf("broken\t!\tbad\n", "# header\n".repeat(121)+"invalid!\t!\n")) {
                try { repository.addFile(lib.filename,lib.kind,content.byteInputStream(),replaceExisting=true); fail("bad replacement") }
                catch(_: IllegalArgumentException) {}
                assertEquals(page,repository.page(lib.id,0))
                assertEquals(sources,File(directory,"builtin-dictionaries-v3").list()!!.filter { it.startsWith("edit-") }.toSet())
                assertFalse(repository.dirty())
            }
            try { repository.addFile(lib.filename,if(lib.kind=="english") "wubi" else "english","hello\t9\n".byteInputStream(),replaceExisting=true); fail("wrong kind") }
            catch(_: IllegalArgumentException) {}
            assertEquals(page,repository.page(lib.id,0))
        }
        assertEquals(active,repository.activeFile())
        CompiledDictionary(active!!).use { assertEquals(listOf("hello"),it.english.complete("he",8)) }
        val english=original.first { it.filename=="skey_english.tsv" }
        repository.addFile(english.filename,"english","world\t8\n".byteInputStream(),replaceExisting=true)
        assertTrue(repository.dirty())
        assertEquals(active,repository.activeFile())
        repository.compile {}
        CompiledDictionary(repository.activeFile()!!).use { assertEquals(listOf("world"),it.english.complete("wo",8)); assertTrue(it.english.complete("he",8).isEmpty()) }
    }

    fun testTamperedSourceCannotReplaceActiveDictionary() {
        emptyFixture()
        repository.addFile("words.tsv","english","hello\t10\n".byteInputStream())
        repository.compile {}
        val current=repository.activeFile()
        val lib=repository.libraries().single()
        val source=File(directory,"builtin-dictionaries-v3/${lib.sourcePath}")
        source.writeText("hello\t10\ninvalid!\t3\n")
        try { repository.compile {}; fail("tampered source accepted") }
        catch(e: IllegalArgumentException) { assertTrue(e.message!!.contains("第 2 行")) }
        assertEquals(current,repository.activeFile())
        CompiledDictionary(current!!).use { assertEquals(listOf("hello"),it.english.complete("he",8)) }
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
    fun testWubiPriorityOverridesImportedWeightButNotPrefixRank() {
        emptyFixture()
        repository.addFile("skey_wubi86.tsv","wubi","普通词\taaaa\t999999\n".byteInputStream())
        repository.addFile("skey_wubi86_high.tsv","wubi","# priority\n优先词\taaaa\t0\n普通词\taaaa\t0\n".byteInputStream())
        repository.compile {}
        CompiledDictionary(repository.activeFile()!!).use {
            assertEquals(listOf("优先词","普通词"),it.wubi.exact("aaaa",10).map { it.text })
            assertEquals("普通词",it.wubi.prefix("aa",10).first().text)
        }
    }
    fun testEmptyPriorityLibrariesCompileAndRankOnlyExactMatches() {
        val priorityNames=setOf("skey_pinyin_high.tsv","skey_english_high.tsv")
        emptyFixture(priorityNames)
        repository.compile {}
        assertFalse(repository.dirty())
        CompiledDictionary(repository.activeFile()!!).use {
            assertTrue(it.engine.decodePinyin("nihao","",emptyList(),false,emptySet()).isEmpty())
            assertTrue(it.english.complete("cat",12).isEmpty())
        }
        emptyFixture()
        repository.addFile("skey_pinyin.tsv","pinyin","你好\tni hao\t1000000\n你\tni\t1000\n好\thao\t1000\n".byteInputStream())
        repository.addFile("normal.tsv","english","cat\t1\ncats\t99999\ncap\t100\n".byteInputStream())
        repository.addFile("skey_pinyin_high.tsv","pinyin","# priority\n".byteInputStream())
        repository.addFile("skey_english_high.tsv","english","# empty\n".byteInputStream())
        val py=repository.libraries().single { it.filename=="skey_pinyin_high.tsv" }
        val en=repository.libraries().single { it.filename=="skey_english_high.tsv" }
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
        // Empty high-priority files remain valid and cannot be renamed.
        repository.savePage(py.id,repository.page(py.id,0),"# priority\n")
        repository.savePage(en.id,repository.page(en.id,0),"# empty\n")
        try { repository.renameFile(en.id,"my-priority.tsv"); fail("Priority library renamed") } catch(_: IllegalArgumentException) { }
        repository.compile {}
        CompiledDictionary(repository.activeFile()!!).use {
            assertEquals("你好",it.engine.decodePinyin("nihao","",emptyList(),false,emptySet()).first().text)
            assertEquals(listOf("cats"),it.english.complete("cat",12))
        }
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

    fun testMalformedSearchIndexRejectedWithoutLeakingDatabase() {
        emptyFixture()
        repository.addFile("words.tsv","wubi","你\twq\t1\n".byteInputStream())
        repository.compile {}
        val file=repository.activeFile()!!
        android.database.sqlite.SQLiteDatabase.openDatabase(file.path,null,0).use {
            it.execSQL("DROP TABLE short_prefixes")
        }
        val baseline=CompiledDictionary.openSnapshotPaths()
        repeat(20) {
            try { CompiledDictionary(file).use { }; fail("Broken index accepted") }
            catch (_: android.database.sqlite.SQLiteException) { }
            assertEquals(baseline,CompiledDictionary.openSnapshotPaths())
        }
        assertTrue(CompiledDictionary.deleteIfUnused(file))
    }

    fun testAppliedSnapshotReleasesOldResourcesAndNeverFallsBack() {
        val engine=OfflineEngine(object: ContextWrapper(context) { override fun getNoBackupFilesDir()=directory; override fun getApplicationContext()=this })
        engine.load()
        val packaged=repository.defaultRuntime().absolutePath
        assertTrue(CompiledDictionary.openSnapshotPaths().contains(packaged))
        emptyFixture()
        fun snapshotRef(): java.lang.ref.WeakReference<Any> {
            val field=OfflineEngine::class.java.getDeclaredField("compiled").apply { isAccessible=true }
            return java.lang.ref.WeakReference(field.get(null))
        }
        var previous=packaged
        val metrics=org.json.JSONArray()
        repeat(12) { iteration ->
            val old=snapshotRef()
            val even=iteration%2==0
            val word=if(even) "新词" else "替换"
            val code=if(even) "aaaa" else "bbbb"
            val py=if(even) "xin ci" else "ti huan"
            val en=if(even) "bravo" else "charlie"
            repository.addFile("skey_wubi86.tsv","wubi","$word\t$code\t5\n".byteInputStream(),replaceExisting=true)
            repository.addFile("skey_pinyin.tsv","pinyin","$word\t$py\t5\n".byteInputStream(),replaceExisting=true)
            repository.addFile("skey_english.tsv","english","$en\t5\n".byteInputStream(),replaceExisting=true)
            repository.compile {}
            assertFalse(engine.isLoaded)
            assertFalse(CompiledDictionary.openSnapshotPaths().contains(previous))
            val staleFd=File("/proc/self/fd").listFiles().orEmpty().any { runCatching { it.canonicalPath==previous }.getOrDefault(false) }
            assertFalse("old SQLite file descriptor retained",staleFd)
            // Do not dereference the WeakReference before GC: ART may keep that temporary alive.
            repeat(5) { System.gc(); System.runFinalization(); Thread.sleep(40) }
            assertNull("old snapshot reachable after apply",old.get())
            assertEquals(word,engine.exactWubi(code,10).single().text)
            assertTrue(engine.exactWubi(if(even) "bbbb" else "aaaa",10).isEmpty())
            assertTrue(engine.decodePinyin(py.replace(" ",""),"",emptyList(),false,emptySet()).any { it.text==word })
            assertTrue(engine.candidates(py.replace(" ",""),false).any { it.text==word })
            assertEquals(listOf(en),engine.completeEnglish(en.take(2),10).map { it.text })
            assertTrue(engine.completeEnglish(if(even) "ch" else "br",10).isEmpty())
            assertTrue(engine.completeEnglish("you",10).isEmpty())
            assertFalse(CompiledDictionary.openSnapshotPaths().contains(packaged))
            previous=repository.activeFile()!!.absolutePath
            assertEquals(setOf(previous),CompiledDictionary.openSnapshotPaths().filter { it.startsWith(directory.path) }.toSet())
            assertEquals(1,File(previous).parentFile!!.listFiles()!!.count { it.name.startsWith("compiled-") && it.extension=="db" })
            System.gc()
            val runtime=Runtime.getRuntime()
            metrics.put(org.json.JSONObject().put("iteration",iteration).put("javaHeap",runtime.totalMemory()-runtime.freeMemory()).put("nativeHeap",android.os.Debug.getNativeHeapAllocatedSize()).put("openSnapshots",CompiledDictionary.openSnapshotPaths().size))
        }
        File(context.cacheDir,"snapshot-replacement-memory.json").writeText(metrics.toString())
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
