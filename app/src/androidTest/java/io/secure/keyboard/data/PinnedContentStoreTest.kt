package io.secure.keyboard.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.test.AndroidTestCase
import java.io.File

/** Independent fixtures for pinned content and OS event identity. Never writes the system clip. */
@Suppress("DEPRECATION")
class PinnedContentStoreTest : AndroidTestCase() {
    private lateinit var sandbox: IsolatedContext
    private lateinit var store: ContentStore
    private lateinit var settings: SettingsStore

    override fun setUp() {
        super.setUp()
        sandbox = IsolatedContext(context)
        sandbox.root.deleteRecursively()
        sandbox.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        store = ContentStore(sandbox)
        settings = SettingsStore(sandbox)
    }

    override fun tearDown() {
        sandbox.root.deleteRecursively()
        sandbox.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        super.tearDown()
    }

    fun testPinUnpinAndDeletePersistByIdAcrossInstances() {
        store.addClipboard("same")
        store.addClipboard("other")
        store.addPhrase("same")
        store.addPhrase("same")
        val clipboard = store.clipboard()
        val phrases = store.phrases()
        store.setClipboardPinned(clipboard.first().id, true)
        store.setPhrasePinned(phrases.last().id, true)
        val reopened = ContentStore(sandbox)
        assertEquals(listOf(true, false), reopened.clipboard().map { it.pinned })
        assertEquals(listOf(false, true), reopened.phrases().map { it.pinned })
        assertEquals(clipboard.map { it.id }, reopened.clipboard().map { it.id })
        assertEquals(phrases.map { it.id }, reopened.phrases().map { it.id })
        reopened.setClipboardPinned(clipboard.first().id, false)
        reopened.setPhrasePinned(phrases.last().id, false)
        assertTrue(store.clipboard().none { it.pinned })
        assertTrue(store.phrases().none { it.pinned })
        reopened.setClipboardPinned(clipboard.first().id, true)
        reopened.setPhrasePinned(phrases.last().id, true)
        reopened.deleteClipboard(clipboard.first().id)
        reopened.deletePhrase(phrases.last().id)
        assertEquals(listOf(clipboard.last().id), store.clipboard().map { it.id })
        assertEquals(listOf(phrases.first().id), store.phrases().map { it.id })
        assertTrue(store.clipboard().none { it.pinned })
        assertTrue(store.phrases().none { it.pinned })
    }

    fun testClipboardPinnedDisplayGroupKeepsRecencyWithoutChangingFifoOrder() {
        repeat(4) { store.addClipboard("copy$it") }
        val original = store.clipboard()
        store.setClipboardPinned(original[0].id, true)
        store.setClipboardPinned(original[2].id, true)
        assertEquals(listOf("copy2", "copy0", "copy3", "copy1"), store.clipboardForDisplay().map { it.text })
        assertEquals(original.map { it.id }, store.clipboard().map { it.id })
        store.setClipboardPinned(original[0].id, false)
        store.setClipboardPinned(original[2].id, false)
        assertEquals(original.asReversed().map { it.id }, store.clipboardForDisplay().map { it.id })
    }

    fun testPhraseGroupingPreservesManualOrderAndEditedPinnedText() {
        repeat(4) { store.addPhrase("phrase$it") }
        val original = store.phrases()
        store.setPhrasePinned(original[3].id, true)
        store.setPhrasePinned(original[1].id, true)
        assertEquals(listOf("phrase1", "phrase3", "phrase0", "phrase2"), store.phrasesForDisplay().map { it.text })
        assertEquals(original.map { it.id }, store.phrases().map { it.id })
        store.movePhrase(original[3].id, 0)
        store.updatePhrase(original[3].id, "edited\nfull phrase 👨‍👩‍👧‍👦")
        assertEquals(listOf(original[3].id, original[1].id, original[0].id, original[2].id), store.phrasesForDisplay().map { it.id })
        assertTrue(store.phrases().first().pinned)
        assertEquals("edited\nfull phrase 👨‍👩‍👧‍👦", store.phrasesForDisplay().first().text)
        store.setPhrasePinned(original[3].id, false)
        store.setPhrasePinned(original[1].id, false)
        assertEquals(store.phrases().map { it.id }, store.phrasesForDisplay().map { it.id })
        assertEquals(listOf(original[3].id, original[0].id, original[1].id, original[2].id), store.phrases().map { it.id })
    }

