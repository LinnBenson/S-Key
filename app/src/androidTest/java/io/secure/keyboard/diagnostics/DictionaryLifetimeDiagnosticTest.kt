package io.secure.keyboard.diagnostics

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.test.AndroidTestCase
import io.secure.keyboard.data.ContentStore
import io.secure.keyboard.data.DictionaryKind
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.security.MessageDigest

/** One fixed app-owned fixture path per test process; no real content, clipboard or prefs touched. */
@Suppress("DEPRECATION")
class DictionaryLifetimeDiagnosticTest : AndroidTestCase() {
    private lateinit var sandbox: LifetimeSandbox
    private lateinit var store: ContentStore
    private lateinit var probe: LifetimeMemoryProbe
    private val chinese = DictionaryKind.CHINESE
    private val english = DictionaryKind.ENGLISH
    private var cycleOperationEndMillis = 0L

    override fun setUp() {
        super.setUp()
        sandbox = LifetimeSandbox(context)
        check(!sandbox.root.exists()) { "Previous diagnostic left evidence; preserve it before retrying" }
        sandbox.root.mkdirs()
        store = ContentStore(sandbox)
        probe = LifetimeMemoryProbe(context, "dictionary")
    }

    override fun tearDown() {
        try {
            if (::probe.isInitialized) probe.stop()
            if (::store.isInitialized) store.clearAll()
            if (::sandbox.isInitialized) sandbox.close()
        } finally { super.tearDown() }
    }

    fun testSingleCycleCalibrationNoForcedGc() {
        val started = android.os.SystemClock.elapsedRealtime()
        val zh = (0 until 10_000).joinToString("\n") { "中$it\tcode" }
        val en = (0 until 10_000).joinToString("\n") { "Entry $it\tcode" }
        fullCycle(zh, en, 10_000)
        probe.recover(checkpoint = true, operationEndMillis = cycleOperationEndMillis)
        probe.sample("calibration_complete", extra = JSONObject().put("calibrationOnly", true)
            .put("cycleMillisIncluding10sCheckpointRecovery", android.os.SystemClock.elapsedRealtime() - started))
    }

    fun testFiveFullTwoLanguageImportLoadClearCyclesNoForcedGc() {
        val chineseFixture = (0 until 10_000).joinToString("\n") { "中$it\tcode" }
        val englishFixture = (0 until 10_000).joinToString("\n") { "Entry $it\tcode" }
        probe.sample("fixtures_ready", extra = JSONObject().put("chineseFixtureSha256", hash(chineseFixture)).put("englishFixtureSha256", hash(englishFixture)).put("rowsPerLanguage", 10_000))
        probe.start()
        val retired = mutableListOf<WeakReference<*>>()
        repeat(5) { index ->
            probe.cycle = index + 1
            retired += fullCycle(chineseFixture, englishFixture, 10_000)
            probe.recover(checkpoint = index in listOf(0, 2, 4), final = index == 4, operationEndMillis = cycleOperationEndMillis) { JSONObject().put("oldSnapshotsStillReachable", retired.count { it.get() != null }) }
        }
        probe.stop()
        probe.observeWeakReferences("natural_final_snapshot_ownership", retired, false)
        // Exact cache ownership is checked without depending on collection timing or PSS recovery.
        assertTrue(store.dictionary(chinese).isEmpty())
        assertTrue(store.dictionary(english).isEmpty())
    }

    /** Separate fresh-process invocation. Never merge these samples into natural PSS results. */
    fun testClearedSnapshotsCollectibleForcedGcDiagnosticOnly() {
        val refs = fullCycle("中文\tcode", "English\tcode", 1)
        probe.observeWeakReferences("forced_gc_snapshot_collectibility", refs, true)
        assertTrue("Old snapshots still strongly reachable after diagnostic GC", refs.all { it.get() == null })
    }

    fun testNearFourMillionCharactersAndIncrementalValidAccumulationNoForcedGc() {
        // Three 1.3-million-character batches together remain below the per-language four-million cap.
        val first = longFixture("a", 100, 13_000)
        val second = longFixture("b", 100, 13_000)
        val third = longFixture("c", 100, 13_000)
        probe.sample("large_fixture_metadata", extra = JSONObject().put("firstCharacters", first.length)
            .put("secondCharacters", second.length).put("thirdCharacters", third.length)
            .put("firstSha256", hash(first)).put("secondSha256", hash(second)).put("thirdSha256", hash(third)))
        probe.start()
        for (kind in listOf(chinese, english)) {
            for ((index, fixture) in listOf(first, second, third).withIndex()) {
                probe.stage = "long_${kind.name}_${index + 1}"
                assertEquals(100, store.importDictionary(fixture, kind = kind))
                assertEquals((index + 1) * 100, store.dictionary(kind).size)
                assertEquals("Every valid word preserved", 13_000, store.dictionary(kind).last().word.length)
                probe.sample("import_and_load_complete", extra = JSONObject().put("storedBytes", contentFile().length()).put("rows", store.dictionary(kind).size))
            }
        }
        probe.stage = "clipboard_and_emoji_with_large_dictionaries"
        assertTrue(store.captureClipboard("synthetic diagnostic", 0, newCopyEvent = true))
        store.useEmoji("🙂")
        for (kind in listOf(chinese, english)) assertEquals(300, store.dictionary(kind).size)
        probe.sample("shared_file_mutations_complete")
        store.clearDictionary(chinese)
        assertEquals(300, store.dictionary(english).size)
        store.clearDictionary(english)
        probe.stage = "long_cleared"
        probe.recover(final = true)
        probe.stop()
    }

