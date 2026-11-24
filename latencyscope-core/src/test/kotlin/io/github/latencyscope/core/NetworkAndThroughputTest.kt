package io.github.latencyscope.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The two additions that turn "the app is slow" into something actionable:
 * which transport it was slow on, and how fast bytes actually moved.
 */
class NetworkAndThroughputTest {

    @Before
    fun setUp() {
        LatencyScope.reset()
        LatencyScope.setClockForTesting(FixedTestClock())
        LatencyScope.initForTesting(LatencyConfig())
    }

    @After
    fun tearDown() {
        LatencyScope.reset()
    }

    // ------------------------------------------------------------ throughput

    /** 100 kB in 500 ms is 200 kB/s, measured over the transfer, not the wait. */
    @Test
    fun throughputIsMeasuredOverTheTransferWindowOnly() {
        val record = record(
            responseBytes = 100_000,
            phases = Phases(serverMs = 4_500, responseBodyMs = 500),
            totalMs = 5_000,
        )

        assertEquals(200_000.0, record.throughputBytesPerSecond!!, 0.1)
    }

    /**
     * Dividing by totalMs would blame a fast download for a slow server. A
     * stack that cannot split the phases reports nothing rather than a number
     * that means something else.
     */
    @Test
    fun aStackWithNoTransferWindowReportsNothing() {
        val record = record(
            responseBytes = 100_000,
            phases = Phases.EMPTY,
            totalMs = 5_000,
        )

        assertNull(record.throughputBytesPerSecond)
    }

    @Test
    fun anEmptyOrInstantResponseReportsNothingRatherThanInfinity() {
        assertNull(record(responseBytes = 0, phases = Phases(responseBodyMs = 10)).throughputBytesPerSecond)
        assertNull(record(responseBytes = 500, phases = Phases(responseBodyMs = 0)).throughputBytesPerSecond)
        assertNull(record(responseBytes = -1, phases = Phases(responseBodyMs = 10)).throughputBytesPerSecond)
    }

    /** Weighted by bytes, so one tiny response cannot drag the figure around. */
    @Test
    fun sessionThroughputIsWeightedByBytes() {
        LatencyScope.start("throughput")
        submit(responseBytes = 1_000_000, responseBodyMs = 1_000, networkType = "wifi")
        submit(responseBytes = 100, responseBodyMs = 100, networkType = "wifi")

        val report = LatencyScope.report(LatencyScope.stop())!!

        // 1_000_100 bytes over 1_100 ms, not the mean of 1 MB/s and 1 kB/s.
        assertEquals(909_181.8, report.throughputBytesPerSecond!!, 1.0)
    }

    @Test
    fun aSessionWithNoMeasurableTransferHasNoThroughput() {
        LatencyScope.start("nothing to measure")
        submit(responseBytes = 500, responseBodyMs = null, networkType = "wifi")

        val report = LatencyScope.report(LatencyScope.stop())!!

        assertNull(report.throughputBytesPerSecond)
    }

    // --------------------------------------------------------------- network

    /**
     * The split that answers "is it the app or the network": the same route
     * can be healthy on wifi and unusable on cellular, and one blended p95
     * hides both halves.
     */
    @Test
    fun callsAreSplitByTransport() {
        LatencyScope.start("mixed")
        submit(totalMs = 100, networkType = "wifi")
        submit(totalMs = 120, networkType = "wifi")
        submit(totalMs = 900, networkType = "cellular")

        val report = LatencyScope.report(LatencyScope.stop())!!

        assertEquals(2, report.networks.size)
        // Busiest first, so the dominant transport leads.
        assertEquals("wifi", report.networks.first().networkType)
        assertEquals(2, report.networks.first().stats.count)
        assertEquals(900, report.networks.last().stats.p95Ms)
    }

    /**
     * ACCESS_NETWORK_STATE is optional and the library never asks for it, so
     * an app without it must get no split rather than an "unknown" bucket
     * competing with real transports.
     */
    @Test
    fun callsWithNoReadableTransportAreLeftOutEntirely() {
        LatencyScope.start("no permission")
        submit(totalMs = 100, networkType = null)
        submit(totalMs = 120, networkType = null)

        val report = LatencyScope.report(LatencyScope.stop())!!

        assertTrue("no transport is not a transport named null", report.networks.isEmpty())
        assertEquals("the calls themselves still count", 2, report.overall.count)
    }

    // ----------------------------------------------------------------- utils

    private fun submit(
        totalMs: Long = 100,
        responseBytes: Long = -1,
        responseBodyMs: Long? = null,
        networkType: String?,
    ) {
        LatencyScope.submit(
            RawCall(
                source = Source.MANUAL,
                url = "https://api.example.com/users",
                method = "GET",
                statusCode = 200,
                startedAtEpochMs = 1_700_000_000_000,
                startedAtElapsedMs = 0,
                totalMs = totalMs,
                phases = Phases(responseBodyMs = responseBodyMs),
                responseBytes = responseBytes,
                networkType = networkType,
            ),
        )
    }

    private fun record(
        responseBytes: Long,
        phases: Phases,
        totalMs: Long = 1_000,
    ) = ApiCallRecord(
        id = "1",
        source = Source.MANUAL,
        url = "https://api.example.com/users",
        route = "/users",
        host = "api.example.com",
        method = "GET",
        statusCode = 200,
        startedAtEpochMs = 1_700_000_000_000,
        startedAtElapsedMs = 0,
        totalMs = totalMs,
        phases = phases,
        responseBytes = responseBytes,
    )

    private class FixedTestClock : Clock {
        override fun epochMs(): Long = 1_700_000_000_000
        override fun elapsedMs(): Long = 0
    }
}
