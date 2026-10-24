package io.secure.keyboard.data

import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.test.AndroidTestCase
import java.io.File

/** Isolated files/preferences; the clipboard integration case restores the prior clip. */
@Suppress("DEPRECATION")
class ContentStoreTest : AndroidTestCase() {
    private lateinit var sandbox: IsolatedContext
    private lateinit var store: ContentStore
    private lateinit var config: SettingsStore

    override fun setUp() {
        super.setUp()
        sandbox = IsolatedContext(context)
        sandbox.root.deleteRecursively()
        sandbox.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        store = ContentStore(sandbox)
        config = SettingsStore(sandbox)
    }

    override fun tearDown() {
        sandbox.root.deleteRecursively()
        sandbox.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        super.tearDown()
    }

    fun testSearchIndexInvalidatesAfterEditDeleteAndOtherLanguageWrites() {
        store.addDictionary("中文一","ni")
        store.addDictionary("中文二","nihao")
        store.addDictionary("English","he",DictionaryKind.ENGLISH)
        assertEquals(listOf("中文一","中文二"),store.matchingDictionary("N'I",DictionaryKind.CHINESE).map { it.word })
        assertEquals(listOf("English"),store.matchingDictionary("H",DictionaryKind.ENGLISH).map { it.word })
        val entry=store.dictionary().first()
        store.updateDictionary(entry.id,"修改","hao")
        assertEquals(listOf("中文二"),store.matchingDictionary("ni",DictionaryKind.CHINESE).map { it.word })
        assertEquals(listOf("修改"),store.matchingDictionary("hao",DictionaryKind.CHINESE).map { it.word })
        assertEquals(listOf("English"),store.matchingDictionary("h",DictionaryKind.ENGLISH).map { it.word })
        store.deleteDictionary(entry.id)
        assertTrue(store.matchingDictionary("hao",DictionaryKind.CHINESE).isEmpty())
        store.clearDictionary()
        assertTrue(store.matchingDictionary("ni",DictionaryKind.CHINESE).isEmpty())
        store.clearDictionary(DictionaryKind.ENGLISH)
        assertTrue(store.matchingDictionary("h",DictionaryKind.ENGLISH).isEmpty())
    }

    fun testDeletionClearsOnlyMatchingSystemClip() {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val original = manager.primaryClip
        try {
            val value = "skey-delete-fixture-" + java.util.UUID.randomUUID()
            manager.setPrimaryClip(android.content.ClipData.newPlainText("", value))
            assertEquals(value, manager.primaryClip?.getItemAt(0)?.text?.toString())
            store.addClipboard(value)
            store.deleteClipboard(store.clipboard().single().id)
            assertFalse(manager.hasPrimaryClip())
            manager.setPrimaryClip(android.content.ClipData.newPlainText("", "newer-copy"))
            store.addClipboard(value)
            store.deleteClipboard(store.clipboard().single().id)
            assertEquals("newer-copy", manager.primaryClip?.getItemAt(0)?.text?.toString())
            store.addClipboard("newer-copy")
            store.clearClipboard()
            assertFalse(manager.hasPrimaryClip())
        } finally {
            if (original != null) manager.setPrimaryClip(original)
            else if (android.os.Build.VERSION.SDK_INT >= 28) manager.clearPrimaryClip()
        }
    }

    fun testClipboardCharacterBudgetPreservesPinsAndNewestCopy() {
        config.clipboardLimit = 1000
        val text = "x".repeat(60_000)
        store.addClipboard("pinned")
        store.setClipboardPinned(store.clipboard().single().id, true)
        repeat(30) { store.captureClipboard(text + it, 20_000L + it, true) }
        val rows = store.clipboard()
        assertTrue(rows.sumOf { it.text.length } <= ContentStore.MAX_CLIPBOARD_CHARACTERS)
        assertTrue(rows.first().pinned)
        assertEquals(text + 29, rows.last().text)
        assertFalse(store.captureClipboard("x".repeat(65_537), 30_000L, true))
        assertEquals(rows, store.clipboard())
    }

    fun testPhraseAndCumulativeDictionaryBudgetRejectAtomically() {
        store.addPhrase("x".repeat(ContentStore.MAX_PHRASE_CHARACTERS))
        try { store.addPhrase("overflow"); fail() } catch (_: IllegalArgumentException) {}
        assertEquals(1, store.phrases().size)
        val id = store.phrases().single().id
        store.updatePhrase(id, "shorter")
        store.addPhrase("allowed again")
        val word = "x".repeat(ContentStore.MAX_DICTIONARY_CHARACTERS - 1)
        store.addDictionary(word, "a")
        try { store.importDictionary("extra\tb"); fail() } catch (_: IllegalArgumentException) {}
        assertEquals(word, store.dictionary().single().word)
        store.deleteDictionary(store.dictionary().single().id)
        assertTrue(store.dictionary().isEmpty())
    }

