package io.secure.keyboard.ai

import android.content.ContextWrapper
import android.content.Context
import android.test.AndroidTestCase

class AiPromptStoreTest : AndroidTestCase() {
    private lateinit var store: AiPromptStore
    private lateinit var name: String
    override fun setUp() {
        super.setUp()
        name = "prompt-test-${System.nanoTime()}"
        store = AiPromptStore(object : ContextWrapper(context) {
            override fun getApplicationContext() = this
            override fun getNoBackupFilesDir() = java.io.File(context.cacheDir, name).apply { mkdirs() }
            override fun getSharedPreferences(n: String, mode: Int) = context.getSharedPreferences(name, mode)
        })
    }
    override fun tearDown() { context.deleteSharedPreferences(name); java.io.File(context.cacheDir, name).deleteRecursively(); super.tearDown() }
    fun testPromptAndModelSaveIsAtomicAndRevokesOldRequests() {
        val conversations = io.secure.keyboard.data.AiConversationStore(context)
        store.save("polish", "before", "model-before")
        val before = conversations.revision()
        try { store.save("polish", "after", "bad model"); fail("Invalid ID accepted") }
        catch (_: IllegalArgumentException) {}
        assertEquals("before", store.read("polish"))
        assertEquals("model-before", store.model("polish"))
        assertEquals(before, conversations.revision())
        store.save("polish", "after", "model-after")
        assertTrue(conversations.revision() > before)
        val unchanged = conversations.revision()
        store.save("polish", "after", "model-after")
        assertEquals(unchanged, conversations.revision())
    }
    fun testModelsOverrideIndependentlyAndBlankFallsBack() {
        store.saveModel("polish", " model-polish ")
        store.saveModel("tone_自然", "model-natural")
        assertEquals("model-polish", store.resolveModel("polish", "自然", "", "global"))
        assertEquals("global", store.resolveModel("correct", "自然", "", "global"))
        assertEquals("model-natural", store.resolveModel("tone", "自然", "", "global"))
        assertEquals("global", store.resolveModel("tone", "正式", "", "global"))
        assertEquals("custom-model", store.resolveModel("custom", "自然", "custom-model", "global"))
        assertEquals("global", store.resolveModel("custom", "自然", "", "global"))
        store.saveModel("polish", "  ")
        assertEquals("global", store.resolveModel("polish", "自然", "", "global"))
        try { store.saveModel("polish", "bad\nmodel"); fail("Invalid ID accepted") } catch (_: IllegalArgumentException) {}
    }
    fun testTranslationUsesExactDefaultWithoutSharedEditingInstructions() {
        store.save("common", "SHOULD_NOT_APPEAR")
        val prompt = store.system("translate", "自然", "")
        assertEquals(AiWriting.translationPrompt, prompt)
        assertFalse(prompt.contains("{target}"))
        assertTrue(prompt.startsWith("你是一名精通中英文的专业翻译助手"))
        assertTrue(prompt.endsWith("只返回最自然、最准确、最符合真实交流习惯的翻译结果。"))
    }
    fun testEmptyCustomUsesOnlyUserInstruction() {
        assertEquals("", store.read("custom_common"))
        assertEquals("我的指令", store.system("custom", "自然", " 我的指令 "))
        store.save("common", "普通要求")
        assertEquals("我的指令", store.system("custom", "自然", "我的指令"))
        store.save("custom_common", "统一要求")
        assertEquals("统一要求\n\n我的指令", store.system("custom", "自然", "我的指令"))
        store.restore("custom_common")
        assertEquals("我的指令", store.system("custom", "自然", "我的指令"))
    }
    fun testDefaultPayloadParityAndTaskOverrides() {
        for (task in listOf("polish", "correct", "tone", "translate", "custom")) {
            val original = AiWriting.payload("model", "原文", AiWriting.instruction(task,"正式","自定义"),task=="custom", systemOverride=if (task == "translate") AiWriting.translationPrompt else null)
            val system = store.system(task,"正式","自定义")
            val actual = AiWriting.payload("model","原文","",systemOverride=system)
            assertEquals(original.toString(), actual.toString())
        }
        store.save("translate", "译成 {target}")
        assertTrue(store.system("translate","自然","").endsWith("译成 {target}"))
        store.save("tone_正式", "正式要求")
        assertTrue(store.system("tone","正式","").endsWith("正式要求"))
        assertFalse(store.system("tone","自然","").contains("正式要求"))
    }
    fun testPromptChangesInvalidateScopeAndContinuationDoesNotDuplicateSystem() {
        val before = store.system("custom","自然","指令")
        store.save("custom_common","新统一要求")
        val after = store.system("custom","自然","指令")
        assertFalse(AiConversation.scope("api","custom",before)==AiConversation.scope("api","custom",after))
        val input = AiWriting.payload("model","后续要求","",previousResponseId="resp_123",systemOverride=after).getJSONArray("input")
        assertEquals(1,input.length())
        assertEquals("user",input.getJSONObject(0).getString("role"))
    }
}
