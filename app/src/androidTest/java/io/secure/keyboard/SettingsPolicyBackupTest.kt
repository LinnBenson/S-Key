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
}
