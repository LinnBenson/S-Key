package io.secure.keyboard.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.test.AndroidTestCase
import java.io.File
import org.json.JSONObject

/** Private isolated fixtures; never change the system clipboard or the user's real content. */
@Suppress("DEPRECATION")
class BilingualContentStoreTest : AndroidTestCase() {
    private lateinit var sandbox: IsolatedContext
    private lateinit var store: ContentStore
    private val chinese = DictionaryKind.CHINESE
    private val english = DictionaryKind.ENGLISH

    override fun setUp() {
        super.setUp()
        sandbox = IsolatedContext(context)
        sandbox.root.deleteRecursively()
        sandbox.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        store = ContentStore(sandbox)
    }

    override fun tearDown() {
        store.clearAll()
        sandbox.root.deleteRecursively()
        sandbox.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        super.tearDown()
    }

    fun testPresetModelPersistsAndBlankResets() {
        store.saveAiPreset(null, "模型测试", "指令", "high", " model-a ")
        val item = store.aiPresets().single()
        assertEquals("model-a", item.modelId)
        assertEquals(item, ContentStore(sandbox).aiPresets().single())
        assertEquals(item, store.settingsSnapshot().aiPresets.single())
        val backup = java.io.ByteArrayOutputStream()
        store.writeBackup(backup)
        store.restoreBackup(backup.toByteArray().inputStream())
        assertEquals(item.modelId, store.aiPresets().single().modelId)
        store.saveAiPreset(item.id, item.name, item.prompt, item.reasoningEffort, "")
        assertEquals("", store.aiPresets().single().modelId)
        try { store.saveAiPreset(item.id, item.name, item.prompt, "low", "bad id"); fail("Invalid model accepted") }
        catch (_: IllegalArgumentException) {}
    }
    fun testNamedAiPresetsPersistAndSupportEditingAndOrdering() {
        store.saveAiPreset(null, "润色", "保留原意并润色")
        store.saveAiPreset(null, "简短", "缩短到两句话")
        val first = store.aiPresets().first()
        val last = store.aiPresets().last()
        store.moveAiPreset(last.id, -1)
        assertEquals(listOf("简短", "润色"), ContentStore(sandbox).aiPresets().map { it.name })
        store.saveAiPreset(first.id, "正式润色", "改成正式语气")
        assertEquals("改成正式语气", store.aiPresets().last().prompt)
        assertEquals(first.id, store.aiPresets().last().id)
        store.moveAiPreset(last.id, 1)
        assertEquals("正式润色", store.aiPresets().first().name)
    }

    fun testAiPresetValidationAndDeletionPreserveOtherContent() {
        store.addPhrase("保留常用语")
        store.saveAiPreset(null, "邮件", "润色邮件")
        val preset = store.aiPresets().single()
        for ((name, prompt) in listOf("邮件" to "重复", "" to "提示词", "长".repeat(41) to "提示词", "名称" to "", "名称" to "长".repeat(ContentStore.MAX_AI_PROMPT_CHARACTERS + 1))) {
            try { store.saveAiPreset(null, name, prompt); fail("Invalid preset accepted") } catch (_: IllegalArgumentException) {}
        }
        assertEquals(listOf(preset), store.aiPresets())
        store.deleteAiPreset(preset.id)
        assertTrue(store.aiPresets().isEmpty())
        assertEquals("保留常用语", store.phrases().single().text)
    }

    fun testLongAiPromptsRoundTripAtMaximumInventory() {
        val prompt = "长".repeat(20_000)
        repeat(100) { store.saveAiPreset(null, "指令$it", prompt) }
        val output = java.io.ByteArrayOutputStream()
        store.writeBackup(output)
        store.restoreBackup(output.toByteArray().inputStream())
        assertEquals(100, store.aiPresets().size)
        assertTrue(store.aiPresets().all { it.prompt == prompt })
    }

    fun testClearAllRemovesAiPresetsAndStaleEditsCannotRecreateThem() {
        store.saveAiPreset(null, "旧配置", "旧提示词")
        val old = store.aiPresets().single()
        val epoch = store.customAiEpoch()
        ContentStore(sandbox).clearAll()
        assertTrue(store.customAiEpoch() > epoch)
        assertTrue(store.aiPresets().isEmpty())
        try { store.saveAiPreset(old.id, "旧配置", "延迟编辑"); fail("Deleted preset recreated") }
        catch (_: IllegalArgumentException) {}
        assertTrue(store.aiPresets().isEmpty())
    }

