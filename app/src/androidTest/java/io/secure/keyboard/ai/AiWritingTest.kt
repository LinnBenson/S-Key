package io.secure.keyboard.ai

import android.test.AndroidTestCase
import io.secure.keyboard.data.ApiConfiguration
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

@Suppress("DEPRECATION")
class AiWritingTest : AndroidTestCase() {
    private fun delta(text: String) = JSONObject().put("type","response.output_text.delta").put("delta",text).toString()
    private fun complete(text: String, id: String = "resp_test", status: String = "completed"): String =
        JSONObject().put("type","response.completed").put("response",JSONObject().put("id",id).put("status",status)
            .put("output",JSONArray().put(JSONObject().put("type","message").put("role","assistant")
                .put("content",JSONArray().put(JSONObject().put("type","output_text").put("text",text)))))).toString()
    private fun stream(text: String, id: String = "resp_test") = "data: ${delta(text)}\n\ndata: ${complete(text,id)}\n\n"

    fun testStreamLineEndingsUnicodeAndFinalEventWithoutBlankLine() {
        for (separator in listOf("\n", "\r\n", "\r")) {
            val partials = mutableListOf<String>()
            val input = ": heartbeat${separator}${separator}event: response.output_text.delta${separator}data: ${delta("你好😀")}${separator}${separator}data: ${delta("世界")}${separator}${separator}data: ${complete("你好😀世界")}"
            val call = AiWriting.Call()
            assertEquals("你好😀世界", call.readStream(input.reader()) { partials.add(it) })
            assertEquals("resp_test",call.responseId)
            assertTrue(partials.isNotEmpty())
        }
    }
    fun testStreamIgnoresReasoningAndUsesCompletedTextWithoutDuplication() {
        val input = "data: {\"type\":\"response.reasoning_text.delta\",\"delta\":\"private reasoning\"}\n\n" + stream("result") + "data: [DONE]\n\n"
        assertEquals("result", AiWriting.Call().readStream(input.reader()))
        assertEquals("result", AiWriting.Call().readStream(("data: " + complete("result")).reader()))
    }
    fun testStreamTruncationAndUnsupportedFinishFailClosed() {
        for (input in listOf("data: ${delta("partial")}\n\n",
            "data: ${delta("partial")}\n\ndata: [DONE]\n\n",
            "data: ${complete("partial",status="incomplete")}\n\n",
            "data: ${complete("partial",id="")}\n\n",
            "data: ${complete("")}\n\n",
            "data: {\"type\":\"response.incomplete\"}\n\n",
            "data: {\"type\":\"response.failed\"}\n\n",
            "data: not-json\n\n", "data: {\"error\":{\"message\":\"private-server-text\"}}\n\n")) {
            val call = AiWriting.Call()
            try { call.readStream(input.reader()); fail("Must reject incomplete stream") }
            catch (e: IllegalStateException) {
                assertFalse(e.message.orEmpty().contains("private-server-text"))
                assertNull(call.responseId)
            }
        }
    }
    fun testStreamCancellationDuringDelivery() {
        val call = AiWriting.Call()
        try {
            call.readStream(stream("partial").reader()) { call.cancel() }
            fail("Canceled result must not complete")
        } catch (e: IllegalStateException) {
            assertEquals("已停止生成", e.message)
            assertNull(call.responseId)
        }
    }
    fun testStreamResponseAndOutputBounds() {
        for (input in listOf("x".repeat(1024*1024+1), "data: ${delta("a".repeat(20001))}\n\n",
            "data: ${complete("a".repeat(20001))}\n\n")) {
            try { AiWriting.Call().readStream(input.reader()); fail("Unbounded response") }
            catch (e: IllegalStateException) { assertTrue(e.message.orEmpty().contains("过大")) }
        }
    }
    fun testPayloadAndInstructions() {
        val body=AiWriting.payload("chosen-model","original text",AiWriting.instruction("tone","礼貌","英语",""))
        assertEquals("chosen-model",body.getString("model"))
        assertTrue(body.getBoolean("stream")); assertTrue(body.getBoolean("store"))
        assertFalse(body.has("previous_response_id")); assertFalse(body.has("messages"))
        assertTrue(body.getJSONArray("input").getJSONObject(0).getString("content").contains("礼貌"))
        assertEquals("original text",body.getJSONArray("input").getJSONObject(1).getString("content"))
        assertEquals("summarize",AiWriting.instruction("custom","自然","英语"," summarize "))
    }
    fun testCustomUsesOnlyUserPromptAndTranslationIgnoresTone() {
        val body=AiWriting.payload("model","source","my exact prompt",custom=true)
        assertEquals("my exact prompt",body.getJSONArray("input").getJSONObject(0).getString("content"))
        assertEquals("source",body.getJSONArray("input").getJSONObject(1).getString("content"))
        val followup=AiWriting.payload("model","new requirement","my exact prompt",custom=true,previousResponseId="resp_parent")
        assertEquals(1,followup.getJSONArray("input").length())
        assertFalse(followup.toString().contains("my exact prompt"))
        assertEquals(AiWriting.instruction("translate","粗鲁","英语",""),AiWriting.instruction("translate","礼貌","英语",""))
    }
    fun testFollowupContainsOnlyNewRequirementAndChosenResponseId() {
        val body=AiWriting.payload("model","再短一点","写邮件",previousResponseId="resp_selected")
        assertEquals("resp_selected",body.getString("previous_response_id"))
        assertEquals(1,body.getJSONArray("input").length())
        assertEquals("再短一点",body.getJSONArray("input").getJSONObject(0).getString("content"))
        assertFalse(body.has("instructions")); assertFalse(body.toString().contains("写邮件"))
        assertFalse(AiWriting.payload("model","new original","写邮件").has("previous_response_id"))
    }
    fun testContextIdentityBindsEndpointModelAndCredential() {
        val c=ApiConfiguration(grokKey="synthetic-key",grokModel="model")
        val key=AiWriting.contextKey(c)
        assertEquals(key,AiWriting.contextKey(c.copy(grokUrl=c.grokUrl+"/")))
        for (other in listOf(c.copy(grokUrl="https://example.invalid/v1"),c.copy(grokKey="other"),c.copy(grokModel="other")))
            assertFalse(key==AiWriting.contextKey(other))
        assertFalse(key.contains("synthetic-key"))
    }
    private class Connection(url: URL, val response: String, val status: Int = 200): HttpsURLConnection(url) {
        val sent=ByteArrayOutputStream()
        var disconnected=false
        override fun connect() {}
        override fun disconnect() { disconnected=true }
        override fun usingProxy()=false
        override fun getCipherSuite()="test"
        override fun getLocalCertificates(): Array<Certificate>?=null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream()=sent
        override fun getResponseCode()=status
        override fun getInputStream()=response.byteInputStream()
    }
    fun testTransportUsesResponsesAndChainsOnlySupplement() {
        val config=ApiConfiguration(grokKey="synthetic-key",grokModel="model")
        val sent=mutableListOf<Connection>()
        fun call(id: String) = AiWriting.Call { url -> Connection(url,stream("正文",id)).also { sent.add(it) } }
        val first=call("resp_first")
        first.execute(config,"原文","润色")
        val second=call("resp_second")
        second.execute(config,"更短","润色",previousResponseId=first.responseId)
        call("resp_new").execute(config,"原文","润色")
        assertEquals("resp_second",second.responseId)
        for (conn in sent) {
            assertEquals("/v1/responses",conn.url.path)
            assertFalse(conn.instanceFollowRedirects)
            assertTrue(conn.disconnected)
        }
        assertFalse(JSONObject(sent[0].sent.toString("UTF-8")).has("previous_response_id"))
        assertEquals("resp_first",JSONObject(sent[1].sent.toString("UTF-8")).getString("previous_response_id"))
        assertFalse(JSONObject(sent[2].sent.toString("UTF-8")).has("previous_response_id"))
    }
    fun testExpiredConversationDoesNotFallbackOrExposeServerBody() {
        var attempts=0
        lateinit var connection: Connection
        val call=AiWriting.Call { url -> attempts++; Connection(url,"private-server-text",404).also { connection=it } }
        try {
            call.execute(ApiConfiguration(grokKey="synthetic-key",grokModel="model"),"shorter","polish",previousResponseId="expired")
            fail("Expired context must fail")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("会话已失效"))
            assertFalse(e.message.orEmpty().contains("private-server-text"))
        }
        assertEquals(1,attempts); assertTrue(connection.disconnected); assertNull(call.responseId)
    }
    fun testSelectionEpochAndRevocation() {
        val target=AiSelectionTarget(); target.capture("same text",4)
        assertTrue(target.matches("same text",4)); assertFalse(target.matches("same text",5))
        assertFalse(target.matches("other",4)); target.revoke(); assertFalse(target.matches("same text",4))
    }
    fun testCanceledRequestDoesNotConnect() {
        val call=AiWriting.Call { error("Canceled call must not connect") }; call.cancel()
        try {
            call.execute(ApiConfiguration(grokKey="test-only",grokModel="test-model"),"hello","polish")
            fail("Canceled call must not connect")
        } catch(e:IllegalStateException) { assertEquals("已停止生成",e.message) }
    }
}
