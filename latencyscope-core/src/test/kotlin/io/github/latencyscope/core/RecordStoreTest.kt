package io.github.latencyscope.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordStoreTest {

    @Test
    fun keepsRecordsUpToTheCap() {
        val store = RecordStore(maxRecords = 10)
        repeat(10) { store.add(record(startedAtElapsedMs = it.toLong())) }

        assertEquals(10, store.size)
        assertEquals(0, store.droppedCount)
    }

    @Test
    fun dropsOldestBeyondCapAndCountsIt() {
        val store = RecordStore(maxRecords = 3)
        repeat(5) { store.add(record(route = "/r$it", startedAtElapsedMs = it.toLong())) }

        assertEquals(3, store.size)
        assertEquals(2, store.droppedCount)
        assertEquals(listOf("/r2", "/r3", "/r4"), store.snapshot().map { it.route })
    }

    @Test
    fun snapshotIsOrderedByStartTime() {
        val store = RecordStore(maxRecords = 10)
        store.add(record(route = "/third", startedAtElapsedMs = 300))
        store.add(record(route = "/first", startedAtElapsedMs = 100))
        store.add(record(route = "/second", startedAtElapsedMs = 200))

        assertEquals(listOf("/first", "/second", "/third"), store.snapshot().map { it.route })
    }

    @Test
    fun clearResetsEverything() {
        val store = RecordStore(maxRecords = 2)
        repeat(5) { store.add(record(startedAtElapsedMs = it.toLong())) }
        store.clear()

        assertEquals(0, store.size)
        assertEquals(0, store.droppedCount)
        assertTrue(store.snapshot().isEmpty())
    }

    @Test
    fun rejectsNonPositiveCap() {
        val error = runCatching { RecordStore(maxRecords = 0) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun concurrentAppendsNeverExceedCapAndNeverLoseACall() {
        val threads = 8
        val perThread = 5_000
        val cap = 1_000
        val store = RecordStore(maxRecords = cap)
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) { t ->
            pool.execute {
                start.await()
                repeat(perThread) { i ->
                    store.add(record(route = "/t$t", startedAtElapsedMs = i.toLong()))
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue("workers did not finish", done.await(60, TimeUnit.SECONDS))
        pool.shutdown()

        val total = threads * perThread
        assertEquals(cap, store.size)
        // Every call is either retained or counted as dropped; none vanish.
        assertEquals(total.toLong(), store.size + store.droppedCount)
    }
}