    fun testLegacyChineseFixtureReadsWithoutRewriteAndEnglishWritesPreserveIt() {
        val longCode = "Legacy NÜ ' " + "x".repeat(80)
        val original = JSONObject()
            .put("dictionary", org.json.JSONArray().put(JSONObject().put("id", "legacy-one")
                .put("word", "  旧词𠀀  ").put("reading", longCode)).put(JSONObject()
                .put("id", "legacy-two").put("word", "CasePreserved").put("reading", "ABC")))
            .put("phrases", org.json.JSONArray().put(JSONObject().put("id", "phrase")
                .put("text", "保留常用语").put("pinned", true)))
            .put("futureMetadata", JSONObject().put("schema", 9).put("keep", "unchanged"))
            .toString(2)
        contentFile().writeText(original)
        val chineseRows = store.dictionary()
        assertEquals(listOf("legacy-one", "legacy-two"), chineseRows.map { it.id })
        assertEquals(listOf("  旧词𠀀  ", "CasePreserved"), chineseRows.map { it.word })
        assertEquals(listOf(longCode, "ABC"), chineseRows.map { it.reading })
        assertTrue(store.dictionary(english).isEmpty())
        assertTrue(ContentStore(sandbox).dictionary(english).isEmpty())
        assertEquals("Opening an old file must not eagerly migrate it", original, contentFile().readText())
        store.addDictionary("iPhone", "IPHONE", english)
        val changed = JSONObject(contentFile().readText())
        assertEquals(JSONObject(original).getJSONArray("dictionary").toString(), changed.getJSONArray("dictionary").toString())
        assertEquals(JSONObject(original).getJSONObject("futureMetadata").toString(), changed.getJSONObject("futureMetadata").toString())
        assertEquals(JSONObject(original).getJSONArray("phrases").toString(), changed.getJSONArray("phrases").toString())
        assertSame(chineseRows, store.dictionary())
        assertEquals(listOf("content.json"), contentFile().parentFile!!.listFiles()!!.map { it.name })
    }

    fun testIndependentCrudPersistsLiteralValuesAndExistingCallsDefaultToChinese() {
        store.addDictionary("中文", "shared")
        store.addDictionary("OpenAI 𠀀, café e\u0301!", "SHARED", english)
        val originalChinese = store.dictionary().single()
        val originalEnglish = store.dictionary(english).single()
        val reopened = ContentStore(sandbox)
        assertEquals(listOf(originalChinese), reopened.dictionary(chinese))
        assertEquals(listOf(originalEnglish), reopened.dictionary(english))
        reopened.updateDictionary(originalEnglish.id, "iPhone Phrase", "IPHONe", english)
        assertEquals(listOf(originalChinese), store.dictionary())
        assertEquals("iPhone Phrase", store.dictionary(english).single().word)
        assertEquals("IPHONe", store.dictionary(english).single().reading)
        assertEquals(originalEnglish.id, store.dictionary(english).single().id)
        reopened.updateDictionary(originalChinese.id, "修改中文", "long ' 中文".repeat(10))
        assertEquals("iPhone Phrase", store.dictionary(english).single().word)
        reopened.deleteDictionary(originalChinese.id)
        assertTrue(store.dictionary().isEmpty())
        assertEquals(1, store.dictionary(english).size)
        reopened.deleteDictionary(originalEnglish.id, english)
        assertTrue(store.dictionary(english).isEmpty())
    }

    fun testIndependentImportsExportsPreserveDuplicatesCaseBomAndCrLf() {
        store.importDictionary("中文\tcode")
        val chineseBefore = store.exportDictionary()
        assertEquals(3, store.importDictionary("\uFEFFword\treading\r\nHello World!\tHELLO\r\nHello World!\tHELLO\r\né e\u0301 𠀀\tUnicode\r\n", kind = english))
        assertEquals(chineseBefore, store.exportDictionary())
        assertEquals("word\treading\nHello World!\tHELLO\nHello World!\tHELLO\né e\u0301 𠀀\tUnicode", store.exportDictionary(english))
        assertEquals(3, store.dictionary(english).map { it.id }.distinct().size)
        val exported = store.exportDictionary(english)
        store.clearDictionary(english)
        assertEquals(3, store.importDictionary(exported, kind = english))
        assertEquals(exported, store.exportDictionary(english))
        assertEquals(chineseBefore, store.exportDictionary())
    }

