package io.secure.keyboard.ai

import android.test.AndroidTestCase
import io.secure.keyboard.data.ApiConfiguration

@Suppress("DEPRECATION")
class AiWritingTest : AndroidTestCase() {
    fun testPayloadAndInstructions() {
        val body=AiWriting.payload("chosen-model","original text",AiWriting.instruction("tone","礼貌","英语",""))
        assertEquals("chosen-model",body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        val messages=body.getJSONArray("messages")
        assertEquals(2,messages.length())
        assertTrue(messages.getJSONObject(1).getString("content").contains("礼貌"))
        assertTrue(messages.getJSONObject(1).getString("content").endsWith("original text"))
        assertEquals("summarize",AiWriting.instruction("custom","自然","英语"," summarize "))
    }
    fun testSelectionEpochAndRevocation() {
        val target=AiSelectionTarget(); target.capture("same text",4)
        assertTrue(target.matches("same text",4))
        assertFalse(target.matches("same text",5))
        assertFalse(target.matches("other",4))
        target.revoke(); assertFalse(target.matches("same text",4))
    }
    fun testCanceledRequestDoesNotConnect() {
        val call=AiWriting.Call(); call.cancel()
        try {
            call.execute(ApiConfiguration(grokKey="test-only",grokModel="test-model"),"hello","polish")
            fail("Canceled call must not connect")
        } catch(e:IllegalStateException) { assertEquals("已停止生成",e.message) }
    }
}
