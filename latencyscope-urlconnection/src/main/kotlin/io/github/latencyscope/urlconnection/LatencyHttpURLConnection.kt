package io.github.latencyscope.urlconnection

import io.github.latencyscope.core.LatencyScope
import io.github.latencyscope.core.Phases
import io.github.latencyscope.core.RawCall
import io.github.latencyscope.core.Source
import io.github.latencyscope.core.describe
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URL
import java.security.Permission
import java.util.concurrent.atomic.AtomicBoolean

/**
 * An [HttpURLConnection] that records its own latency.
 *
 * Obtain one with [LatencyUrlConnection.wrap]; every method delegates to the
 * real connection, so the caller's code is unchanged.
 *
 * ### When the record is written
 * Exactly once, at whichever of these happens first: the response stream is
 * fully read, the response stream is closed, [disconnect] is called, or the
 * call fails. A caller that neither reads nor closes nor disconnects leaks the
 * connection anyway — this records nothing for it rather than guessing a
 * duration.
 *
 * ### Fidelity
 * `HttpURLConnection` exposes no hooks below the socket, so this reports
 * connect time, server think time (headers materialising) and body read time.
 * There is no DNS or TLS split. Records are marked [Source.URL_CONNECTION].
 */
public class LatencyHttpURLConnection internal constructor(
    private val delegate: HttpURLConnection,
) : HttpURLConnection(delegate.url) {

    private val startedAtEpochMs = LatencyScope.epochMs()
    private val startedAtElapsedMs = LatencyScope.elapsedMs()
    private val startNanos = System.nanoTime()
    private val threadName: String = Thread.currentThread().name
    private val finished = AtomicBoolean(false)

    private var connectNanos: Long = 0
    private var serverNanos: Long = 0
    private var bodyStartNanos: Long = 0
    private var bodyNanos: Long = 0
    private var requestBytes: Long = -1
    private var responseBytes: Long = -1
    private var headersResolved = false

    // ---------------------------------------------------------- lifecycle

    override fun connect() {
        val start = System.nanoTime()
        try {
            delegate.connect()
        } catch (throwable: Throwable) {
            connectNanos += System.nanoTime() - start
            fail(throwable)
            throw throwable
        }
        connectNanos += System.nanoTime() - start
    }

    override fun disconnect() {
        delegate.disconnect()
        // A caller that disconnects without draining the body still made a
        // request that cost time; record what we know.
        finish(error = null)
    }

    override fun usingProxy(): Boolean = delegate.usingProxy()

    // ------------------------------------------------------------- streams

    override fun getOutputStream(): OutputStream {
        val stream = try {
            delegate.outputStream
        } catch (throwable: Throwable) {
            fail(throwable)
            throw throwable
        }
        return CountingOutputStream(stream) { bytes -> requestBytes = bytes }
    }

    override fun getInputStream(): InputStream {
        val stream = try {
            timeHeaders { delegate.inputStream }
        } catch (throwable: Throwable) {
            fail(throwable)
            throw throwable
        }
        bodyStartNanos = System.nanoTime()
        return CountingInputStream(stream) { bytes ->
            bodyNanos = System.nanoTime() - bodyStartNanos
            responseBytes = bytes
            finish(error = null)
        }
    }

    /**
     * The error body of a 4xx/5xx. Reading it finishes the record just like the
     * success path, so a failing call is not left unrecorded.
     */
    override fun getErrorStream(): InputStream? {
        val stream = delegate.errorStream ?: return null
        bodyStartNanos = System.nanoTime()
        return CountingInputStream(stream) { bytes ->
            bodyNanos = System.nanoTime() - bodyStartNanos
            responseBytes = bytes
            finish(error = null)
        }
    }

    // ------------------------------------------------------------- headers

    override fun getResponseCode(): Int = try {
        timeHeaders { delegate.responseCode }
    } catch (throwable: Throwable) {
        fail(throwable)
        throw throwable
    }

    override fun getResponseMessage(): String? = timeHeaders { delegate.responseMessage }

    override fun getHeaderField(name: String?): String? = timeHeaders { delegate.getHeaderField(name) }

    override fun getHeaderField(n: Int): String? = timeHeaders { delegate.getHeaderField(n) }

    override fun getHeaderFieldKey(n: Int): String? = timeHeaders { delegate.getHeaderFieldKey(n) }

    override fun getHeaderFields(): MutableMap<String, MutableList<String>> =
        timeHeaders { delegate.headerFields }

    override fun getContentLength(): Int = timeHeaders { delegate.contentLength }

    // getContentLengthLong is API 24+, and the inherited implementation derives
    // it from getHeaderField, which this class already delegates. Overriding it
    // would only add a call that cannot exist on API 21.

    override fun getContentType(): String? = timeHeaders { delegate.contentType }

    override fun getContentEncoding(): String? = timeHeaders { delegate.contentEncoding }

    override fun getDate(): Long = timeHeaders { delegate.date }

    override fun getExpiration(): Long = timeHeaders { delegate.expiration }

    override fun getLastModified(): Long = timeHeaders { delegate.lastModified }

    override fun getContent(): Any? = timeHeaders { delegate.content }

    @Suppress("UNCHECKED_CAST")
    override fun getContent(classes: Array<out Class<*>>?): Any? =
        timeHeaders { delegate.getContent(classes as Array<Class<*>>?) }

    /**
     * Times the first call that forces the response headers to materialise.
     *
     * That wait is the closest this API gets to backend think time, so it is
     * attributed to [Phases.serverMs] rather than lumped into the total.
     */
    private inline fun <T> timeHeaders(block: () -> T): T {
        if (headersResolved) return block()
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            serverNanos += System.nanoTime() - start
            headersResolved = true
        }
    }

    // ------------------------------------------------------ plain delegation

    override fun setRequestMethod(method: String) {
        delegate.requestMethod = method
    }

    override fun getRequestMethod(): String = delegate.requestMethod

    override fun setInstanceFollowRedirects(followRedirects: Boolean) {
        delegate.instanceFollowRedirects = followRedirects
    }

    override fun getInstanceFollowRedirects(): Boolean = delegate.instanceFollowRedirects

    override fun setRequestProperty(key: String?, value: String?) {
        delegate.setRequestProperty(key, value)
    }

    override fun addRequestProperty(key: String?, value: String?) {
        delegate.addRequestProperty(key, value)
    }

    override fun getRequestProperty(key: String?): String? = delegate.getRequestProperty(key)

    override fun getRequestProperties(): MutableMap<String, MutableList<String>> =
        delegate.requestProperties

    override fun setConnectTimeout(timeout: Int) {
        delegate.connectTimeout = timeout
    }

    override fun getConnectTimeout(): Int = delegate.connectTimeout

    override fun setReadTimeout(timeout: Int) {
        delegate.readTimeout = timeout
    }

    override fun getReadTimeout(): Int = delegate.readTimeout

    override fun setDoInput(doinput: Boolean) {
        delegate.doInput = doinput
    }

    override fun getDoInput(): Boolean = delegate.doInput

    override fun setDoOutput(dooutput: Boolean) {
        delegate.doOutput = dooutput
    }

    override fun getDoOutput(): Boolean = delegate.doOutput

    override fun setUseCaches(usecaches: Boolean) {
        delegate.useCaches = usecaches
    }

    override fun getUseCaches(): Boolean = delegate.useCaches

    override fun setAllowUserInteraction(allowuserinteraction: Boolean) {
        delegate.allowUserInteraction = allowuserinteraction
    }

    override fun getAllowUserInteraction(): Boolean = delegate.allowUserInteraction

    override fun setIfModifiedSince(ifmodifiedsince: Long) {
        delegate.ifModifiedSince = ifmodifiedsince
    }

    override fun getIfModifiedSince(): Long = delegate.ifModifiedSince

    override fun setDefaultUseCaches(defaultusecaches: Boolean) {
        delegate.defaultUseCaches = defaultusecaches
    }

    override fun getDefaultUseCaches(): Boolean = delegate.defaultUseCaches

    override fun setChunkedStreamingMode(chunklen: Int) {
        delegate.setChunkedStreamingMode(chunklen)
    }

    override fun setFixedLengthStreamingMode(contentLength: Int) {
        delegate.setFixedLengthStreamingMode(contentLength)
    }

    override fun setFixedLengthStreamingMode(contentLength: Long) {
        delegate.setFixedLengthStreamingMode(contentLength)
    }

    override fun getPermission(): Permission? = delegate.permission

    override fun getURL(): URL = delegate.url

    override fun toString(): String = delegate.toString()

    // --------------------------------------------------------------- output

    private fun fail(throwable: Throwable) {
        finish(error = throwable.describe())
    }

    private fun finish(error: String?) {
        if (!finished.compareAndSet(false, true)) return
        if (!LatencyScope.isRecording()) return

        LatencyScope.submit(
            RawCall(
                source = Source.URL_CONNECTION,
                url = delegate.url.toString(),
                method = runCatching { delegate.requestMethod }.getOrDefault("GET"),
                statusCode = error?.let { null } ?: lastKnownStatus(),
                startedAtEpochMs = startedAtEpochMs,
                startedAtElapsedMs = startedAtElapsedMs,
                totalMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLI,
                phases = Phases(
                    connectMs = connectNanos.toMillisOrNull(),
                    serverMs = serverNanos.toMillisOrNull(),
                    responseBodyMs = bodyNanos.toMillisOrNull(),
                ),
                requestBytes = requestBytes,
                responseBytes = responseBytes,
                threadName = threadName,
                error = error,
            ),
        )
    }

    /** Never forces a network round trip just to label the record. */
    private fun lastKnownStatus(): Int? = try {
        if (headersResolved) delegate.responseCode else null
    } catch (_: ProtocolException) {
        null
    } catch (_: Exception) {
        null
    }

    private fun Long.toMillisOrNull(): Long? = if (this > 0) this / NANOS_PER_MILLI else null

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
