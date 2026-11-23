package io.github.latencyscope.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AggregatorTest {

    @Test
    fun nearestRankPercentilesMatchKnownValues() {
        // 1..100: nearest rank p50 = 50th value = 50, p95 = 95, p99 = 99.
        val sorted = LongArray(100) { (it + 1).toLong() }

        assertEquals(1, Aggregator.percentile(sorted, 1.0))
        assertEquals(50, Aggregator.percentile(sorted, 50.0))
        assertEquals(90, Aggregator.percentile(sorted, 90.0))
        assertEquals(95, Aggregator.percentile(sorted, 95.0))
        assertEquals(99, Aggregator.percentile(sorted, 99.0))
        assertEquals(100, Aggregator.percentile(sorted, 100.0))
    }

    @Test
    fun percentileAlwaysReturnsAnObservedValue() {
        val sorted = longArrayOf(10, 20, 30)
        // Never interpolates: p50 of three values is the middle one, not 20.0something.
        assertEquals(20, Aggregator.percentile(sorted, 50.0))
        assertEquals(30, Aggregator.percentile(sorted, 95.0))
    }

    @Test
    fun percentileOfSingleAndEmptySets() {
        assertEquals(7, Aggregator.percentile(longArrayOf(7), 99.0))
        assertEquals(0, Aggregator.percentile(longArrayOf(), 50.0))
    }

    @Test
    fun percentileRejectsOutOfRange() {
        assertTrue(
            runCatching { Aggregator.percentile(longArrayOf(1), 0.0) }
                .exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { Aggregator.percentile(longArrayOf(1), 101.0) }
                .exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun statsCoverCountsBytesAndErrors() {
        val stats = Aggregator.statsOf(
            listOf(
                record(totalMs = 100, requestBytes = 10, responseBytes = 1_000),
                record(totalMs = 300, requestBytes = 20, responseBytes = 2_000),
                record(totalMs = 200, statusCode = 500),
                record(totalMs = 400, statusCode = null, error = "SocketTimeoutException: timeout"),
            ),
        )

        assertEquals(4, stats.count)
        assertEquals(2, stats.successCount)
        assertEquals(2, stats.errorCount)
        assertEquals(0.5, stats.errorRate, 0.0001)
        assertEquals(250.0, stats.avgMs, 0.0001)
        assertEquals(100, stats.minMs)
        assertEquals(400, stats.maxMs)
        assertEquals(1_000, stats.totalMs)
        assertEquals(30, stats.requestBytes)
        assertEquals(3_000, stats.responseBytes)
    }

    @Test
    fun unknownByteCountsAreNotCountedAsZero() {
        // -1 means "this stack could not tell us", which must not drag a sum down.
        val stats = Aggregator.statsOf(
            listOf(
                record(responseBytes = -1),
                record(responseBytes = 500),
            ),
        )
        assertEquals(500, stats.responseBytes)
    }

    @Test
    fun phaseAveragesOnlyCountCallsThatReportedThePhase() {
        val stats = Aggregator.statsOf(
            listOf(
                record(phases = Phases(dnsMs = 10, serverMs = 100)),
                // Pooled connection: no DNS phase at all.
                record(phases = Phases(serverMs = 200)),
            ),
        )

        assertEquals(10L, stats.phaseAverages.dnsMs)
        assertEquals(150L, stats.phaseAverages.serverMs)
        assertNull(stats.phaseAverages.tlsMs)
    }

    @Test
    fun aggregateGroupsByRouteMethodAndHost() {
        val report = Aggregator.aggregate(sessionOf(
            record(route = "/users/{id}", method = "GET", totalMs = 100),
            record(route = "/users/{id}", method = "GET", totalMs = 300),
            record(route = "/users/{id}", method = "POST", totalMs = 50),
            record(route = "/orders", method = "GET", totalMs = 900),
        ))

        assertEquals(3, report.routes.size)
        // Sorted by total time: /orders (900) beats GET /users/{id} (400).
        assertEquals("GET /orders", report.routes[0].key)
        assertEquals("GET /users/{id}", report.routes[1].key)
        assertEquals(2, report.routes[1].stats.count)
        assertEquals(200.0, report.routes[1].stats.avgMs, 0.0001)
    }

    @Test
    fun aggregateGroupsErrorsByStatusAndException() {
        val report = Aggregator.aggregate(sessionOf(
            record(route = "/a", statusCode = 500),
            record(route = "/b", statusCode = 500),
            record(route = "/c", statusCode = null, error = "SocketTimeoutException: read timed out"),
        ))

        assertEquals(2, report.errors.size)
        assertEquals("HTTP 500", report.errors[0].key)
        assertEquals(2, report.errors[0].count)
        assertEquals(listOf("/a", "/b"), report.errors[0].routes)
        assertEquals("SocketTimeoutException", report.errors[1].key)
    }

    @Test
    fun slowestRouteUsesP95AndHeaviestUsesTotalTime() {
        val report = Aggregator.aggregate(sessionOf(
            // One very slow call.
            record(route = "/rare-but-slow", totalMs = 5_000),
            // Many moderate calls that dominate total time.
            *Array(20) { record(route = "/chatty", totalMs = 400) },
        ))

        assertEquals("/rare-but-slow", report.slowestRoute?.route)
        assertEquals("/chatty", report.heaviestRoute?.route)
    }

    @Test
    fun emptySessionProducesEmptyReport() {
        val report = Aggregator.aggregate(sessionOf())

        assertEquals(Stats.EMPTY, report.overall)
        assertTrue(report.routes.isEmpty())
        assertTrue(report.errors.isEmpty())
        assertNull(report.slowestRoute)
    }

    @Test
    fun timeShareIsRelativeToSessionTotal() {
        val report = Aggregator.aggregate(sessionOf(
            record(route = "/a", totalMs = 250),
            record(route = "/b", totalMs = 750),
        ))

        val a = report.routes.first { it.route == "/a" }
        assertEquals(0.25, a.timeShare(report.overall.totalMs), 0.0001)
    }

    private fun sessionOf(vararg records: ApiCallRecord) = LatencySession(
        id = "session",
        name = "test",
        startedAtEpochMs = 0,
        endedAtEpochMs = 1_000,
        durationMs = 1_000,
        device = DeviceInfo.UNKNOWN,
        records = records.toList(),
    )
}
