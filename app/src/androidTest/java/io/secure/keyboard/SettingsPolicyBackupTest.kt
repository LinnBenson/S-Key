package io.secure.keyboard

import android.content.Context
import android.content.ContextWrapper
import android.test.AndroidTestCase
import io.secure.keyboard.data.*
import io.secure.keyboard.ai.AiPromptStore
import java.io.File
import java.io.ByteArrayOutputStream
import org.json.JSONObject

@Suppress("DEPRECATION")
class SettingsPolicyBackupTest : AndroidTestCase() {
    private lateinit var root: File
    private lateinit var sandbox: Context
    private lateinit var tag: String
    override fun setUp() {
        super.setUp(); tag="policy-${System.nanoTime()}"
        root=File(context.cacheDir,tag).apply { mkdirs() }
        sandbox=object: ContextWrapper(context) {
            override fun getApplicationContext()=this
            override fun getNoBackupFilesDir()=root
            override fun getCacheDir()=File(root,"cache").apply { mkdirs() }
            override fun getExternalCacheDirs()=emptyArray<File>()
            override fun getSharedPreferences(name: String,mode: Int)=context.getSharedPreferences("$tag-$name",mode)
        }
    }
    override fun tearDown() {
        root.deleteRecursively()
        listOf("settings","symbol_closure","ai_builtin_prompts").forEach { context.deleteSharedPreferences("$tag-$it") }
        super.tearDown()
    }
    fun testHistoryPoliciesPreservePinsAndExpireByCopyTime() {
        val store=ContentStore(sandbox); val settings=SettingsStore(sandbox)
        store.addClipboard("pin"); store.setClipboardPinned(store.clipboard().single().id,true)
        store.addClipboard("old")
        val file=File(root,"content/content.json")
        val json=JSONObject(file.readText())
        repeat(2) { json.getJSONArray("clipboard").getJSONObject(it).put("copiedAt",System.currentTimeMillis()-25*3_600_000L) }
        file.writeText(json.toString())
        settings.clipboardRetention="timed"; settings.clipboardRetentionHours=24
        assertEquals(listOf("pin"),store.clipboard().map { it.text })
        store.addClipboard("fresh")
        assertEquals(2,store.clipboard().size)
        settings.clipboardRetention="none"; store.trimClipboard()
        store.captureClipboard("not saved",99L,true)
        assertEquals(listOf("pin"),store.clipboard().map { it.text })
        assertTrue(settings.quickPaste)
        settings.clipboardRetention="forever"; settings.clipboardLimit=1
        store.addClipboard("one"); store.addClipboard("two")
        assertEquals(listOf("pin","two"),store.clipboard().map { it.text })
        store.setClipboardPinned(store.clipboard().first().id,false)
        assertEquals(listOf("two"),store.clipboard().map { it.text })
    }
    fun testSettingsRoundTripPreviewAndSecretExclusion() {
        val store=ContentStore(sandbox); val s=SettingsStore(sandbox)
        s.theme="dark"; s.toolbarPins=listOf("script","input_mode")
        s.saveProfile(false,false,s.profile(false,false).copy(heightDp=320))
        SymbolClosureStore(sandbox).save(null,SymbolClosureStore.Rule("（","）",1))
        SymbolKeyboardStore(sandbox).save(true,false,0,0,"甲")
        LetterSymbolStore(sandbox).save(false,'a',"☆")
        AiPromptStore(sandbox).save("custom_common","")
        AiPromptStore(sandbox).save("polish","测试提示")
        sandbox.getSharedPreferences("settings",0).edit().putString("apiKey","SECRET-MUST-NOT-EXPORT").apply()
        store.addClipboard("PRIVATE-CLIPBOARD"); store.addPhrase("saved")
        val out=ByteArrayOutputStream();store.writeBackup(out);val raw=out.toString("UTF-8")
        assertFalse(raw.contains("SECRET-MUST-NOT-EXPORT"));assertFalse(raw.contains("PRIVATE-CLIPBOARD"))
        s.theme="light";LetterSymbolStore(sandbox).save(false,'a',"!");store.addPhrase("new")
        val plan=store.prepareBackup(raw.byteInputStream())
        assertTrue(plan.summary.contains("常用语：2 → 1"));assertTrue(plan.summary.contains("主题"))
        assertEquals("light",s.theme);assertEquals(2,store.phrases().size)
        store.restoreBackup(plan)
        assertEquals("dark",s.theme);assertEquals(320,s.profile(false,false).heightDp)
        assertEquals(listOf("script","input_mode"),s.toolbarPins)
        assertEquals("☆",LetterSymbolStore(sandbox).symbols(false,'a'))
        assertEquals("甲",SymbolKeyboardStore(sandbox).rows(true,false)[0][0])
        assertEquals("",AiPromptStore(sandbox).read("custom_common"))
        assertEquals("测试提示",AiPromptStore(sandbox).read("polish"))
        assertEquals("PRIVATE-CLIPBOARD",store.clipboard().single().text)
        assertEquals("SECRET-MUST-NOT-EXPORT",sandbox.getSharedPreferences("settings",0).getString("apiKey",null))
        val bad=JSONObject(raw);bad.getJSONObject("settings").getJSONObject("theme").put("apiKey","injected")
        try { store.prepareBackup(bad.toString().byteInputStream());fail("Unknown setting accepted") } catch(_: IllegalArgumentException) {}
        assertEquals("dark",s.theme)
        val deep=JSONObject(raw)
        deep.getJSONObject("settings").getJSONObject("theme").put("themeTokens_light", "[".repeat(10000)+"]".repeat(10000))
        try { store.prepareBackup(deep.toString().byteInputStream());fail("Deep embedded JSON accepted") } catch(_: IllegalArgumentException) {}
        assertEquals("dark",s.theme)
        val legacy=JSONObject(raw).put("version",1).apply { remove("settings") }
        s.theme="light";store.restoreBackup(legacy.toString().byteInputStream());assertEquals("light",s.theme)
    }
    fun testEmojiShortcutsAndUiDesignRoundTripAndLegacyPreservation() {
        val store = ContentStore(sandbox)
        val settings = SettingsStore(sandbox)
        val shortcuts = EmojiShortcutStore(sandbox)
        shortcuts.save(null, "🧑‍💻", "程序员, coder,程序员")
        shortcuts.delete("😀")
        assertEquals(listOf("程序员", "coder"), shortcuts.entries().last().keywords)
        assertTrue(shortcuts.lexicon().appendTo(listOf("coder")).contains("🧑‍💻"))
        assertFalse(shortcuts.lexicon().appendTo(listOf("开心")).contains("😀"))
        settings.keyBorderWidth = 3; settings.keyHorizontalGap = 12
        settings.keyVerticalGap = 10; settings.keyCornerRadius = 22; settings.enterCornerRadius = 18
        for (inner in listOf(false, true)) for (landscape in listOf(false, true)) {
            settings.saveProfile(inner, landscape, settings.profile(inner, landscape).copy(heightDp = if(inner) 330 else 300))
        }
        val output = ByteArrayOutputStream(); store.writeBackup(output)
        val backup = JSONObject(output.toString("UTF-8"))
        assertEquals(3, backup.getInt("version"))
        shortcuts.delete("🧑‍💻"); settings.resetUiDesign()
        store.restoreBackup(backup.toString().byteInputStream())
        assertTrue(shortcuts.lexicon().appendTo(listOf("程序员")).contains("🧑‍💻"))
        assertEquals(3, settings.keyBorderWidth); assertEquals(12, settings.keyHorizontalGap)
        assertEquals(10, settings.keyVerticalGap); assertEquals(22, settings.keyCornerRadius)
        assertEquals(18, settings.enterCornerRadius)
        for (inner in listOf(false, true)) for (landscape in listOf(false, true)) {
            assertEquals(if(inner) 330 else 300, settings.profile(inner, landscape).heightDp)
        }
        val legacy = JSONObject(backup.toString()).put("version", 2)
        legacy.getJSONObject("settings").remove("uiDesign")
        legacy.getJSONObject("settings").remove("emojiShortcuts")
        settings.keyBorderWidth = 2; shortcuts.delete("🧑‍💻")
        store.restoreBackup(legacy.toString().byteInputStream())
        assertEquals(2, settings.keyBorderWidth)
        assertFalse(shortcuts.lexicon().appendTo(listOf("程序员")).contains("🧑‍💻"))
        val invalid = JSONObject(backup.toString())
        invalid.getJSONObject("settings").getJSONObject("uiDesign").put("keyDesign_radius", 31)
        try { store.restoreBackup(invalid.toString().byteInputStream()); fail("Out of range geometry accepted") }
        catch (_: IllegalArgumentException) {}
        assertEquals(2, settings.keyBorderWidth)
    }