    fun testPendingSameKindImportCannotResurrectClearedRows() {
        store.importDictionary("中文\tcode")
        store.importDictionary("English\tcode", kind = english)
        val captured = store.captureDictionaryEpoch(chinese)
        store.clearDictionary(chinese)
        val error = runCatching { store.importDictionary("late\tcode", captured, chinese) }.exceptionOrNull()
        assertNotNull("Import captured before clear must fail", error)
        assertTrue(store.dictionary(chinese).isEmpty())
        assertEquals("English", store.dictionary(english).single().word)
    }

    private fun fullCycle(zh: String, en: String, rows: Int): List<WeakReference<*>> {
        probe.stage = "empty"
        probe.sample("before_import")
        assertEquals(rows, store.importDictionary(zh, kind = chinese))
        probe.stage = "chinese_loaded"
        val oldChinese = store.dictionary(chinese)
        assertEquals(rows, oldChinese.size)
        probe.sample("one_language_loaded")
        assertEquals(rows, store.importDictionary(en, kind = english))
        probe.stage = "both_loaded"
        val oldEnglish = store.dictionary(english)
        assertEquals(rows, oldEnglish.size)
        assertSame(oldChinese, store.dictionary(chinese))
        // Compare every literal row, including the last: no silent truncation or new data cap.
        assertEquals(zh, store.exportDictionary(chinese).substringAfter('\n'))
        assertEquals(en, store.exportDictionary(english).substringAfter('\n'))
        assertTrue(store.captureClipboard("synthetic diagnostic", 0, newCopyEvent = true))
        store.useEmoji("🙂")
        probe.sample("both_loaded_after_shared_file_mutations", extra = JSONObject().put("storedBytes", contentFile().length()))
        val refs = listOf(WeakReference(oldChinese), WeakReference(oldEnglish))
        store.clearDictionary(chinese)
        assertTrue(store.dictionary(chinese).isEmpty())
        assertSame(oldEnglish, store.dictionary(english))
        probe.stage = "chinese_cleared_english_preserved"
        probe.sample("one_cleared")
        store.clearDictionary(english)
        assertTrue(store.dictionary(english).isEmpty())
        cycleOperationEndMillis = android.os.SystemClock.elapsedRealtime()
        probe.stage = "both_cleared"
        probe.sample("both_cleared")
        return refs
    }

    private fun contentFile() = File(sandbox.noBackupFilesDir, "content/content.json")
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun longFixture(letter: String, rows: Int, length: Int) = (0 until rows).joinToString("\n") { index ->
        // Distinct row prefixes and valid literal words; the English reading is alphabetic.
        ("$letter$index" + letter.repeat(length)).take(length) + "\tcode"
    }
}

/** All cleanup APIs are redirected, including ContentStore's external-cache purge. */
internal class LifetimeSandbox(base: Context) : ContextWrapper(base), AutoCloseable {
    private val identity = java.util.UUID.randomUUID().toString()
    val root = File(base.filesDir, "lifetime-dictionary-fixture-$identity")
    private val preferenceNames = mutableSetOf<String>()
    override fun getApplicationContext(): Context = this
    override fun getNoBackupFilesDir() = File(root, "no_backup").apply { mkdirs() }
    override fun getFilesDir() = File(root, "files").apply { mkdirs() }
    override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
    override fun getExternalCacheDirs(): Array<File> = arrayOf(File(root, "external-cache").apply { mkdirs() })
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        val privateName = "lifetime-diagnostic-$identity-$name"
        preferenceNames.add(privateName)
        return baseContext.getSharedPreferences(privateName, mode)
    }
    override fun close() {
        // Test paths are one-shot; evict their process cache without touching the OS clipboard.
        val content = ContentStore(this)
        ContentStore::class.java.getDeclaredMethod("invalidateDictionarySnapshots").apply { isAccessible = true }.invoke(content)
        root.deleteRecursively()
        preferenceNames.forEach { baseContext.deleteSharedPreferences(it) }
    }
}
