package io.github.latencyscope.core

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The half of the overhead budget that can be checked without a device.
 *
 * `OverheadBenchmark` (instrumented) owns the absolute number — 50 µs per
 * call — because that number is only meaningful on ART, on a phone. What is
 * meaningful anywhere, and what a desktop JVM can assert without being flaky,
 * is the *shape* of the cost: recording call number 20,000 must cost what
 * recording call number 200 did.
 *
 * This is the regression that would actually get written by accident. Making
 * `RecordStore.add` scan for the oldest record, or `Recorder.buildRecord`
 * consult the records so far, would turn a bounded per-call cost into one that
 * grows with session length — invisible in a unit test that records ten calls,
 * fatal in an app recording for an hour.
 */
class RecordingCostTest {

    @Before
    fun setUp() {
        LatencyScope.reset()
    }

    @After
    fun tearDown() {
        LatencyScope.reset()
    }

    @Test
    fun perCallCostDoesNotGrowWithSessionLength() {
        // Best of several, not one shot. A machine compiling other modules in
        // parallel can make any single run arbitrarily slow, and that noise
        // only ever adds time — so the fastest run is the closest reading of
        // what the code actually costs. Taking one sample made this fail on a
        // busy machine while the code was fine.
        val small = fastestNanosPerCall(SMALL)
        val large = fastestNanosPerCall(LARGE)

        // LARGE is 10x SMALL, so anything quadratic lands at ~10x here.
        val ratio = large / small
        assertTrue(
            "per-call cost grew ${"%.1f".format(ratio)}x between $SMALL and $LARGE calls " +
                "(${"%.0f".format(small)}ns -> ${"%.0f".format(large)}ns) — " +
                "the recording path is no longer constant-time",
            ratio < MAX_GROWTH,
        )
    }

    /** A session that never ends must not grow until the app runs out of memory. */
    @Test
    fun aSessionLongerThanTheCapStaysBounded() {
        LatencyScope.initForTesting(LatencyConfig(maxRecords = CAP))
        LatencyScope.start("bounded")

        repeat(CAP * OVERFLOW_FACTOR) { index -> LatencyScope.submit(rawCall(index)) }

        val session = LatencyScope.stop()!!
        assertTrue("kept ${session.records.size} with a cap of $CAP", session.records.size <= CAP)
        assertTrue("dropped records must be counted, not lost silently", session.droppedCount > 0)
    }

    private fun fastestNanosPerCall(calls: Int): Double =
        (1..SAMPLES).minOf { nanosPerCall(calls) }

    private fun nanosPerCall(calls: Int): Double {
        // A cap above the call count keeps trimming out of the measurement:
        // this is about the record path, not the eviction path.
        LatencyScope.initForTesting(LatencyConfig(maxRecords = calls + 1))
        LatencyScope.start("cost")

        repeat(WARMUP) { index -> LatencyScope.submit(rawCall(index)) }
        LatencyScope.clear()

        val started = System.nanoTime()
        repeat(calls) { index -> LatencyScope.submit(rawCall(index)) }
        val elapsed = System.nanoTime() - started

        LatencyScope.stop()
        return elapsed.toDouble() / calls
    }

    private fun rawCall(index: Int): RawCall = RawCall(
        source = Source.MANUAL,
        url = "https://api.example.com/users/$index/orders?page=$index",
        method = "GET",
        statusCode = 200,
        startedAtEpochMs = 1_700_000_000_000L + index,
        startedAtElapsedMs = index.toLong(),
        totalMs = 42,
        phases = Phases(dnsMs = 1, connectMs = 2, serverMs = 30, responseBodyMs = 9),
        requestBytes = 128,
        responseBytes = 4_096,
    )

    private companion object {
        const val SMALL = 2_000
        const val LARGE = 20_000
        const val WARMUP = 5_000
        const val CAP = 500
        const val OVERFLOW_FACTOR = 4

        /**
         * Generous on purpose. A shared CI machine can hand one of two
         * identical loops twice the wall clock; what it cannot do is make a
         * constant-time loop look linear by a factor of four.
         */
        const val MAX_GROWTH = 4.0

        /** Enough to shrug off a scheduling hiccup, few enough to stay quick. */
        const val SAMPLES = 3
    }
}
