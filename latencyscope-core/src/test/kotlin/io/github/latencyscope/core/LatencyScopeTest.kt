package io.github.latencyscope.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LatencyScopeTest {

    private lateinit var clock: TestClock

    @Before
    fun setUp() {
        LatencyScope.reset()
        clock = TestClock()
        LatencyScope.setClockForTesting(clock)
        LatencyScope.initForTesting()
    }

    @After
    fun tearDown() {
        LatencyScope.reset()
    }

    @Test
    fun recordsNothingUntilStarted() {
        assertFalse(LatencyScope.isRecording())
        LatencyScope.submit(rawCall())
        assertNull(LatencyScope.currentSession())
        assertNull(LatencyScope.stop())
    }

    @Test
    fun startStopRoundTrip() {
        val handle = LatencyScope.start("checkout")
        assertTrue(LatencyScope.isRecording())

        LatencyScope.submit(rawCall())
        clock.advance(2_500)
        val session = LatencyScope.stop()

        assertFalse(LatencyScope.isRecording())
        assertNotNull(session)
        assertEquals(handle.id, session!!.id)
        assertEquals("checkout", session.name)
        assertEquals(2_500, session.durationMs)
        assertEquals(1, session.records.size)
        assertTrue(session.isComplete)
    }

    @Test
    fun submitNormalizesRouteAndRedactsUrl() {
        LatencyScope.start()
        LatencyScope.submit(
            rawCall(url = "https://user:pw@api.example.com/users/42/orders/9001?token=secret"),
        )
        val record = LatencyScope.stop()!!.records.single()

        assertEquals("api.example.com", record.host)
        assertEquals("/users/{id}/orders/{id}", record.route)
        assertEquals("https://api.example.com/users/42/orders/9001?token=***", record.url)
    }

    @Test
    fun startTimesAreRelativeToTheSession() {
        // Session starts well after boot: offsets must be session-relative, not
        // absolute, or every waterfall would start 10 seconds in.
        clock.advance(10_000)
        LatencyScope.start()

        clock.advance(1_500)
        LatencyScope.submit(rawCall(startedAtElapsedMs = clock.elapsedMs()))

        val record = LatencyScope.stop()!!.records.single()
        assertEquals(1_500, record.startedAtElapsedMs)
    }

    @Test
    fun urlFilterExcludesNoise() {
        LatencyScope.initForTesting(
            LatencyConfig(urlFilter = { url -> !url.contains("analytics") }),
        )
        LatencyScope.start()

        LatencyScope.submit(rawCall(url = "https://api.example.com/users"))
        LatencyScope.submit(rawCall(url = "https://analytics.example.com/beacon"))

        val routes = LatencyScope.stop()!!.records.map { it.route }
        assertEquals(listOf("/users"), routes)
    }

    @Test
    fun aThrowingUrlFilterDoesNotBreakRecording() {
        LatencyScope.initForTesting(LatencyConfig(urlFilter = { error("boom") }))
        LatencyScope.start()
        LatencyScope.submit(rawCall())

        assertEquals(1, LatencyScope.stop()!!.records.size)
    }

    @Test
    fun droppedCallsAreCountedInTheSession() {
        LatencyScope.initForTesting(LatencyConfig(maxRecords = 2))
        LatencyScope.start()
        repeat(5) { LatencyScope.submit(rawCall(url = "https://api.example.com/r$it")) }

        val session = LatencyScope.stop()!!
        assertEquals(2, session.records.size)
        assertEquals(3, session.droppedCount)
        assertTrue(session.isTruncated)
    }

    @Test
    fun markersLandOnTheSessionTimeline() {
        LatencyScope.start()
        clock.advance(500)
        LatencyScope.mark("opened cart")
        clock.advance(300)
        LatencyScope.mark("checkout tapped")

        val markers = LatencyScope.stop()!!.markers
        assertEquals(listOf("opened cart", "checkout tapped"), markers.map { it.label })
        assertEquals(listOf(500L, 800L), markers.map { it.atElapsedMs })
    }

    @Test
    fun trackMeasuresTheBlock() {
        LatencyScope.start()
        val result = LatencyScope.track("https://api.example.com/compute", "POST") {
            clock.advance(250)
            "done"
        }

        assertEquals("done", result)
        val record = LatencyScope.stop()!!.records.single()
        assertEquals(Source.MANUAL, record.source)
        assertEquals("POST", record.method)
        assertEquals(250, record.totalMs)
        assertTrue(record.isSuccess)
    }

    @Test
    fun trackRecordsFailuresAndRethrows() {
        LatencyScope.start()
        val thrown = runCatching {
            LatencyScope.track("https://api.example.com/compute") {
                clock.advance(40)
                throw IllegalStateException("nope")
            }
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        val record = LatencyScope.stop()!!.records.single()
        assertEquals("IllegalStateException: nope", record.error)
        assertEquals(40, record.totalMs)
        assertTrue(record.isError)
    }

    @Test
    fun trackStillRunsTheBlockWhenNotRecording() {
        var ran = false
        val result = LatencyScope.track("https://api.example.com/x") {
            ran = true
            42
        }
        assertEquals(42, result)
        assertTrue(ran)
    }

    @Test
    fun stopSessionOnlyStopsItsOwnSession() {
        val stale = LatencyScope.start("first")
        LatencyScope.stop()
        LatencyScope.start("second")

        assertNull(stale.stop())
        assertTrue(LatencyScope.isRecording())
        assertEquals("second", LatencyScope.stop()!!.name)
    }

    @Test
    fun currentSessionIsReadableWhileRecording() {
        LatencyScope.start("live")
        LatencyScope.submit(rawCall())
        clock.advance(100)

        val live = LatencyScope.currentSession()!!
        assertFalse(live.isComplete)
        assertEquals(1, live.records.size)
        assertEquals(100, live.durationMs)
        assertTrue(LatencyScope.isRecording())
    }

    @Test
    fun clearDropsRecordsButKeepsRecording() {
        LatencyScope.start()
        LatencyScope.submit(rawCall())
        LatencyScope.clear()

        assertTrue(LatencyScope.isRecording())
        assertEquals(0, LatencyScope.currentSession()!!.records.size)
    }

    @Test
    fun reportDefaultsToTheLastFinishedSession() {
        LatencyScope.start("flow")
        LatencyScope.submit(rawCall(totalMs = 120))
        LatencyScope.stop()

        val report = LatencyScope.report()!!
        assertEquals("flow", report.session.name)
        assertEquals(1, report.overall.count)
        assertEquals(120, report.overall.p50Ms)
    }

    @Test
    fun reportCarriesConfiguredThresholds() {
        LatencyScope.initForTesting(
            LatencyConfig(warnThresholdMs = 150, slowThresholdMs = 900),
        )
        LatencyScope.start()
        LatencyScope.submit(rawCall())

        val report = LatencyScope.report(LatencyScope.stop())!!
        assertEquals(150, report.warnThresholdMs)
        assertEquals(900, report.slowThresholdMs)
    }

    @Test
    fun configRejectsInvalidThresholds() {
        assertTrue(
            runCatching { LatencyConfig(warnThresholdMs = 2_000, slowThresholdMs = 1_000) }
                .exceptionOrNull() is IllegalArgumentException,
        )
    }

    private fun rawCall(
        url: String = "https://api.example.com/users",
        method: String = "GET",
        totalMs: Long = 100,
        startedAtElapsedMs: Long = clock.elapsedMs(),
    ) = RawCall(
        source = Source.OKHTTP,
        url = url,
        method = method,
        statusCode = 200,
        startedAtEpochMs = clock.epochMs(),
        startedAtElapsedMs = startedAtElapsedMs,
        totalMs = totalMs,
    )
}
