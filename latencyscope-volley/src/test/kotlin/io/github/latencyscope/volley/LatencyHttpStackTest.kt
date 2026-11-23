package io.github.latencyscope.volley

import com.android.volley.DefaultRetryPolicy
import com.android.volley.Request
import com.android.volley.toolbox.BaseHttpStack
import com.android.volley.toolbox.HttpResponse
import com.android.volley.toolbox.HurlStack
import com.android.volley.toolbox.StringRequest
import io.github.latencyscope.core.ApiCallRecord
import io.github.latencyscope.core.Clock
import io.github.latencyscope.core.LatencyScope
import io.github.latencyscope.core.Source
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Pinned: Robolectric 4.14 ships no runtime for SDK 37, and nothing here is
// SDK-specific — Volley only needs a real Uri and TextUtils.
@Config(manifest = Config.NONE, sdk = [34])
class LatencyHttpStackTest {

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
        server.shutdown()
    }

    @Test
    fun recordsASuccessfulRequestThroughTheRealHurlStack() {
        server.enqueue(MockResponse().setBody("hello volley"))
        val stack = LatencyHttpStack(HurlStack())
        LatencyScope.start()

        val response = stack.executeRequest(get("/users/42"), mutableMapOf())

        assertEquals(200, response.statusCode)
        val record = stopAndGetSingleRecord()
        assertEquals(Source.VOLLEY, record.source)
        assertEquals("GET", record.method)
        assertEquals(200, record.statusCode)
        assertEquals("/users/{id}", record.route)
        assertTrue(record.isSuccess)
        assertNotNull(record.threadName)
    }

    @Test
    fun phaseBreakdownIsAbsentRatherThanInvented() {
        server.enqueue(MockResponse().setBody("x"))
        val stack = LatencyHttpStack(HurlStack())
        LatencyScope.start()

        stack.executeRequest(get("/ping"), mutableMapOf())

        // Volley's stack API is one opaque "execute" call; there is nothing
        // finer to report, and zeros would read as "instant".
        val record = stopAndGetSingleRecord()
        assertTrue(record.phases.isEmpty)
        assertEquals(false, record.source.hasFullPhaseBreakdown)
    }

    @Test
    fun measuresRealElapsedTime() {
        server.enqueue(
            MockResponse().setBody("slow").setHeadersDelay(300, TimeUnit.MILLISECONDS),
        )
        val stack = LatencyHttpStack(HurlStack())
        LatencyScope.start()

        stack.executeRequest(get("/slow"), mutableMapOf())

        val record = stopAndGetSingleRecord()
        assertTrue("totalMs was ${record.totalMs}", record.totalMs >= 250)
    }

    @Test
    fun httpErrorKeepsItsStatusAndIsNotATransportFailure() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("down"))
        val stack = LatencyHttpStack(HurlStack())
        LatencyScope.start()

        stack.executeRequest(get("/health"), mutableMapOf())

        val record = stopAndGetSingleRecord()
        assertEquals(503, record.statusCode)
        assertTrue(record.isError)
        assertEquals("HTTP 503", record.errorKey)
        assertNull(record.error)
    }

    @Test
    fun transportFailureIsRecordedAndRethrown() {
        val stack = LatencyHttpStack(
            failingStack(IOException("connection reset")),
        )
        LatencyScope.start()

        val thrown = runCatching {
            stack.executeRequest(get("/boom"), mutableMapOf())
        }.exceptionOrNull()

        // Volley's retry policy depends on seeing the original exception.
        assertTrue(thrown is IOException)
        val record = stopAndGetSingleRecord()
        assertNull(record.statusCode)
        assertTrue(record.error!!.contains("connection reset"))
    }

    @Test
    fun capturesRequestAndResponseSizes() {
        val payload = """{"name":"bhargav"}"""
        val stack = LatencyHttpStack(
            respondingStack(statusCode = 200, body = "0123456789"),
        )
        LatencyScope.start()

        stack.executeRequest(post("/users", payload), mutableMapOf())

        val record = stopAndGetSingleRecord()
        assertEquals("POST", record.method)
        assertEquals(payload.length.toLong(), record.requestBytes)
        assertEquals(10, record.responseBytes)
    }

    @Test
    fun unknownResponseLengthStaysUnknown() {
        val stack = LatencyHttpStack(
            respondingStack(statusCode = 200, body = "abc", contentLength = -1),
        )
        LatencyScope.start()

        stack.executeRequest(get("/chunked"), mutableMapOf())

        // -1 means "Volley could not tell us", which must not be summed as 0.
        assertEquals(-1, stopAndGetSingleRecord().responseBytes)
    }

    @Test
    fun eachRetryAttemptIsItsOwnRecord() {
        val stack = LatencyHttpStack(respondingStack(statusCode = 200, body = "ok"))
        val request = get("/flaky").apply {
            retryPolicy = DefaultRetryPolicy()
        }
        LatencyScope.start()

        // Volley retries by calling the stack again, above this layer.
        stack.executeRequest(request, mutableMapOf())
        stack.executeRequest(request, mutableMapOf())

        val records = stopAndGetRecords()
        assertEquals("a retry storm must stay visible", 2, records.size)
    }

    @Test
    fun recordsNothingWhenNoSessionIsRecording() {
        server.enqueue(MockResponse().setBody("hello"))
        val stack = LatencyHttpStack(HurlStack())

        val response = stack.executeRequest(get("/users"), mutableMapOf())

        assertEquals(200, response.statusCode)
        assertNull(LatencyScope.stop())
    }

    @Test
    fun mapsVolleyMethodConstantsToNames() {
        val stack = LatencyHttpStack(respondingStack(statusCode = 200, body = ""))
        LatencyScope.start()

        listOf(
            Request.Method.GET to "GET",
            Request.Method.POST to "POST",
            Request.Method.PUT to "PUT",
            Request.Method.DELETE to "DELETE",
            Request.Method.PATCH to "PATCH",
            Request.Method.HEAD to "HEAD",
        ).forEach { (code, _) ->
            stack.executeRequest(request(code, "/x"), mutableMapOf())
        }

        assertEquals(
            listOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD"),
            stopAndGetRecords().map { it.method },
        )
    }

    // ----------------------------------------------------------------- utils

    private fun get(path: String) = request(Request.Method.GET, path)

    private fun post(path: String, payload: String) =
        object : StringRequest(Method.POST, server.url(path).toString(), {}, {}) {
            override fun getBody(): ByteArray = payload.toByteArray()
        }

    private fun request(method: Int, path: String) =
        StringRequest(method, server.url(path).toString(), {}, {})

    private fun respondingStack(
        statusCode: Int,
        body: String,
        contentLength: Int = body.length,
    ) = object : BaseHttpStack() {
        override fun executeRequest(
            request: Request<*>,
            additionalHeaders: MutableMap<String, String>?,
        ): HttpResponse = HttpResponse(
            statusCode,
            emptyList(),
            contentLength,
            ByteArrayInputStream(body.toByteArray()),
        )
    }

    private fun failingStack(error: IOException) = object : BaseHttpStack() {
        override fun executeRequest(
            request: Request<*>,
            additionalHeaders: MutableMap<String, String>?,
        ): HttpResponse = throw error
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