    fun testEnglishValidationAppliesToAddEditAndAllOrNothingImports() {
        store.addDictionary("Original", "CODE", english)
        store.addDictionary("中文不受影响", "long-code")
        val before = contentFile().readText()
        val id = store.dictionary(english).single().id
        val invalid = listOf("word" to "a".repeat(41), "word" to "code1", "word" to "NÜ",
            "word" to "co de", " leading" to "code", "trailing " to "code", "a\tb" to "code")
        for ((word, code) in invalid) {
            assertInvalid { store.addDictionary(word, code, english) }
            assertInvalid { store.updateDictionary(id, word, code, english) }
            assertInvalid { store.importDictionary("valid\tvalid\n$word\t$code", kind = english) }
            assertEquals("Validation failure must not write either language", before, contentFile().readText())
        }
        for (size in listOf(1, 4, 5, 40)) store.addDictionary("Boundary $size", "a".repeat(size), english)
        assertEquals(5, store.dictionary(english).size)
        assertEquals(1, store.dictionary().size)
    }

    fun testEachDictionaryHasIndependentTenThousandEntryCapacity() {
        val chineseTsv = (0 until ContentStore.MAX_DICTIONARY_ENTRIES).joinToString("\n") { "中$it\tcode$it" }
        val englishTsv = (0 until ContentStore.MAX_DICTIONARY_ENTRIES).joinToString("\n") { "Entry $it\tcode" }
        assertEquals(10_000, store.importDictionary(chineseTsv))
        assertEquals(10_000, store.importDictionary(englishTsv, kind = english))
        val before = contentFile().readText()
        for (kind in DictionaryKind.entries) {
            assertInvalid { store.addDictionary("extra", "extra", kind) }
            assertInvalid { store.importDictionary("extra\textra", kind = kind) }
            assertEquals(before, contentFile().readText())
            assertEquals(10_000, store.dictionary(kind).size)
        }
        val first = store.dictionary(english).first()
        store.updateDictionary(first.id, "Edited", "edited", english)
        assertEquals("Edited", store.dictionary(english).first().word)
        store.deleteDictionary(first.id, english)
        store.addDictionary("Replacement", "replacement", english)
        assertEquals(10_000, store.dictionary(english).size)
        assertEquals(10_000, store.dictionary().size)
    }

    fun testCategoryClearsAndClearAllKeepSettingsWhileRemovingCorrectContent() {
        val settings = SettingsStore(sandbox)
        settings.theme = "dark"
        settings.toolbarPins = listOf("dictionary", "clipboard")
        settings.lastChineseLanguage = false
        val profile = settings.profile(true, false).copy(heightDp = 317, split = true)
        settings.saveProfile(true, false, profile)
        store.addDictionary("中文", "code")
        store.addDictionary("English", "code", english)
        store.addPhrase("phrase")
        store.clearDictionary()
        assertTrue(store.dictionary().isEmpty())
        assertEquals(listOf("English"), store.dictionary(english).map { it.word })
        store.addDictionary("中文二", "code")
        store.clearDictionary(english)
        assertTrue(store.dictionary(english).isEmpty())
        assertEquals(listOf("中文二"), store.dictionary().map { it.word })
        assertEquals(1, store.phrases().size)
        store.addDictionary("English again", "again", english)
        store.clearAll()
        assertTrue(store.dictionary().isEmpty())
        assertTrue(store.dictionary(english).isEmpty())
        assertTrue(store.phrases().isEmpty())
        assertFalse(contentFile().exists())
        val restored = SettingsStore(sandbox)
        assertEquals("dark", restored.theme)
        assertFalse(restored.lastChineseLanguage)
        assertEquals(listOf("dictionary", "clipboard"), restored.toolbarPins)
        assertEquals(profile, restored.profile(true, false))
    }

