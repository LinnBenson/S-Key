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
            override fun getSharedPreferences(n: String, mode: Int) = context.getSharedPreferences(name, mode)
        })
    }
    override fun tearDown() { context.deleteSharedPreferences(name); super.tearDown() }
    fun testEmptyCustomUsesOnlyUserInstruction() {
        assertEquals("", store.read("custom_common"))
        assertEquals("我的指令", store.system("custom", "自然", "英语", " 我的指令 "))
        store.save("common", "普通要求")
        assertEquals("我的指令", store.system("custom", "自然", "英语", "我的指令"))
        store.save("custom_common", "统一要求")
        assertEquals("统一要求\n\n我的指令", store.system("custom", "自然", "英语", "我的指令"))
        store.restore("custom_common")
        assertEquals("我的指令", store.system("custom", "自然", "英语", "我的指令"))
    }
    fun testDefaultPayloadParityAndTaskOverrides() {
        for (task in listOf("polish", "correct", "tone", "translate", "custom")) {
            val original = AiWriting.payload("model", "原文", AiWriting.instruction(task,"正式","英语","自定义"),task=="custom")
            val system = store.system(task,"正式","英语","自定义")
            val actual = AiWriting.payload("model","原文","",systemOverride=system)
            assertEquals(original.toString(), actual.toString())
        }
        store.save("translate", "译成 {target}")
        assertTrue(store.system("translate","自然","法语","").endsWith("译成 法语"))
        store.save("tone_正式", "正式要求")
        assertTrue(store.system("tone","正式","","").endsWith("正式要求"))
        assertFalse(store.system("tone","自然","","").contains("正式要求"))
    }
    fun testPromptChangesInvalidateScopeAndContinuationDoesNotDuplicateSystem() {
        val before = store.system("custom","自然","英语","指令")
        store.save("custom_common","新统一要求")
        val after = store.system("custom","自然","英语","指令")
        assertFalse(AiConversation.scope("api","custom",before)==AiConversation.scope("api","custom",after))
        val input = AiWriting.payload("model","后续要求","",previousResponseId="resp_123",systemOverride=after).getJSONArray("input")
        assertEquals(1,input.length())
        assertEquals("user",input.getJSONObject(0).getString("role"))
    }
}
