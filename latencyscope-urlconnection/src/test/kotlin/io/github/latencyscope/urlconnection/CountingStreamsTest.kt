package io.github.latencyscope.urlconnection

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class CountingStreamsTest {

    @Test
    fun countsWhatTheCallerActuallyRead() {
        val reported = AtomicLong(-1)
        val stream = CountingInputStream(ByteArrayInputStream(ByteArray(10))) { reported.set(it) }

        stream.readBytes()

        assertEquals(10L, reported.get())
    }

    /** Stopping mid-body is normal; the record reflects what was consumed. */
    @Test
    fun aCallerThatStopsEarlyStillReportsItsPartialRead() {
        val reported = AtomicLong(-1)
        val stream = CountingInputStream(ByteArrayInputStream(ByteArray(100))) { reported.set(it) }

        stream.read(ByteArray(40), 0, 40)
        stream.close()

        assertEquals(40L, reported.get())
    }

    @Test
    fun skippedBytesCount() {
        val reported = AtomicLong(-1)
        val stream = CountingInputStream(ByteArrayInputStream(ByteArray(50))) { reported.set(it) }

        stream.skip(20)
        stream.close()

        assertEquals(20L, reported.get())
    }

    /**
     * Reaching the end and then closing is the ordinary path, and it must not
     * record the same call twice.
     */
    @Test
    fun exhaustingThenClosingReportsOnce() {
        val calls = AtomicInteger(0)
        val stream = CountingInputStream(ByteArrayInputStream(ByteArray(4))) { calls.incrementAndGet() }

        stream.readBytes()
        stream.close()
        stream.close()

        assertEquals(1, calls.get())
    }

    /**
     * The handoff this class is volatile/atomic for: a body being drained on
     * one thread and closed from another — a cancel, or a timeout watchdog
     * calling `disconnect()`. A check-then-set `reported` flag lets both
     * threads through and records the call twice.
     */
    @Test
    fun aConcurrentCloseCannotRecordTheCallTwice() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(TRIALS) {
                val calls = AtomicInteger(0)
                val stream = CountingInputStream(ByteArrayInputStream(ByteArray(64))) {
                    calls.incrementAndGet()
                }
                val start = CountDownLatch(1)
                val done = CountDownLatch(2)

                listOf<() -> Unit>({ stream.readBytes() }, { stream.close() }).forEach { work ->
                    pool.execute {
                        start.await()
                        runCatching(work)
                        done.countDown()
                    }
                }

                start.countDown()
                check(done.await(10, TimeUnit.SECONDS)) { "workers did not finish" }

                assertEquals("recorded twice on trial $it", 1, calls.get())
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun theOutputStreamReportsTheRunningTotal() {
        val seen = AtomicLong(-1)
        val stream = CountingOutputStream(ByteArrayOutputStream()) { seen.set(it) }

        stream.write(ByteArray(30), 0, 30)
        stream.write(1)

        assertEquals(31L, seen.get())
    }

    private companion object {
        /** A race needs repetition to show up at all. */
        const val TRIALS = 200
    }
}