    fun testClipboardCapacityEvictsOldestUnpinnedAndKeepsNewestCopies() {
        settings.clipboardLimit = 3
        assertTrue(store.captureClipboard("same", 1000L, true))
        val pinnedId = store.clipboard().single().id
        store.setClipboardPinned(pinnedId, true)
        assertTrue(store.captureClipboard("second", 1001L, true))
        val secondId = store.clipboard().last().id
        assertTrue(store.captureClipboard("third", 1002L, true))
        val thirdId = store.clipboard().last().id
        assertTrue(store.captureClipboard("fourth", 1003L, true))
        val rows = store.clipboard()
        assertEquals(3, rows.size)
        assertEquals(3, rows.map { it.id }.distinct().size)
        assertEquals(listOf("same", "third", "fourth"), rows.map { it.text })
        assertEquals(listOf(pinnedId, thirdId), rows.take(2).map { it.id })
        assertFalse(rows.any { it.id == secondId })
        assertTrue(rows.first().pinned)
    }

    fun testAllPinnedCapacityStillAcceptsNewCopyAndNeverExceedsLimit() {
        settings.clipboardLimit = 3
        repeat(3) { store.addClipboard("copy$it") }
        store.clipboard().forEach { store.setClipboardPinned(it.id, true) }
        store.addClipboard("copy3")
        assertEquals(listOf("copy1", "copy2", "copy3"), store.clipboard().map { it.text })
        assertEquals(listOf(true, true, false), store.clipboard().map { it.pinned })
        repeat(20) { index ->
            store.clipboard().forEach { store.setClipboardPinned(it.id, true) }
            store.addClipboard("new$index")
            assertEquals(3, store.clipboard().size)
            assertEquals("new$index", store.clipboard().last().text)
        }
    }

    fun testReducingLimitTrimsUnpinnedFirstThenOldestPinned() {
        settings.clipboardLimit = 5
        repeat(5) { store.addClipboard("copy$it") }
        val original = store.clipboard()
        listOf(0, 2, 4).forEach { store.setClipboardPinned(original[it].id, true) }
        settings.clipboardLimit = 3
        store.trimClipboard()
        assertEquals(listOf("copy0", "copy2", "copy4"), store.clipboard().map { it.text })
        settings.clipboardLimit = 1
        store.trimClipboard()
        assertEquals(listOf("copy4"), store.clipboard().map { it.text })
        assertTrue(store.clipboard().single().pinned)
        store.addClipboard("latest")
        assertEquals(listOf("latest"), store.clipboard().map { it.text })
        assertFalse(store.clipboard().single().pinned)
    }

    fun testCategoryAndAllClearsDeletePinMetadataAndPreserveSettings() {
        settings.theme = "dark"
        settings.clipboardLimit = 7
        store.addClipboard("private clipboard")
        store.addPhrase("private phrase")
        store.setClipboardPinned(store.clipboard().single().id, true)
        store.setPhrasePinned(store.phrases().single().id, true)
        store.addDictionary("word", "code")
        val dictionarySnapshot = store.dictionary()
        store.clearClipboard()
        assertTrue(store.clipboard().isEmpty())
        assertTrue(store.phrases().single().pinned)
        assertSame(dictionarySnapshot, store.dictionary())
        store.clearPhrases()
        assertTrue(store.phrases().isEmpty())
        assertFalse(contentFile().readText().contains("pinned"))
        store.addClipboard("new copy")
        store.addPhrase("new phrase")
        store.setClipboardPinned(store.clipboard().single().id, true)
        store.setPhrasePinned(store.phrases().single().id, true)
        File(contentFile().parentFile, "content.json.bak").writeText("private pin")
        File(sandbox.cacheDir, "content.tmp").writeText("private pin")
        store.clearAll()
        assertTrue(contentFile().parentFile!!.listFiles().orEmpty().isEmpty())
        assertTrue(sandbox.cacheDir.listFiles().orEmpty().isEmpty())
        assertTrue(ContentStore(sandbox).clipboardForDisplay().isEmpty())
        assertTrue(ContentStore(sandbox).phrasesForDisplay().isEmpty())
        assertEquals("dark", SettingsStore(sandbox).theme)
        assertEquals(7, SettingsStore(sandbox).clipboardLimit)
    }

    fun testQueuedCaptureAndStalePinActionsCannotReviveClearedContent() {
        store.addClipboard("old copy")
        store.addPhrase("old phrase")
        val clipboardId = store.clipboard().single().id
        val phraseId = store.phrases().single().id
        store.setClipboardPinned(clipboardId, true)
        store.setPhrasePinned(phraseId, true)
        val beforeClear = store.clipboardGeneration()
        val other = ContentStore(sandbox)
        other.clearClipboard()
        other.clearPhrases()
        assertFalse(store.captureClipboard("queued", 1200L, true, beforeClear))
        store.setClipboardPinned(clipboardId, true)
        store.setPhrasePinned(phraseId, true)
        assertTrue(store.clipboard().isEmpty())
        assertTrue(store.phrases().isEmpty())
        assertTrue(store.captureClipboard("fresh", 1201L, true, store.clipboardGeneration()))
        val beforeDelete = store.clipboardGeneration()
        val freshId = store.clipboard().single().id
        store.setClipboardPinned(freshId, true)
        other.deleteClipboard(freshId)
        assertFalse(store.captureClipboard("queued deleted", 1202L, true, beforeDelete))
        store.setClipboardPinned(freshId, true)
        assertTrue(store.clipboard().isEmpty())
        val beforeAll = store.clipboardGeneration()
        other.clearAll()
        assertFalse(store.captureClipboard("queued all", 1203L, true, beforeAll))
        assertTrue(store.clipboard().isEmpty())
    }

