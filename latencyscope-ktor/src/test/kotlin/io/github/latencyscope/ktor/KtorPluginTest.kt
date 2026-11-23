package io.github.latencyscope.ktor

import io.github.latencyscope.core.ApiCallRecord
import io.github.latencyscope.core.Clock
import io.github.latencyscope.core.LatencyScope
import io.github.latencyscope.core.Source
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KtorPluginTest {

    @Before
    fun setUp() {
        LatencyScope.reset()
        LatencyScope.setClockForTesting(RealClock())
        LatencyScope.initForTesting()
    }

    @After
    fun tearDown() {
        LatencyScope.reset()
    }

    @Test
    fun recordsASuccessfulCall() = runBlocking {
        val client = clientReturning("hello")
        LatencyScope.start()

        val body = client.get("https://api.example.com/users/42").bodyAsText()

        assertEquals("hello", body)
        val record = stopAndGetSingleRecord()
        assertEquals(Source.KTOR, record.source)
        assertEquals("GET", record.method)
        assertEquals(200, record.statusCode)
        assertEquals("/users/{id}", record.route)
        assertEquals("api.example.com", record.host)
        assertTrue(record.isSuccess)
    }

    @Test
    fun phaseBreakdownIsAbsentRatherThanInvented() {
        runBlocking {
            val client = clientReturning("x")
            LatencyScope.start()

            client.get("https://api.example.com/ping").bodyAsText()

            // No Ktor engine exposes DNS/TLS/server timings. Reporting zeros
            // would read as "instant" in the stacked bar, which is a lie.
            val record = stopAndGetSingleRecord()
            assertTrue(record.phases.isEmpty)
            assertEquals(false, record.source.hasFullPhaseBreakdown)
        }
    }

    @Test
    fun readsResponseSizeFromContentLength() = runBlocking {
        val client = clientReturning("0123456789")
        LatencyScope.start()

        client.get("https://api.example.com/blob").bodyAsText()

        assertEquals(10, stopAndGetSingleRecord().responseBytes)
    }

    @Test
    fun measuresRequestBodySize() = runBlocking {
        val client = clientReturning("ok")
        LatencyScope.start()

        val payload = """{"name":"bhargav"}"""
        client.post("https://api.example.com/users") { setBody(payload) }.bodyAsText()

        val record = stopAndGetSingleRecord()
        assertEquals("POST", record.method)
        assertEquals(payload.length.toLong(), record.requestBytes)
    }

    @Test
    fun httpErrorKeepsItsStatusAndIsNotATransportFailure() = runBlocking {
        val client = HttpClient(
            MockEngine { respondError(HttpStatusCode.ServiceUnavailable) },
        ) { install(LatencyScopeKtorPlugin) }
        LatencyScope.start()

        client.get("https://api.example.com/health")

        val record = stopAndGetSingleRecord()
        assertEquals(503, record.statusCode)
        assertTrue(record.isError)
        assertEquals("HTTP 503", record.errorKey)
        assertNull(record.error)
    }

    @Test
    fun transportFailureIsRecordedAndRethrown() = runBlocking {
        val client = HttpClient(
            MockEngine { throw IOException("connection reset") },
        ) { install(LatencyScopeKtorPlugin) }
        LatencyScope.start()

        val thrown = runCatching { client.get("https://api.example.com/boom") }.exceptionOrNull()

        assertNotNull("the caller must still see the failure", thrown)
        val record = stopAndGetSingleRecord()
        assertNull(record.statusCode)
        assertNotNull(record.error)
        assertTrue(record.error!!.contains("connection reset"))
    }

    @Test
    fun deferToEngineSuppressesRecording() = runBlocking {
        val client = HttpClient(
            MockEngine { respond("ok", HttpStatusCode.OK) },
        ) {
            install(LatencyScopeKtorPlugin) { deferToEngine = true }
        }
        LatencyScope.start()

        client.get("https://api.example.com/users").bodyAsText()

        // The engine-level instrumentation is expected to record this one.
        assertEquals(0, stopAndGetRecords().size)
    }

    @Test
    fun recordsNothingWhenNoSessionIsRecording() = runBlocking {
        val client = clientReturning("hello")

        val body = client.get("https://api.example.com/users").bodyAsText()

        assertEquals("hello", body)
        assertNull(LatencyScope.stop())
    }

    @Test
    fun concurrentCallsAreAllRecorded() = runBlocking {
        val client = clientReturning("ok")
        LatencyScope.start()

        (0 until 20).map { i ->
            async { client.get("https://api.example.com/items/$i").bodyAsText() }
        }.awaitAll()

        val records = stopAndGetRecords()
        assertEquals(20, records.size)
        assertEquals(setOf("/items/{id}"), records.map { it.route }.toSet())
    }

    @Test
    fun queryTokensAreRedactedByCore() = runBlocking {
        val client = clientReturning("ok")
        LatencyScope.start()

        client.get("https://api.example.com/me?access_token=secret123").bodyAsText()

        val record = stopAndGetSingleRecord()
        assertTrue(record.url, record.url.endsWith("access_token=***"))
    }

    private fun clientReturning(body: String) = HttpClient(
        MockEngine {
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentLength, body.length.toString()),
            )
        },
    ) {
        install(LatencyScopeKtorPlugin)
    }

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