    fun testDeletingAllEmojiShortcutsDoesNotRestoreDefaults() {
        val prefs = sandbox.getSharedPreferences("settings", 0)
        val shortcuts = EmojiShortcutStore(sandbox)
        assertTrue(shortcuts.entries().isNotEmpty())
        prefs.edit().putString(EmojiShortcutStore.KEY, "[]").commit()
        shortcuts.save(null, "❤️", "爱,喜欢")
        shortcuts.delete("❤️")
        assertTrue(EmojiShortcutStore(sandbox).entries().isEmpty())
        assertEquals(listOf("爱"), shortcuts.lexicon().appendTo(listOf("爱")))
        val store = ContentStore(sandbox); val output = ByteArrayOutputStream(); store.writeBackup(output)
        prefs.edit().remove(EmojiShortcutStore.KEY).commit()
        store.restoreBackup(output.toByteArray().inputStream())
        assertTrue(shortcuts.entries().isEmpty())
    }

    fun testClearUserContentIncludesPinsButPreservesUnrelatedSettings() {
        val store = ContentStore(sandbox); val settings = SettingsStore(sandbox)
        store.addClipboard("pinned clip"); store.setClipboardPinned(store.clipboard().single().id, true)
        store.addPhrase("pinned phrase"); store.setPhrasePinned(store.phrases().single().id, true)
        store.addDictionary("测试", "ceshi")
        store.addDictionary("example", "example", DictionaryKind.ENGLISH)
        store.useEmoji("😀")
        store.saveAiPreset(null, "keep AI", "keep prompt")
        settings.keyCornerRadius = 30; settings.theme = "dark"
        settings.saveProfile(false, false, settings.profile(false, false).copy(heightDp=350))
        val prefs = sandbox.getSharedPreferences("settings", 0)
        prefs.edit().putString("builtin_dictionary_revision", "keep-builtins").commit()
        val shortcuts = EmojiShortcutStore(sandbox)
        shortcuts.save(null, "🧑‍💻", "coder")
        val epoch = store.emojiGeneration()
        store.clearAll()
        store.useEmoji("😂", epoch)
        assertTrue(store.clipboard().isEmpty()); assertTrue(store.phrases().isEmpty())
        assertTrue(store.dictionary().isEmpty())
        assertTrue(store.dictionary(DictionaryKind.ENGLISH).isEmpty())
        assertTrue(store.recentEmoji().isEmpty())
        assertTrue(store.aiPresets().isEmpty())
        assertEquals(30, settings.keyCornerRadius); assertEquals("dark", settings.theme)
        assertEquals(350, settings.profile(false, false).heightDp)
        assertEquals("keep-builtins", prefs.getString("builtin_dictionary_revision", null))
        assertTrue(shortcuts.entries().any { it.emoji == "🧑‍💻" })
    }

    fun testDamagedShortcutConfigurationCannotDisableTypingAndRecoveryInvalidatesCache() {
        val prefs = sandbox.getSharedPreferences("settings", 0)
        val store = EmojiShortcutStore(sandbox)
        prefs.edit().putString(EmojiShortcutStore.KEY, "broken-json").commit()
        assertEquals(listOf("开心"), store.lexicon().appendTo(listOf("开心")))
        assertEquals("broken-json", prefs.getString(EmojiShortcutStore.KEY, null))
        store.reset()
        assertTrue(store.lexicon().appendTo(listOf("开心")).contains("😀"))
        store.delete("😀")
        try { store.save("😀", "😀", "开心"); fail("Stale edit resurrected a deleted shortcut") }
        catch (_: IllegalArgumentException) {}
        assertFalse(store.entries().any { it.emoji == "😀" })
    }

}
