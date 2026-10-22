package io.secure.keyboard.voice

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

class VoiceHttpRequestTest {
    private class Server(private val respond: Boolean) : AutoCloseable {
        val listener = ServerSocket(0)
        val accepted = CountDownLatch(1)
        private val release = CountDownLatch(1)
        @Volatile private var socket: Socket? = null
        private val thread = Thread {
            try {
                listener.accept().use { connection ->
                    socket = connection
                    accepted.countDown()
                    if (respond) {
                        val input = connection.getInputStream()
                        var tail = ""
                        while (!tail.endsWith("\r\n\r\n")) {
                            val c = input.read(); if (c < 0) return@Thread
                            tail = (tail + c.toChar()).takeLast(4)
                        }
                        connection.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                    } else release.await(10, TimeUnit.SECONDS)
                }
            } catch (_: IOException) { }
        }.apply { isDaemon = true; start() }
        val url get() = "http://127.0.0.1:${listener.localPort}/voice"
        override fun close() { release.countDown(); socket?.close(); listener.close(); thread.join(2000) }
    }
    private fun request(client: okhttp3.OkHttpClient) = VoiceHttpRequest(client::newCall)
    private fun execute(call: VoiceHttpRequest, url: String, size: Long = 0, write: (java.io.OutputStream) -> Unit = {}) =
        call.execute(url, emptyMap(), "application/octet-stream", size, write) { "HTTP $it" }

    @Test fun responseDeadlineThenFreshRequestSucceeds() {
        val client = VoiceHttpRequest.client.newBuilder().callTimeout(250, TimeUnit.MILLISECONDS)
            .readTimeout(5, TimeUnit.SECONDS).build()
        repeat(3) {
            Server(false).use { server ->
                val start = System.nanoTime()
                try { execute(request(client), server.url); fail("Must time out") } catch (_: IOException) { }
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3000)
            }
            Server(true).use { assertEquals("ok", execute(request(client), it.url)) }
        }
        assertEquals(0, client.dispatcher.runningCallsCount())
    }

    @Test fun stalledUploadHasWriteTimeoutAndDoesNotBlockRetry() {
        val client = VoiceHttpRequest.client.newBuilder().writeTimeout(200, TimeUnit.MILLISECONDS)
            .callTimeout(5, TimeUnit.SECONDS).build()
        Server(false).use { server ->
            val started = System.nanoTime()
            try {
                execute(request(client), server.url, 64L * 1024 * 1024) { output ->
                    val chunk = ByteArray(8192)
                    repeat(8192) { output.write(chunk) }
                }
                fail("Stalled upload succeeded")
            } catch (_: IOException) { }
            assertTrue("Write timeout must fire before the total timeout", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 4000)
        }
        Server(true).use { assertEquals("ok", execute(request(client), it.url)) }
        assertEquals(0, client.dispatcher.runningCallsCount())
    }

    @Test fun cancellationClosesBlockedSocketAndNextCallWorks() {
        val client = VoiceHttpRequest.client
        val worker = Executors.newSingleThreadExecutor()
        try {
            Server(false).use { server ->
                val call = request(client)
                val result = worker.submit<Boolean> {
                    try { execute(call, server.url); false } catch (_: IOException) { true }
                }
                assertTrue(server.accepted.await(3, TimeUnit.SECONDS))
                call.cancel()
                assertTrue(result.get(3, TimeUnit.SECONDS))
            }
            Server(true).use { assertEquals("ok", execute(request(client), it.url)) }
        } finally { worker.shutdownNow() }
    }

    @Test fun canceledBeforeExecuteNeverCreatesConnection() {
        val call = VoiceHttpRequest { error("Canceled request opened connection") }
        call.cancel()
        try { execute(call, "https://example.invalid"); fail("Canceled call succeeded") } catch (_: IOException) { }
    }

    @Test fun productionTransportDoesNotReplayAudioOrForwardCredentials() {
        val client = VoiceHttpRequest.client
        assertEquals(45000, client.callTimeoutMillis)
        assertEquals(15000, client.writeTimeoutMillis)
        assertFalse(client.retryOnConnectionFailure)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertNull(client.cache)
    }
}
