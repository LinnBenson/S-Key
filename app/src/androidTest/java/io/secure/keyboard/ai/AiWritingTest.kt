package io.secure.keyboard.ai

import android.test.AndroidTestCase
import io.secure.keyboard.data.ApiConfiguration

@Suppress("DEPRECATION")
class AiWritingTest : AndroidTestCase() {
    private fun chunk(text: String, reason: String? = null): String =
        org.json.JSONObject().put("choices", org.json.JSONArray().put(org.json.JSONObject()
            .put("delta", org.json.JSONObject().put("content", text))
            .put("finish_reason", reason ?: org.json.JSONObject.NULL))).toString()

    fun testStreamLineEndingsUnicodeAndFinalEventWithoutBlankLine() {
        for (separator in listOf("\n", "\r\n", "\r")) {
            val partials = mutableListOf<String>()
            val input = ": heartbeat${separator}${separator}data: ${chunk("你好😀")}${separator}${separator}data: ${chunk("世界", "stop")}"
            assertEquals("你好😀世界", AiWriting.Call().readStream(input.reader()) { partials.add(it) })
            assertTrue(partials.isNotEmpty())
        }
    }
    fun testStreamDoneAndUsageEvent() {
        val input = "data: ${chunk("result")}\n\ndata: {\"choices\":[],\"usage\":{}}\n\ndata: [DONE]\n\n"
        assertEquals("result", AiWriting.Call().readStream(input.reader()))
    }
    fun testStreamTruncationAndUnsupportedFinishFailClosed() {
        for (input in listOf("data: ${chunk("partial")}\n\n",
            "data: ${chunk("partial", "length")}\n\n",
            "data: ${chunk("partial", "tool_calls")}\n\ndata: [DONE]\n\n",
            "data: not-json\n\n", "data: {\"error\":{\"message\":\"private-server-text\"}}\n\n")) {
            try { AiWriting.Call().readStream(input.reader()); fail("Must reject incomplete stream") }
            catch (e: IllegalStateException) { assertFalse(e.message.orEmpty().contains("private-server-text")) }
        }
    }
    fun testStreamCancellationDuringDelivery() {
        val call = AiWriting.Call()
        try {
            call.readStream(("data: ${chunk("partial")}\n\ndata: [DONE]\n\n").reader()) { call.cancel() }
            fail("Canceled result must not complete")
        } catch (e: IllegalStateException) { assertEquals("已停止生成", e.message) }
    }
    fun testStreamResponseAndOutputBounds() {
        for (input in listOf("x".repeat(1024*1024+1), "data: ${chunk("a".repeat(20001))}\n\n")) {
            try { AiWriting.Call().readStream(input.reader()); fail("Unbounded response") }
            catch (e: IllegalStateException) { assertTrue(e.message.orEmpty().contains("过大")) }
        }
    }

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
    fun testCustomUsesOnlyUserPromptAndTranslationIgnoresTone() {
        val messages=AiWriting.payload("model","source","my exact prompt",custom=true).getJSONArray("messages")
        assertEquals(2,messages.length())
        assertEquals("my exact prompt",messages.getJSONObject(0).getString("content"))
        assertEquals("source",messages.getJSONObject(1).getString("content"))
        assertEquals(AiWriting.instruction("translate","粗鲁","英语",""),AiWriting.instruction("translate","礼貌","英语",""))
    }
    fun testRefinementPreservesOriginalTaskWithoutSharedCustomPrompt() {
        val instruction=AiWriting.refinementInstruction("写邮件，保留主题和称呼", "再短一点")
        val messages=AiWriting.payload("model","当前邮件",instruction,custom=true).getJSONArray("messages")
        assertEquals(instruction,messages.getJSONObject(0).getString("content"))
        assertTrue(instruction.contains("写邮件，保留主题和称呼"))
        assertTrue(instruction.contains("再短一点"))
        assertEquals("当前邮件",messages.getJSONObject(1).getString("content"))
        assertFalse(AiWriting.instruction("correct","自然","英语","").contains("1.5"))
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