    fun testEachMutationInvalidatesOnlySameKindAcrossInstances() {
        val other = ContentStore(sandbox)
        for (kind in DictionaryKind.entries) {
            val untouched = if (kind == chinese) english else chinese
            store.addDictionary("untouched", "untouched", untouched)
            val unaffectedRows = store.dictionary(untouched)
            val unaffectedRevision = store.dictionaryRevision(untouched)
            fun assertMutation(action: () -> Unit) {
                val oldRows = store.dictionary(kind)
                val oldRevision = store.dictionaryRevision(kind)
                action()
                assertTrue(store.dictionaryRevision(kind) > oldRevision)
                assertNotSame(oldRows, store.dictionary(kind))
                assertSame(unaffectedRows, other.dictionary(untouched))
                assertEquals(unaffectedRevision, other.dictionaryRevision(untouched))
            }
            assertMutation { other.addDictionary("one", "one", kind) }
            assertMutation { other.updateDictionary(store.dictionary(kind).first().id, "updated", "updated", kind) }
            assertMutation { other.importDictionary("two\ttwo", kind = kind) }
            assertMutation { other.deleteDictionary(store.dictionary(kind).first().id, kind) }
            assertMutation { other.clearDictionary(kind) }
        }
    }

    fun testBothImmutableSnapshotsSurviveUnrelatedCategoryWrites() {
        store.addDictionary("中文", "code")
        store.addDictionary("English", "code", english)
        val rows = DictionaryKind.entries.associateWith { store.dictionary(it) }
        val revisions = DictionaryKind.entries.associateWith { store.dictionaryRevision(it) }
        for (kind in DictionaryKind.entries) {
            try {
                (rows.getValue(kind) as MutableList<ContentStore.DictionaryEntry>).clear()
                fail("Both dictionary snapshots must be immutable")
            } catch (_: UnsupportedOperationException) {}
        }
        store.addClipboard("clipboard")
        store.addPhrase("phrase")
        store.useEmoji("😀")
        store.clearClipboard()
        store.clearPhrases()
        store.clearEmoji()
        for (kind in DictionaryKind.entries) {
            assertSame(rows.getValue(kind), ContentStore(sandbox).dictionary(kind))
            assertEquals(revisions.getValue(kind), store.dictionaryRevision(kind))
        }
    }

    fun testDelayedImportsAreCancelledOnlyBySameKindDeleteOrClear() {
        val other = ContentStore(sandbox)
        for (kind in DictionaryKind.entries) {
            val unrelated = if (kind == chinese) english else chinese
            store.addDictionary("entry", "entry", kind)
            val epoch = store.captureDictionaryEpoch(kind)
            other.addDictionary("other", "other", unrelated)
            other.deleteDictionary(other.dictionary(unrelated).first().id, unrelated)
            other.clearDictionary(unrelated)
            assertEquals(epoch, store.captureDictionaryEpoch(kind))
            assertEquals(1, store.importDictionary("fresh\tfresh", epoch, kind))
            val beforeDelete = store.captureDictionaryEpoch(kind)
            other.deleteDictionary(store.dictionary(kind).first().id, kind)
            assertStaleImportRejected(kind, beforeDelete)
            val beforeClear = store.captureDictionaryEpoch(kind)
            other.clearDictionary(kind)
            assertStaleImportRejected(kind, beforeClear)
        }
        val epochs = DictionaryKind.entries.associateWith { store.captureDictionaryEpoch(it) }
        other.clearAll()
        for (kind in DictionaryKind.entries) assertStaleImportRejected(kind, epochs.getValue(kind))
    }

    fun testSnapshotsRevisionsAndDeletionEpochsArePathIsolatedForBothKinds() {
        val independentContext = IsolatedContext(sandbox)
        val independent = ContentStore(independentContext)
        try {
            independent.clearAll()
            for (kind in DictionaryKind.entries) {
                store.addDictionary("primary", "primary", kind)
                independent.addDictionary("independent", "independent", kind)
            }
            val rows = DictionaryKind.entries.associateWith { store.dictionary(it) }
            val revisions = DictionaryKind.entries.associateWith { store.dictionaryRevision(it) }
            val epochs = DictionaryKind.entries.associateWith { store.captureDictionaryEpoch(it) }
            independent.clearAll()
            for (kind in DictionaryKind.entries) {
                assertSame(rows.getValue(kind), store.dictionary(kind))
                assertEquals(revisions.getValue(kind), store.dictionaryRevision(kind))
                assertEquals(epochs.getValue(kind), store.captureDictionaryEpoch(kind))
                assertEquals(1, store.importDictionary("fresh\tfresh", epochs.getValue(kind), kind))
                assertTrue(independent.dictionary(kind).isEmpty())
            }
        } finally {
            independent.clearAll()
            independentContext.root.deleteRecursively()
        }
    }

