package io.github.latencyscope.urlconnection

import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Counts bytes as the caller reads them and reports completion.
 *
 * Wrapping rather than buffering matters: the point is to measure the stream
 * the app is already reading, not to pull it into memory first. A library that
 * changes when bytes arrive has changed the thing it claims to measure.
 */
internal class CountingInputStream(
    delegate: InputStream,
    private val onFinished: (bytes: Long) -> Unit,
) : FilterInputStream(delegate) {

    /*
     * Reads happen on the thread draining the body; `close()` can come from a
     * different one — a cancel, a `disconnect()` from a timeout watchdog, or a
     * `use {}` unwinding on another thread. So this is a cross-thread handoff,
     * the same one `LatencyHttpURLConnection`'s fields are volatile for.
     *
     * `reported` is atomic rather than volatile because a plain check-then-set
     * lets two threads both pass the check and record the call twice. `count`
     * is volatile so the reporting thread sees the reader's last write instead
     * of a stale or torn value — `long` is not guaranteed atomic on 32-bit.
     */
    @Volatile private var count = 0L
    private val reported = AtomicBoolean(false)

    override fun read(): Int {
        val value = super.read()
        if (value == -1) finish() else count++
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val read = super.read(b, off, len)
        if (read == -1) finish() else count += read
        return read
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(n)
        count += skipped
        return skipped
    }

    override fun close() {
        try {
            super.close()
        } finally {
            // Closing early is normal — a caller may stop reading mid-body —
            // so the record is written with whatever was actually consumed.
            finish()
        }
    }

    private fun finish() {
        // Exactly one caller wins, so the call is recorded exactly once.
        if (!reported.compareAndSet(false, true)) return
        onFinished(count)
    }
}

/** Counts bytes written to a request body. */
internal class CountingOutputStream(
    delegate: OutputStream,
    private val onBytes: (bytes: Long) -> Unit,
) : FilterOutputStream(delegate) {

    // Volatile for the same handoff: the connection reads the running total
    // from whichever thread later builds the record.
    @Volatile private var count = 0L

    override fun write(b: Int) {
        out.write(b)
        count++
        onBytes(count)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        // FilterOutputStream.write(byte[]) loops over write(int) one byte at a
        // time, which would be a real slowdown on a large upload.
        out.write(b, off, len)
        count += len
        onBytes(count)
    }
}