    fun testLegacyOversizedPhrasesCanBePinnedAndReducedWithoutDataLoss() {
        val text = "x".repeat(ContentStore.MAX_PHRASE_CHARACTERS + 1)
        val row = org.json.JSONObject().put("id", "legacy").put("text", text)
        File(sandbox.noBackupFilesDir, "content/content.json").writeText(
            org.json.JSONObject().put("phrases", org.json.JSONArray().put(row)).toString())
        store.setPhrasePinned("legacy", true)
        assertEquals(text, store.phrases().single().text)
        assertTrue(store.phrases().single().pinned)
        try { store.updatePhrase("legacy", text + "x"); fail() } catch (_: IllegalArgumentException) {}
        assertEquals(text, store.phrases().single().text)
        store.updatePhrase("legacy", "reduced")
        assertEquals("reduced", store.phrases().single().text)
    }

    fun testStreamedJsonPreservesEscapesUnicodeAndPinnedState() {
        val text = "quotes \" slash \\ newline\n tab\t emoji🙂 control\u0001"
        store.addClipboard(text)
        store.addPhrase(text)
        store.setClipboardPinned(store.clipboard().single().id, true)
        val reopened = ContentStore(sandbox)
        assertEquals(text, reopened.clipboard().single().text)
        assertTrue(reopened.clipboard().single().pinned)
        assertEquals(text, reopened.phrases().single().text)
    }

    fun testClipboardDuplicatesAndPollingDeduplication() {
        assertTrue(store.captureClipboard("same", 100L))
        assertFalse(store.captureClipboard("same", 100L))
        assertTrue(store.captureClipboard("same", 101L))
        assertTrue(store.captureClipboard("same", 102L, newCopyEvent = true))
        assertEquals(listOf("same"), store.clipboard().map { it.text })
        assertEquals(1, store.clipboard().map { it.id }.distinct().size)
    }

    fun testRecopyMovesExistingItemWithoutLosingPinOrEvictingOtherItems() {
        config.clipboardLimit = 2
        store.addClipboard("same")
        val original = store.clipboard().single()
        store.setClipboardPinned(original.id, true)
        store.addClipboard("other")
        store.addClipboard("same")
        assertEquals(listOf("other", "same"), store.clipboard().map { it.text })
        assertEquals(original.id, store.clipboard().last().id)
        assertTrue(store.clipboard().last().pinned)
        config.clipboardLimit = 10
        store.addClipboard("same ")
        store.addClipboard("same\n")
        assertEquals(4, store.clipboard().size)
    }

    fun testClipboardClearSuppressesOldCopyAndLeavesSystemClipboard() {
        val manager = sandbox.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val before = manager.primaryClipDescription?.timestamp
        store.captureClipboard("old", before ?: 444L)
        store.clearClipboard()
        assertTrue(store.clipboard().isEmpty())
        val suppressed =
            sandbox
                .getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getLong("lastClipboardToken", 0L)
        assertFalse(store.captureClipboard("old", suppressed))
        assertTrue(store.clipboard().isEmpty())
        assertEquals(before, manager.primaryClipDescription?.timestamp)
        assertTrue(store.captureClipboard("old", suppressed + 1))
        assertEquals(1, store.clipboard().size)
    }

    fun testQueuedClipboardEventsCannotReviveClearedOrDeletedContent() {
        val queuedBeforeClear = store.clipboardGeneration()
        store.clearClipboard()
        assertFalse(store.captureClipboard("stale queued copy", 900L, true, queuedBeforeClear))
        assertTrue(store.clipboard().isEmpty())
        val freshAfterClear = store.clipboardGeneration()
        assertTrue(store.captureClipboard("fresh copy", 901L, true, freshAfterClear))
        val queuedBeforeDelete = store.clipboardGeneration()
        store.deleteClipboard(store.clipboard().single().id)
        assertFalse(store.captureClipboard("deleted copy", 902L, true, queuedBeforeDelete))
        assertTrue(store.clipboard().isEmpty())
        val queuedBeforeAll = store.clipboardGeneration()
        store.clearAll()
        assertFalse(store.captureClipboard("stale after clear all", 903L, true, queuedBeforeAll))
        val freshAfterAll = store.clipboardGeneration()
        assertTrue(store.captureClipboard("fresh after all", 904L, true, freshAfterAll))
        assertEquals(listOf("fresh after all"), store.clipboard().map { it.text })
    }

