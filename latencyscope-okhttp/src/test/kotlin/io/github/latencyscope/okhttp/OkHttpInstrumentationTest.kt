package io.github.latencyscope.okhttp

import io.github.latencyscope.core.ApiCallRecord
import io.github.latencyscope.core.Clock
import io.github.latencyscope.core.LatencyConfig
import io.github.latencyscope.core.LatencyScope
import io.github.latencyscope.core.Source
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OkHttpInstrumentationTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        LatencyScope.reset()
        LatencyScope.setClockForTesting(FixedClock())
        LatencyScope.initForTesting()
        client = OkHttpClient.Builder()
            .installLatencyScope()
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        LatencyScope.reset()
        server.shutdown()
    }

    // ------------------------------------------------------------- happy path

    @Test
    fun recordsASuccessfulCall() {
        server.enqueue(MockResponse().setBody("hello world"))
        LatencyScope.start()

        val body = get("/users/42")

        assertEquals("hello world", body)
        val record = stopAndGetSingleRecord()
        assertEquals(Source.OKHTTP, record.source)
        assertEquals("GET", record.method)
        assertEquals(200, record.statusCode)
        assertEquals("/users/{id}", record.route)
        assertTrue(record.isSuccess)
        assertEquals(11, record.responseBytes)
        assertEquals("http/1.1", record.protocol)
        assertNotNull(record.threadName)
    }

    @Test
    fun capturesPhaseBreakdownOnAFreshConnection() {
        server.enqueue(MockResponse().setBody("x"))
        LatencyScope.start()

        get("/ping")

        val phases = stopAndGetSingleRecord().phases
        assertFalse("expected a phase breakdown", phases.isEmpty)
        assertNotNull("request headers phase missing", phases.requestHeadersMs)
        assertNotNull("response headers phase missing", phases.responseHeadersMs)
        assertNotNull("server phase missing", phases.serverMs)
        // No TLS against a plaintext MockWebServer.
        assertNull(phases.tlsMs)
    }

    @Test
    fun serverPhaseReflectsBackendThinkTime() {
        server.enqueue(
            MockResponse().setBody("slow").setHeadersDelay(400, TimeUnit.MILLISECONDS),
        )
        LatencyScope.start()

        get("/slow")

        val record = stopAndGetSingleRecord()
        val serverMs = requireNotNull(record.phases.serverMs)
        // The delay is backend think time, not transfer time: it must land in
        // serverMs, which is the number users will optimise against.
        assertTrue("serverMs was $serverMs, expected >= 350", serverMs >= 350)
        assertTrue("totalMs was ${record.totalMs}", record.totalMs >= serverMs)
    }

    @Test
    fun pooledConnectionReportsNoDnsConnectOrTls() {
        server.enqueue(MockResponse().setBody("1"))
        server.enqueue(MockResponse().setBody("2"))
        LatencyScope.start()

        get("/first")
        get("/second")

        val records = stopAndGetRecords()
        assertEquals(2, records.size)
        // First call dials; the second reuses the pooled connection, so it has
        // no DNS or connect phase at all. Reporting 0 instead of "absent" would
        // make the two calls look identical.
        assertNotNull(records[0].phases.connectMs)
        assertNull(records[1].phases.dnsMs)
        assertNull(records[1].phases.connectMs)
        assertNotNull(records[1].phases.serverMs)
    }

    @Test
    fun countsRequestAndResponseBytes() {
        server.enqueue(MockResponse().setBody("0123456789"))
        LatencyScope.start()

        val payload = """{"name":"bhargav"}"""
        val request = Request.Builder()
            .url(server.url("/users"))
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { it.body?.string() }

        val record = stopAndGetSingleRecord()
        assertEquals("POST", record.method)
        assertEquals(payload.length.toLong(), record.requestBytes)
        assertEquals(10, record.responseBytes)
    }

    // ---------------------------------------------------------------- errors

    @Test
    fun httpErrorIsRecordedAsAFailedCallWithItsStatus() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("down"))
        LatencyScope.start()

        get("/health")

        val record = stopAndGetSingleRecord()
        assertEquals(503, record.statusCode)
        assertTrue(record.isError)
        assertEquals("HTTP 503", record.errorKey)
        assertNull("a 503 is a response, not a transport failure", record.error)
    }

    @Test
    fun transportFailureIsRecordedWithoutAStatus() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        LatencyScope.start()

        val thrown = runCatching { get("/boom") }.exceptionOrNull()

        assertNotNull("the call should still fail for the caller", thrown)
        val record = stopAndGetSingleRecord()
        assertNull(record.statusCode)
        assertNotNull(record.error)
        assertTrue(record.isError)
    }

    @Test
    fun timeoutIsRecordedAsOneFailedCall() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val impatient = client.newBuilder()
            .readTimeout(300, TimeUnit.MILLISECONDS)
            .build()
        LatencyScope.start()

        runCatching {
            impatient.newCall(Request.Builder().url(server.url("/hang")).build())
                .execute().use { it.body?.string() }
        }

        val record = stopAndGetSingleRecord()
        assertNotNull(record.error)
        assertTrue(record.totalMs >= 250)
    }

    @Test
    fun cancelledCallProducesExactlyOneRecord() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        LatencyScope.start()

        val call = client.newCall(Request.Builder().url(server.url("/hang")).build())
        Thread {
            Thread.sleep(200)
            call.cancel()
        }.start()
        runCatching { call.execute().use { it.body?.string() } }

        // A cancel fires canceled() *and* callFailed(); emitting on both would
        // double-count the call.
        assertEquals(1, stopAndGetRecords().size)
    }

    // ------------------------------------------------------ redirects, retries

    @Test
    fun redirectChainIsOneRecordWithSummedPhasesAndARetryCount() {
        server.enqueue(
            MockResponse().setResponseCode(302)
                .setHeader("Location", "/final")
                .setHeadersDelay(150, TimeUnit.MILLISECONDS),
        )
        server.enqueue(
            MockResponse().setBody("arrived").setHeadersDelay(150, TimeUnit.MILLISECONDS),
        )
        LatencyScope.start()

        val body = get("/start")

        assertEquals("arrived", body)
        val record = stopAndGetSingleRecord()
        // The caller made one call and saw one 200; the record says so, while
        // still accounting for the time both hops cost.
        assertEquals(200, record.statusCode)
        assertEquals(1, record.retryCount)
        val serverMs = requireNotNull(record.phases.serverMs)
        assertTrue("serverMs $serverMs should cover both hops", serverMs >= 250)
    }

    // ----------------------------------------------------------- integration

    @Test
    fun existingEventListenerStillReceivesEvents() {
        server.enqueue(MockResponse().setBody("x"))
        val seen = mutableListOf<String>()
        val nosy = object : EventListener() {
            override fun callStart(call: Call) { seen += "start" }
            override fun callEnd(call: Call) { seen += "end" }
        }
        val wrapped = OkHttpClient.Builder()
            .installLatencyScope(EventListener.Factory { nosy })
            .build()
        LatencyScope.start()

        wrapped.newCall(Request.Builder().url(server.url("/x")).build())
            .execute().use { it.body?.string() }

        // Installing LatencyScope must not silently uninstall the app's own
        // listener: OkHttp only allows one.
        assertEquals(listOf("start", "end"), seen)
        assertEquals(1, stopAndGetRecords().size)
    }

    @Test
    fun recordsNothingWhenNoSessionIsRecording() {
        server.enqueue(MockResponse().setBody("hello"))

        val body = get("/users/42")

        assertEquals("hello", body)
        assertFalse(LatencyScope.isRecording())
        assertNull(LatencyScope.stop())
    }

    @Test
    fun urlFilterKeepsNoiseOutOfTheReport() {
        server.enqueue(MockResponse().setBody("a"))
        server.enqueue(MockResponse().setBody("b"))
        LatencyScope.initForTesting(
            LatencyConfig(urlFilter = { url -> !url.contains("/beacon") }),
        )
        LatencyScope.start()

        get("/users")
        get("/beacon")

        assertEquals(listOf("/users"), stopAndGetRecords().map { it.route })
    }

    @Test
    fun concurrentCallsAreAllRecorded() {
        val calls = 12
        repeat(calls) { server.enqueue(MockResponse().setBody("ok")) }
        LatencyScope.start()

        val threads = (0 until calls).map { i ->
            Thread { get("/items/$i") }.apply { start() }
        }
        threads.forEach { it.join() }

        val records = stopAndGetRecords()
        assertEquals(calls, records.size)
        assertEquals(setOf("/items/{id}"), records.map { it.route }.toSet())
    }

    // ----------------------------------------------------------------- utils

    private fun get(path: String): String? =
        client.newCall(Request.Builder().url(server.url(path)).build())
            .execute().use { it.body?.string() }

    private fun stopAndGetRecords(): List<ApiCallRecord> =
        requireNotNull(LatencyScope.stop()) { "no session was recording" }.records

    private fun stopAndGetSingleRecord(): ApiCallRecord {
        val records = stopAndGetRecords()
        assertEquals("expected exactly one record, got $records", 1, records.size)
        return records.single()
    }

    private class FixedClock : Clock {
        override fun elapsedMs(): Long = System.nanoTime() / 1_000_000
        override fun epochMs(): Long = System.currentTimeMillis()
    }
}
