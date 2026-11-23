package io.github.latencyscope.urlconnection

import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream

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

    private var count = 0L
    private var reported = false

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
        if (reported) return
        reported = true
        onFinished(count)
    }
}

/** Counts bytes written to a request body. */
internal class CountingOutputStream(
    delegate: OutputStream,
    private val onBytes: (bytes: Long) -> Unit,
) : FilterOutputStream(delegate) {

    private var count = 0L

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
