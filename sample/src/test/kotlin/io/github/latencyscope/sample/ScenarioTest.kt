package io.github.latencyscope.sample

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scenarios are the sample's contract with the report: each one exists to
 * put a specific shape into it, so these assert the shapes rather than the
 * literal URLs.
 */
class ScenarioTest {

    @Test
    fun everyScenarioCallsSomething() {
        Scenario.entries.forEach { scenario ->
            assertTrue("${scenario.label} has no URLs", scenario.urls.isNotEmpty())
            assertTrue("${scenario.label} has no timeout", scenario.timeoutMs > 0)
        }
    }

    /** Plaintext would be rewritten or blocked, and the TLS phase would vanish. */
    @Test
    fun everyEndpointIsHttps() {
        Scenario.entries.flatMap { it.urls }.forEach { url ->
            assertTrue("$url is not https", url.startsWith("https://"))
        }
    }

    /**
     * The timeout scenario only demonstrates anything if the server outlasts
     * the client — otherwise it is just another slow call.
     */
    @Test
    fun theTimeoutScenarioTimesOut() {
        val scenario = Scenario.TIMEOUT

        assertTrue(scenario.urls.single().endsWith("/delay/10"))
        assertTrue("client must give up first", scenario.timeoutMs < 10_000)
    }

    @Test
    fun onlyTheBurstRunsInParallel() {
        val parallel = Scenario.entries.filter { it.parallel }

        assertEquals(listOf(Scenario.BURST), parallel)
        assertTrue("a burst of one proves nothing", Scenario.BURST.urls.size > 1)
    }

    /** A 404 or 500 is a completed call, so it must not read as a failure. */
    @Test
    fun aFailingStatusIsAnErrorRowButNotATransportError() {
        val line = logLine(CallResult(statusCode = 500, bytes = 12, error = null))

        assertTrue(line.isError)
        assertTrue(line.summary.startsWith("HTTP 500"))
    }

    @Test
    fun aTransportFailureReadsAsItsOwnMessage() {
        val line = logLine(CallResult(statusCode = null, bytes = -1, error = "SocketTimeoutException"))

        assertTrue(line.isError)
        assertTrue(line.summary.startsWith("SocketTimeoutException"))
        assertFalse("unknown sizes must not be printed", line.summary.contains("-1 B"))
    }

    @Test
    fun aSuccessfulCallIsNotAnError() {
        val line = logLine(CallResult(statusCode = 200, bytes = 31, error = null))

        assertFalse(line.isError)
        assertTrue(line.summary.contains("31 B"))
    }

    private fun logLine(result: CallResult) = LogLine(
        tab = SampleTab.OKHTTP,
        scenario = Scenario.FAST_GET,
        url = "https://example.test/todos/1",
        result = result,
        durationMs = 42,
    )
}