    fun testClipboardSuppressionPersistenceFailureIsReported() {
        val underlying = sandbox.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val failing =
            object : ContextWrapper(sandbox) {
                override fun getApplicationContext(): Context = this

                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                    object : SharedPreferences by underlying {
                        override fun edit(): SharedPreferences.Editor =
                            object : SharedPreferences.Editor by underlying.edit() {
                                override fun putLong(
                                    key: String,
                                    value: Long,
                                ): SharedPreferences.Editor = this

                                override fun commit(): Boolean = false
                            }
                    }
            }
        try {
            SettingsStore(failing).lastClipboardToken = 999L
            fail("Failed durable suppression save must be reported")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("尚未完成"))
        }
        assertEquals(0L, underlying.getLong("lastClipboardToken", 0L))
    }

    fun testClipboardFifoLimitAndExplicitTrim() {
        assertEquals(10, config.clipboardLimit)
        config.clipboardLimit = 3
        repeat(5) { store.addClipboard("entry$it") }
        assertEquals(listOf("entry2", "entry3", "entry4"), store.clipboard().map { it.text })
        config.clipboardLimit = 1
        store.trimClipboard()
        assertEquals(listOf("entry4"), store.clipboard().map { it.text })
        store.deleteClipboard(store.clipboard().single().id)
        assertTrue(store.clipboard().isEmpty())
    }

    fun testCategoryClearsPreserveOtherContentAndConfiguration() {
        config.theme = "dark"
        config.clipboardLimit = 7
        config.toolbarPins = listOf("settings", "emoji")
        val profile = config.profile(true, false).copy(heightDp = 321, split = true)
        config.saveProfile(true, false, profile)
        seedAll()
        store.clearClipboard()
        assertTrue(store.clipboard().isEmpty())
        assertEquals(1, store.phrases().size)
        assertEquals(1, store.dictionary().size)
        assertEquals(1, store.recentEmoji().size)
        store.clearPhrases()
        assertTrue(store.phrases().isEmpty())
        assertEquals(1, store.dictionary().size)
        assertEquals(1, store.recentEmoji().size)
        store.clearDictionary()
        assertTrue(store.dictionary().isEmpty())
        assertEquals(1, store.recentEmoji().size)
        store.clearEmoji()
        assertTrue(store.recentEmoji().isEmpty())
        val restored = SettingsStore(sandbox)
        assertEquals("dark", restored.theme)
        assertEquals(7, restored.clipboardLimit)
        assertEquals(listOf("settings", "emoji"), restored.toolbarPins)
        assertEquals(profile, restored.profile(true, false))
    }

    fun testClearAllRemovesCopiesCachesAndPreservesConfig() {
        config.longPressDelay = 550
        config.keySound = true
        seedAll()
        File(sandbox.cacheDir, "private-export.tmp").writeText("private")
        File(sandbox.noBackupFilesDir, "content/content.json.bak").writeText("private")
        File(sandbox.noBackupFilesDir, "content/content.tmp").writeText("private")
        store.clearAll()
        assertTrue(store.clipboard().isEmpty())
        assertTrue(store.phrases().isEmpty())
        assertTrue(store.dictionary().isEmpty())
        assertTrue(store.recentEmoji().isEmpty())
        assertTrue(File(sandbox.noBackupFilesDir, "content").listFiles().orEmpty().isEmpty())
        assertTrue(sandbox.cacheDir.listFiles().orEmpty().isEmpty())
        assertEquals(550, SettingsStore(sandbox).longPressDelay)
        assertTrue("Content erasure preserves the explicit sound preference", SettingsStore(sandbox).keySound)
    }

    fun testUnreadableContentRefusesOverwriteAndExplicitClearAllRecovers() {
        config.theme = "dark"
        config.clipboardLimit = 8
        store.addPhrase("keep until explicitly cleared")
        val storedFile = File(sandbox.noBackupFilesDir, "private-content/phrases.enc")
        val corrupt = "invalid ciphertext"
        storedFile.writeText(corrupt)
        try { store.phrases(); fail("Corrupt reads must report an error") }
        catch (_: ContentStore.ContentReadException) {}
        try { store.addPhrase("must not overwrite"); fail("Mutating corrupt category must fail") }
        catch (_: ContentStore.ContentReadException) {}
        assertEquals(corrupt, storedFile.readText())
        store.addClipboard("independent copy")
        store.clearAll()
        assertFalse(storedFile.exists())
        assertEquals("dark", SettingsStore(sandbox).theme)
        assertEquals(8, SettingsStore(sandbox).clipboardLimit)
        store.addClipboard("new content")
        assertEquals(listOf("new content"), store.clipboard().map { it.text })
        assertTrue(store.phrases().isEmpty())
    }

    fun testLegacyEmojiMigrationAndIndependentWritesPreserveContentAndBackup() {
        store.addPhrase("keep phrase")
        store.addDictionary("词条", "ci")
        val file = File(sandbox.noBackupFilesDir, "content/content.json")
        val legacy = org.json.JSONObject(if (file.exists()) file.readText() else "{}").put("emoji", org.json.JSONArray(listOf("🙂", "😀", "🙂")))
        File(sandbox.noBackupFilesDir, "private-content").deleteRecursively()
        file.writeText(legacy.put("phrases", org.json.JSONArray().put(org.json.JSONObject().put("id", "p").put("text", "keep phrase"))).toString())
        assertEquals(listOf("🙂", "😀"), store.recentEmoji())
        assertFalse(org.json.JSONObject(file.readText()).has("emoji"))
        val original = file.readBytes()
        store.useEmoji("😎")
        assertTrue("Emoji writes must not rewrite the shared content file", original.contentEquals(file.readBytes()))
        assertEquals(listOf("😎", "🙂", "😀"), ContentStore(sandbox).recentEmoji())
        val backup = java.io.ByteArrayOutputStream().also { store.writeBackup(it) }.toByteArray()
        store.restoreBackup(backup.inputStream())
        assertEquals(listOf("😎", "🙂", "😀"), store.recentEmoji())
        assertEquals("keep phrase", store.phrases().single().text)
        assertEquals("词条", store.dictionary().single().word)
    }

    fun testEmojiClearRevokesPendingWritesAcrossInstancesAndFullErase() {
        val generation = store.emojiGeneration()
        store.useEmoji("🙂", generation)
        val other = ContentStore(sandbox)
        other.clearEmoji()
        store.useEmoji("😎", generation)
        assertTrue(store.recentEmoji().isEmpty())
        val next = store.emojiGeneration()
        store.useEmoji("😀", next)
        other.clearAll()
        store.useEmoji("😎", next)
        assertTrue(store.recentEmoji().isEmpty())
        assertFalse(File(sandbox.noBackupFilesDir, "content/content.json").exists())
    }

    fun testFailedEmojiMigrationPreservesLegacyAndCanRetry() {
        store.addPhrase("preserved")
        val file = File(sandbox.noBackupFilesDir, "content/content.json")
        val legacy = org.json.JSONObject(if (file.exists()) file.readText() else "{}").put("emoji", org.json.JSONArray(listOf("🙂")))
        File(sandbox.noBackupFilesDir, "private-content").deleteRecursively()
        file.writeText(legacy.toString())
        val before = file.readBytes()
        val obstruction = File(sandbox.noBackupFilesDir, "private-content").apply { writeText("blocked") }
        try { store.recentEmoji(); fail("Migration must fail safely") } catch (_: ContentStore.ContentReadException) {}
        assertTrue(before.contentEquals(file.readBytes()))
        assertTrue(obstruction.delete())
        assertEquals(listOf("🙂"), store.recentEmoji())
    }

    fun testInterruptedMigrationRetriesRemovingLegacyHistory() {
        store.addPhrase("keep")
        val file = File(sandbox.noBackupFilesDir, "content/content.json")
        val legacy = org.json.JSONObject(if (file.exists()) file.readText() else "{}").put("emoji", org.json.JSONArray(listOf("🙂")))
        File(sandbox.noBackupFilesDir, "private-content").deleteRecursively()
        file.writeText(legacy.toString())
        val obstruction = File(file.parentFile, "content.tmp").apply { mkdirs() }
        File(obstruction, "block").writeText("block")
        try { store.recentEmoji(); fail("Expected interrupted migration") } catch (_: ContentStore.ContentReadException) {}
        assertTrue(org.json.JSONObject(file.readText()).has("emoji"))
        obstruction.deleteRecursively()
        assertEquals(listOf("🙂"), store.recentEmoji())
        assertFalse(org.json.JSONObject(file.readText()).has("emoji"))
        store.clearEmoji()
        assertTrue(ContentStore(sandbox).recentEmoji().isEmpty())
    }

    fun testRecentEmojiIsUniqueRecencyBoundedTo50() {
        repeat(55) { store.useEmoji("emoji$it") }
        assertEquals(50, store.recentEmoji().size)
        assertEquals("emoji54", store.recentEmoji().first())
        assertEquals("emoji5", store.recentEmoji().last())
        store.useEmoji("emoji5")
        store.useEmoji("emoji5")
        assertEquals(50, store.recentEmoji().size)
        assertEquals("emoji5", store.recentEmoji().first())
        assertEquals(1, store.recentEmoji().count { it == "emoji5" })
    }

    fun testDictionaryImportExportValidationAndCrud() {
        assertEquals(
            2,
            store.importDictionary("\uFEFFword\treading\r\n你好\tnihao\r\nhello\thello\n"),
        )
        assertEquals("word\treading\n你好\tnihao\nhello\thello", store.exportDictionary())
        val original = store.exportDictionary()
        try {
            store.importDictionary("valid\tvalid\ninvalid-line\n")
            fail("Invalid import should fail before any writes")
        } catch (_: IllegalArgumentException) {}
        assertEquals(original, store.exportDictionary())
        try {
            store.addDictionary("with\ttab", "code")
            fail("Tabs must be rejected")
        } catch (_: IllegalArgumentException) {}
        try {
            store.addDictionary("word", "")
            fail("Empty readings must be rejected")
        } catch (_: IllegalArgumentException) {}
        val entry = store.dictionary().first()
        store.updateDictionary(entry.id, "您好", "ninhao")
        assertEquals("您好", store.dictionary().first().word)
        store.deleteDictionary(entry.id)
        assertEquals(1, store.dictionary().size)
        store.clearDictionary()
        assertEquals(0, store.importDictionary("word\treading\n"))
    }

    fun testDelayedDictionaryImportCannotReviveDeletedContent() {
        val otherSurface = ContentStore(sandbox)
        fun assertStaleImportRejected(epoch: Long) {
            val before = store.exportDictionary()
            try {
                store.importDictionary("resurrected\toldcode", expectedEpoch = epoch)
                fail("A delayed import must not revive deleted content")
            } catch (error: IllegalStateException) {
                assertTrue(error.message.orEmpty().contains("导入已取消"))
            }
            assertEquals(before, store.exportDictionary())
        }
        store.addDictionary("old", "old")
        val beforeCategoryClear = store.captureDictionaryEpoch()
        otherSurface.clearDictionary()
        assertStaleImportRejected(beforeCategoryClear)
        assertTrue(store.dictionary().isEmpty())

        store.addDictionary("delete me", "delete")
        store.addDictionary("keep me", "keep")
        val beforeItemDelete = store.captureDictionaryEpoch()
        otherSurface.deleteDictionary(store.dictionary().first().id)
        assertStaleImportRejected(beforeItemDelete)
        assertEquals(listOf("keep me"), store.dictionary().map { it.word })

        val beforeClearAll = store.captureDictionaryEpoch()
        otherSurface.clearAll()
        assertStaleImportRejected(beforeClearAll)
        assertTrue(store.dictionary().isEmpty())
        assertEquals(
            1,
            store.importDictionary("fresh\tnewcode", expectedEpoch = store.captureDictionaryEpoch()),
        )
        assertEquals(listOf("fresh"), store.dictionary().map { it.word })
    }

    fun testDictionaryEntryLimitRejectsEntireImportAndManualAppend() {
        store.addDictionary("existing", "keep")
        val before = store.exportDictionary()
        val tooMany =
            (0 until ContentStore.MAX_DICTIONARY_ENTRIES + 1).joinToString("\n") { "w$it\tc$it" }
        try {
            store.importDictionary(tooMany)
            fail("Oversized import must fail")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("10000"))
        }
        assertEquals(before, store.exportDictionary())
        val maximum =
            (0 until ContentStore.MAX_DICTIONARY_ENTRIES).joinToString("\n") { "w$it\tc$it" }
        try {
            store.importDictionary(maximum)
            fail("Import exceeding remaining capacity must fail without partial writes")
        } catch (_: IllegalArgumentException) {}
        assertEquals(before, store.exportDictionary())
        store.clearDictionary()
        assertEquals(ContentStore.MAX_DICTIONARY_ENTRIES, store.importDictionary(maximum))
        try {
            store.addDictionary("extra", "extra")
            fail("Manual append beyond limit must fail")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("10000"))
        }
        assertEquals(ContentStore.MAX_DICTIONARY_ENTRIES, store.dictionary().size)
        val first = store.dictionary().first()
        store.updateDictionary(first.id, "edited", "edited")
        assertEquals("edited", store.dictionary().first().word)
        store.deleteDictionary(first.id)
        store.addDictionary("replacement", "replacement")
        assertEquals(ContentStore.MAX_DICTIONARY_ENTRIES, store.dictionary().size)
    }

    fun testPhrasesManualCrudAndReordering() {
        store.addPhrase("first")
        store.addPhrase("second")
        store.addPhrase("third")
        val second = store.phrases()[1]
        store.movePhrase(second.id, 0)
        assertEquals(listOf("second", "first", "third"), store.phrases().map { it.text })
        store.updatePhrase(second.id, "edited")
        store.movePhrase(second.id, 100)
        assertEquals(listOf("first", "third", "edited"), store.phrases().map { it.text })
        store.deletePhrase(second.id)
        assertEquals(listOf("first", "third"), store.phrases().map { it.text })
        store.addPhrase("  ")
        assertEquals(2, store.phrases().size)
    }

    fun testFourLayoutProfilesAndToolbarAreIndependent() {
        assertEquals(SettingsStore.defaultPins, config.toolbarPins)
        config.toolbarPins = listOf("theme", "clipboard", "dictionary", "theme", "invalid")
        assertEquals(listOf("theme", "clipboard", "dictionary"), config.toolbarPins)
        var height = 200
        for (outer in listOf(true, false)) for (landscape in listOf(false, true)) {
            config.saveProfile(
                outer,
                landscape,
                SettingsStore.LayoutProfile(heightDp = height, floatingXDp = height),
            )
            height += 20
        }
        height = 200
        val restored = SettingsStore(sandbox)
        for (outer in listOf(true, false)) for (landscape in listOf(false, true)) {
            assertEquals(height, restored.profile(outer, landscape).heightDp)
            assertEquals(height, restored.profile(outer, landscape).floatingXDp)
            height += 20
        }
    }

    fun testSymbolHintsModesPersistAndSurviveContentClearing() {
        assertEquals("digits", config.symbolHints)
        for (mode in listOf("all", "digits", "hidden")) {
            config.symbolHints = mode
            assertEquals(mode, SettingsStore(sandbox).symbolHints)
        }
        config.symbolHints = "invalid"
        assertEquals("digits", SettingsStore(sandbox).symbolHints)
        sandbox
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putString("symbolHints", "invalid-stored-mode")
            .commit()
        assertEquals("digits", SettingsStore(sandbox).symbolHints)
        config.symbolHints = "hidden"
        seedAll()
        store.clearAll()
        assertEquals("hidden", SettingsStore(sandbox).symbolHints)
        assertTrue(store.clipboard().isEmpty())
        assertTrue(store.phrases().isEmpty())
        assertTrue(store.dictionary().isEmpty())
        assertTrue(store.recentEmoji().isEmpty())
    }

    fun testFuzzyPairChoicesPersistIndependentOfMasterAndClearing() {
        assertEquals(SettingsStore.defaultFuzzyPairIds, config.fuzzyPairs)
        assertTrue("r_l" in SettingsStore.fuzzyPairIds)
        assertEquals(setOf("r_l", "en_eng", "in_ing"), config.fuzzyPairs)
        assertTrue(config.fuzzyPinyin)
        config.fuzzyPairs = setOf("r_l", "en_eng", "in_ing", "invalid")
        assertEquals(setOf("r_l", "en_eng", "in_ing"), SettingsStore(sandbox).fuzzyPairs)
        assertFalse("n_l" in SettingsStore(sandbox).fuzzyPairs)
        config.fuzzyPinyin = true
        config.fuzzyPinyin = false
        assertEquals(setOf("r_l", "en_eng", "in_ing"), SettingsStore(sandbox).fuzzyPairs)
        config.fuzzyPairs = config.fuzzyPairs - "r_l"
        assertEquals(setOf("en_eng", "in_ing"), SettingsStore(sandbox).fuzzyPairs)
        config.fuzzyPairs = emptySet()
        assertTrue(SettingsStore(sandbox).fuzzyPairs.isEmpty())
        config.fuzzyPairs = setOf("unknown")
        assertTrue(SettingsStore(sandbox).fuzzyPairs.isEmpty())
        sandbox
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("fuzzyPairs", setOf("zh_z", "invalid"))
            .commit()
        assertEquals(setOf("zh_z"), SettingsStore(sandbox).fuzzyPairs)
        config.fuzzyPairs = setOf("r_l", "en_eng", "in_ing")
        config.fuzzyPinyin = true
        seedAll()
        store.clearAll()
        assertEquals(setOf("r_l", "en_eng", "in_ing"), SettingsStore(sandbox).fuzzyPairs)
        assertTrue(SettingsStore(sandbox).fuzzyPinyin)
    }

    fun testAllThemeTokensPersistSeparatelyForLightAndDark() {
        assertEquals(SettingsStore.defaultThemeTokens(false), config.themeTokens(false))
        assertEquals(SettingsStore.defaultThemeTokens(true), config.themeTokens(true))
        assertEquals(15, SettingsStore.themeTokenIds.size)
        assertEquals(SettingsStore.themeTokenIds, config.themeTokens(false).asMap().keys.toList())
        assertTrue(
            config.themeTokens(false).asMap().values.all { Regex("#[0-9A-F]{6}").matches(it) }
        )
        val darkDefaults = config.themeTokens(true)
        config.setThemeToken(false, "normalKeyBackground", " #aabbcc ")
        assertEquals("#AABBCC", SettingsStore(sandbox).themeTokens(false).normalKeyBackground)
        assertEquals(darkDefaults, config.themeTokens(true))
        val expectedLight = linkedMapOf<String, String>()
        val expectedDark = linkedMapOf<String, String>()
        SettingsStore.themeTokenIds.forEachIndexed { index, id ->
            val light = "#%06X".format((index + 1) * 0x010101)
            val dark = "#%06X".format((index + 100) * 0x010101)
            expectedLight[id] = light
            expectedDark[id] = dark
            config.setThemeToken(false, id, light)
            config.setThemeToken(true, id, dark)
        }
        val restored = SettingsStore(sandbox)
        assertEquals(expectedLight, restored.themeTokens(false).asMap())
        assertEquals(expectedDark, restored.themeTokens(true).asMap())
    }

    fun testThemeInvalidValuesCannotPartiallySaveAndCorruptValuesFallback() {
        val before = config.themeTokens(false)
        for (bad in listOf("red", "#123", "#GGGGGG", "#80FFFFFF", "#FFFFFFFF", "123456", "")) {
            try {
                config.setThemeToken(false, "text", bad)
                fail("Invalid or alpha-bearing color must be rejected")
            } catch (_: IllegalArgumentException) {}
            assertEquals(before, config.themeTokens(false))
        }
        try {
            config.setThemeToken(false, "unknown-token", "#123456")
            fail("Unknown token must be rejected")
        } catch (_: IllegalArgumentException) {}
        try {
            config.saveThemeTokens(
                false,
                before.copy(keyboardBackground = "#123456", enterText = "invalid"),
            )
            fail("Palette must be validated before any writes")
        } catch (_: IllegalArgumentException) {}
        assertEquals(before, config.themeTokens(false))
        val prefs = sandbox.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().putString("themeTokens_light", "{broken").commit()
        assertEquals(
            SettingsStore.defaultThemeTokens(false),
            SettingsStore(sandbox).themeTokens(false),
        )
        prefs
            .edit()
            .putString("themeTokens_light", "{\"text\":\"bad\",\"accent\":\"#abcdef\"}")
            .commit()
        val recovered = SettingsStore(sandbox).themeTokens(false)
        assertEquals(before.text, recovered.text)
        assertEquals("#ABCDEF", recovered.accent)
        assertEquals(SettingsStore.defaultThemeTokens(true), config.themeTokens(true))
    }

    fun testThemeResetAndContentErasureKeepIndependentConfiguration() {
        val light =
            config
                .themeTokens(false)
                .copy(normalKeyBackground = "#123456", specialKeyText = "#ABCDEF")
        val dark =
            config
                .themeTokens(true)
                .copy(selectedKeyBackground = "#336699", selectedKeyText = "#FEDCBA")
        config.saveThemeTokens(false, light)
        config.saveThemeTokens(true, dark)
        config.theme = "dark"
        seedAll()
        store.clearClipboard()
        store.clearPhrases()
        store.clearDictionary()
        store.clearEmoji()
        store.clearAll()
        val restored = SettingsStore(sandbox)
        assertEquals(light, restored.themeTokens(false))
        assertEquals(dark, restored.themeTokens(true))
        assertEquals("dark", restored.theme)
        restored.resetThemeTokens(false)
        assertEquals(
            SettingsStore.defaultThemeTokens(false),
            SettingsStore(sandbox).themeTokens(false),
        )
        assertEquals(dark, SettingsStore(sandbox).themeTokens(true))
        restored.resetThemeTokens(true)
        assertEquals(
            SettingsStore.defaultThemeTokens(true),
            SettingsStore(sandbox).themeTokens(true),
        )
        assertEquals("dark", restored.theme)
    }

    fun testDictionarySnapshotIsSharedImmutableAndDoesNotReloadForClipboardWrites() {
        store.addDictionary("original", "orig")
        val beforeFirstRead = store.dictionaryRevision()
        val first = store.dictionary()
        assertEquals(beforeFirstRead, store.dictionaryRevision())
        assertSame(first, store.dictionary())
        val otherSurface = ContentStore(sandbox)
        assertSame(first, otherSurface.dictionary())
        try {
            (first as MutableList<ContentStore.DictionaryEntry>).clear()
            fail("Dictionary snapshot must be immutable")
        } catch (_: UnsupportedOperationException) {}
        store.addClipboard("unrelated clipboard text")
        store.addPhrase("unrelated phrase")
        store.useEmoji("😀")
        assertSame(first, otherSurface.dictionary())
        assertEquals(beforeFirstRead, otherSurface.dictionaryRevision())
        assertEquals(listOf("original"), first.map { it.word })
    }

    fun testDictionarySnapshotInvalidatesAcrossInstancesForEveryMutation() {
        val otherSurface = ContentStore(sandbox)
        val initial = store.dictionary()
        val initialRevision = store.dictionaryRevision()
        otherSurface.addDictionary("one", "one")
        assertTrue(store.dictionaryRevision() > initialRevision)
        assertNotSame(initial, store.dictionary())
        val added = store.dictionary()
        otherSurface.updateDictionary(added.single().id, "updated", "updated")
        assertEquals(listOf("updated"), store.dictionary().map { it.word })
        assertNotSame(added, store.dictionary())
        val beforeImport = store.dictionaryRevision()
        otherSurface.importDictionary("two\ttwo")
        assertTrue(store.dictionaryRevision() > beforeImport)
        assertEquals(listOf("updated", "two"), store.dictionary().map { it.word })
        otherSurface.deleteDictionary(store.dictionary().first().id)
        assertEquals(listOf("two"), store.dictionary().map { it.word })
        otherSurface.clearDictionary()
        assertTrue(store.dictionary().isEmpty())
        otherSurface.addDictionary("last", "last")
        assertEquals(1, store.dictionary().size)
        otherSurface.clearAll()
        assertTrue(store.dictionary().isEmpty())
    }

    fun testDictionarySnapshotIsPathIsolated() {
        val independentContext = IsolatedContext(sandbox)
        val independent = ContentStore(independentContext)
        try {
            independent.clearAll()
            store.addDictionary("primary", "primary")
            independent.addDictionary("independent", "independent")
            val primaryRows = store.dictionary()
            assertEquals(listOf("independent"), independent.dictionary().map { it.word })
            independent.clearAll()
            assertSame(primaryRows, store.dictionary())
            assertEquals(listOf("primary"), store.dictionary().map { it.word })
        } finally {
            independent.clearAll()
            independentContext.root.deleteRecursively()
        }
    }

    fun testChangedAndCorruptFilesCannotServeOldDictionarySnapshot() {
        store.addDictionary("old", "old")
        val before = store.dictionary()
        val beforeRevision = store.dictionaryRevision()
        val storedFile = File(sandbox.noBackupFilesDir, "content/content.json")
        val root = org.json.JSONObject(storedFile.readText())
        root.getJSONArray("dictionary").getJSONObject(0).put("word", "externally changed")
        storedFile.writeText(root.toString())
        assertEquals(listOf("externally changed"), store.dictionary().map { it.word })
        assertNotSame(before, store.dictionary())
        assertTrue(store.dictionaryRevision() > beforeRevision)
        val corruptRevision = store.dictionaryRevision()
        storedFile.writeText("{truncated and corrupt")
        repeat(2) {
            try {
                store.dictionary()
                fail("Corruption must not return a previously cached dictionary")
            } catch (_: ContentStore.ContentReadException) {}
        }
        assertTrue(store.dictionaryRevision() > corruptRevision)
        store.clearAll()
        assertTrue(store.dictionary().isEmpty())
    }

    fun testFailedDictionaryWriteAndStaleImportEvictSnapshotWithoutResurrection() {
        store.addDictionary("original", "orig")
        val original = store.dictionary()
        val revision = store.dictionaryRevision()
        val obstruction = File(sandbox.noBackupFilesDir, "content/content.tmp")
        assertTrue(obstruction.mkdir())
        try {
            try {
                store.addDictionary("must not persist", "failed")
                fail("Write obstruction must fail")
            } catch (_: java.io.IOException) {}
            assertTrue(store.dictionaryRevision() > revision)
            assertEquals(listOf("original"), store.dictionary().map { it.word })
            assertNotSame(original, store.dictionary())
        } finally {
            obstruction.deleteRecursively()
        }
        val oldEpoch = store.captureDictionaryEpoch()
        store.clearDictionary()
        val emptySnapshot = store.dictionary()
        try {
            store.importDictionary("stale\tstale", expectedEpoch = oldEpoch)
            fail("Stale import must be rejected")
        } catch (_: IllegalStateException) {}
        assertTrue(store.dictionary().isEmpty())
        assertNotSame(emptySnapshot, store.dictionary())
    }

    private fun seedAll() {
        store.addClipboard("copy")
        store.addPhrase("phrase")
        store.addDictionary("word", "reading")
        store.useEmoji("😀")
    }

    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        val root = File(base.noBackupFilesDir, "storage-instrumentation-tests")

        override fun getApplicationContext(): Context = this

        override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }

        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }

        override fun getExternalCacheDirs(): Array<File> = emptyArray()

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("storage-test-$name", mode)
    }
}