    fun testExternalSharedFileReplacementInvalidatesBothKindsBeforeReturningFreshRows() {
        store.addDictionary("中文", "code")
        store.addDictionary("English", "code", english)
        for (changedKind in DictionaryKind.entries) {
            val rows = DictionaryKind.entries.associateWith { store.dictionary(it) }
            val revisions = DictionaryKind.entries.associateWith { store.dictionaryRevision(it) }
            val root = JSONObject(contentFile().readText())
            root.getJSONArray(changedKind.storageKey).getJSONObject(0)
                .put("word", "Externally changed ${changedKind.name}")
            contentFile().writeText(root.toString())
            // Read the unchanged kind first: its old rows are still unsafe to reuse because the
            // shared file was replaced without an app-owned atomic-write stamp update.
            val readFirst = if (changedKind == chinese) english else chinese
            store.dictionary(readFirst)
            for (kind in DictionaryKind.entries) {
                assertTrue(store.dictionaryRevision(kind) > revisions.getValue(kind))
                assertNotSame(rows.getValue(kind), store.dictionary(kind))
            }
            assertEquals("Externally changed ${changedKind.name}", store.dictionary(changedKind).single().word)
        }
    }

    fun testMalformedEnglishArrayFailsClosedForSharedContentAndEvictsBothSnapshots() {
        store.addDictionary("中文", "code")
        store.addDictionary("English", "code", english)
        DictionaryKind.entries.forEach { store.dictionary(it) }
        val revisions = DictionaryKind.entries.associateWith { store.dictionaryRevision(it) }
        val original = contentFile().readText()
        for (bad in listOf("not an array", org.json.JSONArray().put(JSONObject().put("id", 5)
                .put("word", "word").put("reading", "code")))) {
            val corrupt = JSONObject(original).put("englishDictionary", bad).toString()
            contentFile().writeText(corrupt)
            try {
                store.phrases()
                fail("Other-category reads must detect English structural corruption")
            } catch (_: ContentStore.ContentReadException) {}
            for (kind in DictionaryKind.entries) {
                assertTrue(store.dictionaryRevision(kind) > revisions.getValue(kind))
                try {
                    store.dictionary(kind)
                    fail("Neither dictionary can serve cached personal content after shared-file corruption")
                } catch (_: ContentStore.ContentReadException) {}
                try {
                    store.addDictionary("must not overwrite", "code", kind)
                    fail("Corrupt shared storage cannot be overwritten")
                } catch (_: ContentStore.ContentReadException) {}
            }
            assertEquals(corrupt, contentFile().readText())
        }
        store.clearAll()
        for (kind in DictionaryKind.entries) assertTrue(store.dictionary(kind).isEmpty())
    }

    fun testFailedSharedFileWriteEvictsBothSnapshotsAndPreservesSavedRows() {
        store.addDictionary("中文", "code")
        store.addDictionary("English", "code", english)
        val rows = DictionaryKind.entries.associateWith { store.dictionary(it) }
        val revisions = DictionaryKind.entries.associateWith { store.dictionaryRevision(it) }
        val before = contentFile().readText()
        val obstruction = File(contentFile().parentFile, "content.tmp")
        assertTrue(obstruction.mkdir())
        try {
            try {
                store.addDictionary("failed", "failed", english)
                fail("Obstructed temporary file must fail")
            } catch (_: java.io.IOException) {}
            assertEquals(before, contentFile().readText())
            for (kind in DictionaryKind.entries) {
                assertTrue(store.dictionaryRevision(kind) > revisions.getValue(kind))
                assertEquals(rows.getValue(kind), store.dictionary(kind))
                assertNotSame(rows.getValue(kind), store.dictionary(kind))
            }
        } finally {
            obstruction.deleteRecursively()
        }
    }

    private fun assertStaleImportRejected(kind: DictionaryKind, epoch: Long) {
        val before = store.exportDictionary(kind)
        try {
            store.importDictionary("resurrected\tresurrected", epoch, kind)
            fail("Deleted entries must not be resurrected by a delayed same-kind import")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("导入已取消"))
        }
        assertEquals(before, store.exportDictionary(kind))
    }

    private fun assertInvalid(action: () -> Unit) {
        try {
            action()
            fail("Invalid or oversized dictionary operation must fail")
        } catch (_: IllegalArgumentException) {}
    }

    private fun contentFile() = File(sandbox.noBackupFilesDir, "content/content.json")

    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        val root = File(base.noBackupFilesDir, "bilingual-storage-tests")
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getExternalCacheDirs(): Array<File> = emptyArray()
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("bilingual-storage-test-$name", mode)
    }
}