    fun testLegacyRowsDefaultToUnpinnedAndInvalidPinRefusesOverwrite() {
        contentFile().writeText("""{"clipboard":[{"id":"c","text":"old copy"}],"phrases":[{"id":"p","text":"old phrase"}]}""")
        assertFalse(store.clipboard().single().pinned)
        assertFalse(store.phrases().single().pinned)
        store.setClipboardPinned("c", true)
        store.setPhrasePinned("p", true)
        assertTrue(ContentStore(sandbox).clipboard().single().pinned)
        assertTrue(ContentStore(sandbox).phrases().single().pinned)
        val corrupt = """{"clipboard":[{"id":"c","text":"old copy","pinned":"yes"}]}"""
        contentFile().writeText(corrupt)
        try {
            store.setClipboardPinned("c", false)
            fail("Malformed pin metadata must not silently overwrite content")
        } catch (_: ContentStore.ContentReadException) {}
        assertEquals(corrupt, contentFile().readText())
    }

    fun testPinMutationsPreserveDictionaryOnlySnapshotAndRevision() {
        store.addDictionary("word", "code")
        val snapshot = store.dictionary()
        val revision = store.dictionaryRevision()
        store.addClipboard("clip")
        store.addPhrase("phrase")
        store.setClipboardPinned(store.clipboard().single().id, true)
        store.setPhrasePinned(store.phrases().single().id, true)
        assertSame(snapshot, ContentStore(sandbox).dictionary())
        assertEquals(revision, store.dictionaryRevision())
        store.clearAll()
        assertNotSame(snapshot, store.dictionary())
        assertTrue(store.dictionary().isEmpty())
    }

    fun testDuplicateCallbackAndReopenCaptureExactlyOneCopyInEitherOrder() {
        assertTrue(store.captureClipboard("same", 2000L, newCopyEvent = true))
        assertFalse(store.captureClipboard("same", 2000L, newCopyEvent = true))
        assertFalse(store.captureClipboard("same", 2000L, newCopyEvent = false))
        val reopened = ContentStore(sandbox)
        assertFalse(reopened.captureClipboard("same", 2000L, newCopyEvent = false))
        assertFalse(reopened.captureClipboard("same", 2000L, newCopyEvent = true))
        assertEquals(1, store.clipboard().size)
        assertTrue(reopened.captureClipboard("same", 2001L, newCopyEvent = false))
        assertFalse(store.captureClipboard("same", 2001L, newCopyEvent = true))
        assertEquals(1, store.clipboard().size)
        assertEquals(1, store.clipboard().map { it.id }.distinct().size)
    }

    fun testRapidIdenticalTextWithDistinctCopyTokensIsNotDebounced() {
        settings.clipboardLimit = 100
        repeat(60) { index ->
            assertTrue(store.captureClipboard("identical text", 3000L + index, true))
            assertFalse(store.captureClipboard("identical text", 3000L + index, true))
        }
        assertEquals(1, store.clipboard().size)
        assertEquals(1, store.clipboard().map { it.id }.distinct().size)
        assertTrue(store.clipboard().all { it.text == "identical text" })
    }

    fun testClearSuppressionAlsoRejectsDelayedCallbackUntilNewCopyToken() {
        assertTrue(store.captureClipboard("old", 4000L, true))
        store.clearClipboard()
        // clearClipboard may read a live OS timestamp, without modifying the system clip.
        val suppressed = sandbox.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getLong("lastClipboardToken", 0L)
        assertTrue(suppressed > 0L)
        assertFalse(store.captureClipboard("old", suppressed, true))
        assertFalse(ContentStore(sandbox).captureClipboard("old", suppressed, false))
        assertTrue(store.clipboard().isEmpty())
        assertTrue(store.captureClipboard("old", suppressed + 1L, true))
        assertEquals(1, store.clipboard().size)
    }

    private fun contentFile() = File(sandbox.noBackupFilesDir, "content/content.json")

    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        val root = File(base.noBackupFilesDir, "pinned-content-instrumentation-tests")
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getExternalCacheDirs(): Array<File> = emptyArray()
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("pinned-content-test-$name", mode)
    }
}
