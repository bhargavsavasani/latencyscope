package io.github.latencyscope.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What LatencyScope costs the app it is measuring.
 *
 * This is the number the library has to justify: `submit` runs on the thread
 * that just finished an HTTP call, so whatever it spends is added to that
 * call's real latency. The budget is 50 µs per call — small enough to be lost
 * in the noise of any network request, and roughly a thousandth of the
 * fastest call anyone will make over a real connection.
 *
 * It is an instrumented test, not a JVM one, because the cost being measured
 * is partly Android's: the monotonic clock read, the binder round trip behind
 * [NetworkTypeProvider] (cached, which is the point), and ART's allocator
 * rather than the JVM's. A number measured on a desktop JVM would be a
 * different number about a different machine.
 *
 * Run with:
 * ```
 * ./gradlew :latencyscope-core:connectedDebugAndroidTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
class OverheadBenchmark {

    @Before
    fun setUp() {
        LatencyScope.reset()
        LatencyScope.init(
            InstrumentationRegistry.getInstrumentation().targetContext,
            LatencyConfig(maxRecords = CALLS),
        )
    }

    @After
    fun tearDown() {
        LatencyScope.reset()
    }

    @Test
    fun recordingACallCostsLessThanFiftyMicroseconds() {
        val calls = List(CALLS) { index -> rawCall(index) }

        // Baseline: not recording, so submit returns on its first line. This
        // is not "zero" — it is the cost of the call the app makes anyway —
        // and subtracting it is what isolates the library's own overhead.
        LatencyScope.stop()
        repeat(WARMUP) { LatencyScope.submit(calls[it % CALLS]) }
        val baselineNanos = time { calls.forEach(LatencyScope::submit) }

        LatencyScope.start("overhead benchmark")
        repeat(WARMUP) { LatencyScope.submit(calls[it % CALLS]) }
        LatencyScope.clear()
        val recordingNanos = time { calls.forEach(LatencyScope::submit) }

        val addedNanosPerCall = (recordingNanos - baselineNanos).toDouble() / CALLS
        val addedMicros = addedNanosPerCall / 1_000.0

        assertTrue(
            "recording added %.1f µs per call, budget is %d µs".format(addedMicros, BUDGET_MICROS),
            addedMicros < BUDGET_MICROS,
        )
    }

    /**
     * The store is bounded, so a long session must not grow without limit no
     * matter how many calls it sees. An unbounded recorder would eventually
     * OOM the app it was measuring.
     */
    @Test
    fun aLongSessionStaysBounded() {
        LatencyScope.start("bounded")
        val cap = LatencyScope.config().maxRecords

        repeat(cap * OVERFLOW_FACTOR) { index -> LatencyScope.submit(rawCall(index)) }

        val session = LatencyScope.stop()!!
        assertTrue("kept ${session.records.size}, cap is $cap", session.records.size <= cap)
        assertTrue("overflow must be counted, not silently lost", session.droppedCount > 0)
    }

    private inline fun time(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return System.nanoTime() - start
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
        const val CALLS = 10_000
        const val WARMUP = 2_000
        const val BUDGET_MICROS = 50
        const val OVERFLOW_FACTOR = 3
    }
}
