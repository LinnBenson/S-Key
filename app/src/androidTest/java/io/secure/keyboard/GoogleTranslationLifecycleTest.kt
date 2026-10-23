package io.secure.keyboard

import android.test.AndroidTestCase
import io.secure.keyboard.data.ApiConfiguration
import io.secure.keyboard.translation.GoogleTranslation
import io.secure.keyboard.translation.TranslationSession
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

@Suppress("DEPRECATION")
class GoogleTranslationLifecycleTest : AndroidTestCase() {
    private class Connection : HttpsURLConnection(URL("https://example.invalid/translate")) {
        val sent = ByteArrayOutputStream()
        val disconnected = CountDownLatch(1)
        val reading = CountDownLatch(1)
        var block = false
        var reply = """{"data":{"translations":[{"translatedText":"synthetic result"}]}}"""
        override fun connect() {}
        override fun disconnect() { disconnected.countDown() }
        override fun usingProxy() = false
        override fun getCipherSuite() = "test"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream() = sent
        override fun getResponseCode(): Int {
            reading.countDown()
            if (block) check(disconnected.await(3, TimeUnit.SECONDS))
            return 200
        }
        override fun getInputStream() = reply.byteInputStream()
    }
    private val config = ApiConfiguration(googleKey = "synthetic-key")
    fun testKeyOnlyTravelsInHeader() {
        val conn = Connection()
        var target: URL? = null
        GoogleTranslation.Call { target = it; conn }.execute(config, "synthetic", "auto", "en")
        assertNull(target!!.query)
        assertEquals("synthetic-key", conn.getRequestProperty("X-Goog-Api-Key"))
    }
    fun testCanceledQueuedRequestDoesNotConnectOrUpload() {
        var connections = 0
        val call = GoogleTranslation.Call { connections++; Connection() }
        call.cancel()
        try { call.execute(config, "synthetic", "auto", "en"); fail("Canceled upload") }
        catch (_: IllegalStateException) {}
        assertEquals(0, connections)
    }
    fun testClosingOrEditingSessionDisconnectsInFlightRequest() {
        for (close in listOf(true, false)) {
            val conn = Connection().apply { block = true }
            val call = GoogleTranslation.Call { conn }
            val session = TranslationSession { call.cancel() }
            session.enabled = true
            var result: String? = null
            var failed = false
            val thread = Thread {
                try { result = call.execute(config, "synthetic", "auto", "en") }
                catch (_: IllegalStateException) { failed = true }
            }
            thread.start()
            assertTrue(conn.reading.await(3, TimeUnit.SECONDS))
            if (close) session.close() else session.edit("changed")
            thread.join(4000)
            assertFalse(thread.isAlive)
            assertTrue(failed)
            assertNull(result)
            assertEquals(0L, conn.disconnected.count)
        }
    }
    fun testNullOrOversizedResponseIsRejectedAndConnectionReleased() {
        for (value in listOf("null", "\"" + "a".repeat(20001) + "\"")) {
            val conn = Connection().apply { reply = """{"data":{"translations":[{"translatedText":$value}]}}""" }
            try { GoogleTranslation.Call { conn }.execute(config, "synthetic", "auto", "en"); fail("Invalid result") }
            catch (_: IllegalStateException) {}
            assertEquals(0L, conn.disconnected.count)
        }
    }
    fun testSuccessfulRequestHasBoundedPlainTextAndNoRedirects() {
        val conn = Connection()
        assertEquals("synthetic result", GoogleTranslation.Call { conn }.execute(config, "synthetic", "auto", "en"))
        assertFalse(conn.instanceFollowRedirects)
        assertEquals(0L, conn.disconnected.count)
    }
}
