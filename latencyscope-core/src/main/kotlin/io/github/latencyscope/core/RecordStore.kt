package io.github.latencyscope.core

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded, lock-free store of finished calls.
 *
 * The append path runs on whatever thread finished the HTTP call, so it must
 * not block, allocate much, or touch disk. When the cap is reached the oldest
 * record is dropped and counted — a report that silently loses data is worse
 * than one that says "4,382 earlier calls were dropped".
 */
public class RecordStore(
    private val maxRecords: Int,
) {
    init {
        require(maxRecords > 0) { "maxRecords must be positive, was $maxRecords" }
    }

    private val queue = ConcurrentLinkedQueue<ApiCallRecord>()
    private val count = AtomicInteger(0)
    private val dropped = AtomicLong(0)

    public val size: Int
        get() = count.get()

    public val droppedCount: Long
        get() = dropped.get()

    public fun add(record: ApiCallRecord) {
        queue.add(record)
        // Trim in a loop: concurrent adds can briefly overshoot the cap.
        var over = count.incrementAndGet() - maxRecords
        while (over > 0) {
            // A null poll means a concurrent trim already drained it. Keep
            // looping only while we are actually removing something, or two
            // threads can spin against each other forever.
            if (queue.poll() == null) break
            count.decrementAndGet()
            dropped.incrementAndGet()
            over = count.get() - maxRecords
        }
    }

    /** Snapshot ordered by start time, oldest first. */
    public fun snapshot(): List<ApiCallRecord> =
        queue.toList().sortedBy { it.startedAtElapsedMs }

    public fun clear() {
        queue.clear()
        count.set(0)
        dropped.set(0)
    }
}
