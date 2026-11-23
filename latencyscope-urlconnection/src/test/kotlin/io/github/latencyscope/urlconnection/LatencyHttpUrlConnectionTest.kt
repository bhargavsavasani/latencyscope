package io.github.latencyscope.urlconnection

import io.github.latencyscope.core.ApiCallRecord
import io.github.latencyscope.core.Clock
import io.github.latencyscope.core.LatencyScope
import io.github.latencyscope.core.Source
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class LatencyHttpUrlConnectionTest {

    /** A wrapper bug here means a hung socket, which must fail, not block CI. */
    @get:Rule
    public val timeout: Timeout = Timeout.seconds(30)

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        LatencyScope.reset()
        LatencyScope.setClockForTesting(RealClock())
        LatencyScope.initForTesting()
    }

    @After
    fun tearDown() {
        LatencyScope.reset()
        server.shutdownQuietly()
    }

    private fun MockWebServer.shutdownQuietly() {
        runCatching { shutdown() }
    }

    @Test
    fun recordsASuccessfulCallWhenTheBodyIsRead() {
        server.enqueue(MockResponse().setBody("hello urlconnection"))
        LatencyScope.start()

        val body = open("/users/42").inputStream.bufferedReader().use { it.readText() }

        assertEquals("hello urlconnection", body)
        val record = stopAndGetSingleRecord()
        assertEquals(Source.URL_CONNECTION, record.source)
        assertEquals("GET", record.method)
        assertEquals(200, record.statusCode)
        assertEquals("/users/{id}", record.route)
        assertTrue(record.isSuccess)
        assertEquals("hello urlconnection".length.toLong(), record.responseBytes)
        assertNotNull(record.threadName)
    }

    @Test
    fun reportsConnectAndServerPhasesButNotDnsOrTls() {
        server.enqueue(
            MockResponse().setBody("slow").setHeadersDelay(300, TimeUnit.MILLISECONDS),
        )
        LatencyScope.start()

        open("/slow").inputStream.use { it.readBytes() }

        val phases = stopAndGetSingleRecord().phases
        val serverMs = requireNotNull(phases.serverMs)
        assertTrue("serverMs was $serverMs", serverMs >= 250)
        // HttpURLConnection exposes no hook below the socket.
        assertNull(phases.dnsMs)
        assertNull(phases.tlsMs)
    }

    @Test
    fun closingTheStreamEarlyStillRecordsWhatWasRead() {
        server.enqueue(MockResponse().setBody("0123456789"))
        LatencyScope.start()

        val stream = open("/partial").inputStream
        val first = ByteArray(4)
        stream.read(first)
        stream.close()

        val record = stopAndGetSingleRecord()
        assertEquals(4, record.responseBytes)
        assertEquals(200, record.statusCode)
    }

    @Test
    fun disconnectWithoutReadingStillRecordsTheCall() {
        server.enqueue(MockResponse().setBody("ignored"))
        LatencyScope.start()

        val connection = open("/fire-and-forget")
        assertEquals(200, connection.responseCode)
        connection.disconnect()

        val record = stopAndGetSingleRecord()
        assertEquals(200, record.statusCode)
        // Nothing was consumed, so the size is unknown rather than zero.
        assertEquals(-1, record.responseBytes)
    }

    @Test
    fun httpErrorIsRecordedWithItsStatus() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("down"))
        LatencyScope.start()

        val connection = open("/health")
        assertEquals(503, connection.responseCode)
        connection.errorStream?.use { it.readBytes() }

        val record = stopAndGetSingleRecord()
        assertEquals(503, record.statusCode)
        assertTrue(record.isError)
        assertEquals("HTTP 503", record.errorKey)
        assertNull("a 503 is a response, not a transport failure", record.error)
    }

    @Test
    fun transportFailureIsRecordedAndRethrown() {
        // Connection refused rather than a mid-handshake disconnect: the JDK
        // silently retries a reset idempotent request, which would block on a
        // MockWebServer with nothing left queued. The library sees the same
        // IOException either way.
        val deadUrl = server.url("/boom").toUrl()
        server.shutdownQuietly()
        LatencyScope.start()

        val thrown = runCatching {
            LatencyUrlConnection.open(deadUrl).inputStream.use { it.readBytes() }
        }.exceptionOrNull()

        assertTrue(thrown is IOException)
        val record = stopAndGetSingleRecord()
        assertNull(record.statusCode)
        assertNotNull(record.error)
        assertTrue(record.isError)
    }

    @Test
    fun countsUploadedRequestBytes() {
        server.enqueue(MockResponse().setBody("ok"))
        LatencyScope.start()

        val payload = """{"name":"bhargav"}""".toByteArray()
        val connection = open("/users").apply {
            requestMethod = "POST"
            doOutput = true
            setFixedLengthStreamingMode(payload.size)
        }
        connection.outputStream.use { it.write(payload) }
        connection.inputStream.use { it.readBytes() }

        val record = stopAndGetSingleRecord()
        assertEquals("POST", record.method)
        assertEquals(payload.size.toLong(), record.requestBytes)
    }

    @Test
    fun oneCallProducesExactlyOneRecordEvenWithDisconnectAfterReading() {
        server.enqueue(MockResponse().setBody("hello"))
        LatencyScope.start()

        val connection = open("/once")
        connection.inputStream.use { it.readBytes() }
        connection.disconnect()
        connection.disconnect()

        assertEquals(1, stopAndGetRecords().size)
    }

    @Test
    fun recordsNothingWhenNoSessionIsRecording() {
        server.enqueue(MockResponse().setBody("hello"))

        val body = open("/users").inputStream.bufferedReader().use { it.readText() }

        assertEquals("hello", body)
        assertNull(LatencyScope.stop())
    }

    @Test
    fun headersAndPropertiesAreDelegatedUnchanged() {
        server.enqueue(MockResponse().setBody("x").setHeader("X-Trace", "abc123"))
        LatencyScope.start()

        val connection = open("/headers").apply {
            setRequestProperty("X-Client", "latencyscope")
            connectTimeout = 5_000
            readTimeout = 5_000
        }
        connection.inputStream.use { it.readBytes() }

        assertEquals("abc123", connection.getHeaderField("X-Trace"))
        assertEquals("latencyscope", connection.getRequestProperty("X-Client"))
        assertEquals(5_000, connection.connectTimeout)
        assertEquals(server.url("/headers").toUrl(), connection.url)
        assertEquals(1, stopAndGetRecords().size)
    }

    @Test
    fun wrappingAnAlreadyWrappedConnectionIsANoOp() {
        server.enqueue(MockResponse().setBody("x"))
        val once = LatencyUrlConnection.open(server.url("/x").toUrl())
        val twice = LatencyUrlConnection.wrap(once)

        assertTrue(twice === once)
    }

    @Test
    fun kotlinExtensionOpensAnInstrumentedConnection() {
        server.enqueue(MockResponse().setBody("hi"))
        LatencyScope.start()

        val url: URL = server.url("/ext").toUrl()
        url.openInstrumentedConnection().inputStream.use { it.readBytes() }

        assertEquals(1, stopAndGetRecords().size)
    }

    // ----------------------------------------------------------------- utils

    private fun open(path: String): HttpURLConnection =
        LatencyUrlConnection.open(server.url(path).toUrl())

    private fun stopAndGetRecords(): List<ApiCallRecord> =
        requireNotNull(LatencyScope.stop()) { "no session was recording" }.records

    private fun stopAndGetSingleRecord(): ApiCallRecord {
        val records = stopAndGetRecords()
        assertEquals("expected exactly one record, got $records", 1, records.size)
        return records.single()
    }

    private class RealClock : Clock {
        override fun elapsedMs(): Long = System.nanoTime() / 1_000_000
        override fun epochMs(): Long = System.currentTimeMillis()
    }
}
