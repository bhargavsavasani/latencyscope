package io.github.latencyscope.sample

import io.github.latencyscope.core.Clock
import io.github.latencyscope.core.LatencyConfig
import io.github.latencyscope.core.LatencyScope
import io.github.latencyscope.core.Source
import io.github.latencyscope.export.LatencyExport
import io.github.latencyscope.okhttp.installLatencyScope
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The whole library, end to end, the way the README describes it.
 *
 * Every link in this chain has its own unit tests — the listener, the
 * recorder, the route normalizer, the aggregator, the HTML renderer — but
 * until here nothing joined them up. An integration defect lives exactly in
 * the joins: a route key the renderer cannot find, a session offset that puts
 * a call outside its own timeline, a redaction that happens too late to reach
 * the report. This is the only module with all of it on one classpath.
 */
class EndToEndReportTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        LatencyScope.reset()
        // A JVM unit test has no android.os.SystemClock, and the default clock
        // reads it. This is the same substitution every instrumentation
        // module's tests make, and it still measures real elapsed time.
        LatencyScope.setClockForTesting(RealClock())
        LatencyScope.initForTesting(LatencyConfig(maxRecords = 100))
    }

    @After
    fun tearDown() {
        LatencyScope.reset()
        server.shutdown()
    }

    @Test
    fun realCallsBecomeAReadableReport() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":1}"""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val client = OkHttpClient.Builder().installLatencyScope().build()

        LatencyScope.start("checkout")
        LatencyScope.mark("cart opened")
        get(client, "/users/8311/orders?token=supersecret&page=2")
        get(client, "/users/9022/orders")

        val session = LatencyScope.stop()
        assertNotNull("stop() must return the session it just finished", session)

        val report = LatencyScope.report(session)!!

        // Two different ids, one route: this is what makes the report readable
        // at all, and it has to survive the whole chain, not just the unit test
        // for the normalizer.
        assertEquals(1, report.routes.size)
        assertEquals("/users/{id}/orders", report.routes.single().route)
        assertEquals(2, report.overall.count)
        assertEquals(1, report.overall.errorCount)

        // OkHttp is the one stack that can see connection phases.
        assertTrue("OkHttp records must carry a phase breakdown", report.hasPhaseData)
        assertTrue(report.session.records.all { it.source == Source.OKHTTP })

        val html = LatencyExport.toHtml(report)

        assertTrue("the report must name the route", html.contains("/users/{id}/orders"))
        assertTrue("the marker belongs on the timeline", html.contains("cart opened"))
        assertTrue("the session name identifies the report", html.contains("checkout"))
        assertTrue("a complete document, not a fragment", html.startsWith("<!doctype html>"))
        // Self-contained: no external CSS, JS or images, so the file opens
        // offline and from a chat attachment.
        assertFalse("the report must not fetch anything", html.contains("<script src="))
        assertFalse("the report must not fetch anything", html.contains("<link rel=\"stylesheet\""))
    }

    /**
     * The redaction the README promises has to hold at the far end of the
     * chain — in the file someone actually shares — not just in the unit test
     * for `Redactor`.
     */
    @Test
    fun secretsNeverReachTheExportedReport() {
        server.enqueue(MockResponse().setResponseCode(200))

        val client = OkHttpClient.Builder().installLatencyScope().build()
        LatencyScope.start("auth")
        get(client, "/login?token=supersecret&password=hunter2")

        val report = LatencyScope.report(LatencyScope.stop())!!

        listOf(
            LatencyExport.toHtml(report),
            LatencyExport.toJson(report),
            LatencyExport.toCsvCalls(report),
        ).forEach { rendered ->
            assertFalse("a secret reached the report", rendered.contains("supersecret"))
            assertFalse("a secret reached the report", rendered.contains("hunter2"))
            assertTrue("the parameter should still be visible as masked", rendered.contains("***"))
        }
    }

    /** Monotonic, like the real one, but without the Android framework. */
    private class RealClock : Clock {
        override fun epochMs(): Long = System.currentTimeMillis()
        override fun elapsedMs(): Long = System.nanoTime() / 1_000_000
    }

    private fun get(client: OkHttpClient, path: String) {
        client.newCall(Request.Builder().url(server.url(path)).build())
            .execute()
            .use { response -> response.body?.bytes() }
    }
}
