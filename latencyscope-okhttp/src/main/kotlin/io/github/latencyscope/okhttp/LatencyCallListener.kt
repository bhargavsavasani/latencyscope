package io.github.latencyscope.okhttp

import io.github.latencyscope.core.LatencyScope
import io.github.latencyscope.core.Phases
import io.github.latencyscope.core.RawCall
import io.github.latencyscope.core.Source
import io.github.latencyscope.core.describe
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

/**
 * Per-call listener that turns OkHttp's event stream into one [RawCall].
 *
 * OkHttp creates a fresh listener for every call (see [LatencyEventListenerFactory]),
 * so call state lives in plain fields here rather than in a shared map keyed by
 * `Call`. That removes the usual leak: a cancelled or failed call cannot strand
 * an entry, because there is no entry.
 *
 * ### Redirects and retries
 * One `Call` can perform several HTTP exchanges — redirects, auth challenges,
 * route retries. Phase durations are **summed** across those exchanges and
 * [exchangeCount] records how many happened, so a 3-hop redirect chain reports
 * its real total cost instead of only the last hop.
 *
 * ### Timing source
 * Durations come from [System.nanoTime] — monotonic, unaffected by clock
 * changes, and available without an Android dependency so the whole class is
 * testable on the JVM. The session-relative start offset comes from
 * [LatencyScope.elapsedMs] so it shares a timebase with markers.
 */
public class LatencyCallListener internal constructor(
    private val delegate: EventListener?,
) : EventListener() {

    private var startedAtEpochMs: Long = 0
    private var startedAtElapsedMs: Long = 0
    private var callStartNanos: Long = 0

    private var url: String? = null
    private var method: String = "GET"
    private var statusCode: Int? = null
    private var protocol: String? = null
    private var fromCache: Boolean = false
    private var threadName: String? = null
    private var finished: Boolean = false

    /** Number of HTTP exchanges performed; >1 means redirects or retries. */
    private var exchangeCount: Int = 0

    private var requestBytes: Long = -1
    private var responseBytes: Long = -1

    // Phase accumulators, in nanoseconds. Null start marks "phase not running".
    private var firstWorkNanos: Long? = null
    private var dnsStart: Long? = null
    private var dnsTotal: Long = 0
    private var connectStart: Long? = null
    private var connectTotal: Long = 0
    private var tlsStart: Long? = null
    private var tlsTotal: Long = 0
    private var requestHeadersStart: Long? = null
    private var requestHeadersTotal: Long = 0
    private var requestBodyStart: Long? = null
    private var requestBodyTotal: Long = 0
    private var requestEndNanos: Long? = null
    private var serverTotal: Long = 0
    private var responseHeadersStart: Long? = null
    private var responseHeadersTotal: Long = 0
    private var responseBodyStart: Long? = null
    private var responseBodyTotal: Long = 0

    // ------------------------------------------------------------ call scope

    override fun callStart(call: Call) {
        delegate?.callStart(call)
        callStartNanos = System.nanoTime()
        startedAtEpochMs = LatencyScope.epochMs()
        startedAtElapsedMs = LatencyScope.elapsedMs()
        threadName = Thread.currentThread().name
        val request = call.request()
        url = request.url.toString()
        method = request.method
    }

    override fun callEnd(call: Call) {
        delegate?.callEnd(call)
        finish(error = null)
    }

    override fun callFailed(call: Call, ioe: IOException) {
        delegate?.callFailed(call, ioe)
        finish(error = ioe.describe())
    }

    override fun canceled(call: Call) {
        delegate?.canceled(call)
        // OkHttp still delivers callEnd/callFailed after a cancel, so the record
        // is written there; cancelling alone must not emit a second record.
    }

    // -------------------------------------------------------------- phases

    override fun dnsStart(call: Call, domainName: String) {
        delegate?.dnsStart(call, domainName)
        markFirstWork()
        dnsStart = System.nanoTime()
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        delegate?.dnsEnd(call, domainName, inetAddressList)
        dnsTotal += elapsedSince(dnsStart)
        dnsStart = null
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        delegate?.connectStart(call, inetSocketAddress, proxy)
        markFirstWork()
        connectStart = System.nanoTime()
    }

    override fun secureConnectStart(call: Call) {
        delegate?.secureConnectStart(call)
        tlsStart = System.nanoTime()
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        delegate?.secureConnectEnd(call, handshake)
        tlsTotal += elapsedSince(tlsStart)
        tlsStart = null
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
    ) {
        delegate?.connectEnd(call, inetSocketAddress, proxy, protocol)
        connectTotal += elapsedSince(connectStart)
        connectStart = null
        protocol?.let { this.protocol = it.toString() }
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) {
        delegate?.connectFailed(call, inetSocketAddress, proxy, protocol, ioe)
        connectTotal += elapsedSince(connectStart)
        connectStart = null
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        delegate?.connectionAcquired(call, connection)
        // A pooled connection skips DNS/connect/TLS entirely; this is the first
        // point where we know work on the wire is about to begin.
        markFirstWork()
        protocol = protocol ?: connection.protocol().toString()
    }

    override fun requestHeadersStart(call: Call) {
        delegate?.requestHeadersStart(call)
        markFirstWork()
        exchangeCount++
        requestHeadersStart = System.nanoTime()
    }

    override fun requestHeadersEnd(call: Call, request: Request) {
        delegate?.requestHeadersEnd(call, request)
        requestHeadersTotal += elapsedSince(requestHeadersStart)
        requestHeadersStart = null
        requestEndNanos = System.nanoTime()
    }

    override fun requestBodyStart(call: Call) {
        delegate?.requestBodyStart(call)
        requestBodyStart = System.nanoTime()
    }

    override fun requestBodyEnd(call: Call, byteCount: Long) {
        delegate?.requestBodyEnd(call, byteCount)
        requestBodyTotal += elapsedSince(requestBodyStart)
        requestBodyStart = null
        requestEndNanos = System.nanoTime()
        requestBytes = maxOf(requestBytes, 0) + byteCount
    }

    override fun requestFailed(call: Call, ioe: IOException) {
        delegate?.requestFailed(call, ioe)
        requestHeadersTotal += elapsedSince(requestHeadersStart)
        requestHeadersStart = null
        requestBodyTotal += elapsedSince(requestBodyStart)
        requestBodyStart = null
    }

    override fun responseHeadersStart(call: Call) {
        delegate?.responseHeadersStart(call)
        val now = System.nanoTime()
        // Server think time: request fully sent -> first response byte back.
        requestEndNanos?.let { serverTotal += (now - it).coerceAtLeast(0) }
        requestEndNanos = null
        responseHeadersStart = now
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        delegate?.responseHeadersEnd(call, response)
        responseHeadersTotal += elapsedSince(responseHeadersStart)
        responseHeadersStart = null
        // Last status wins, which for a redirect chain is the one the caller saw.
        statusCode = response.code
        protocol = response.protocol.toString()
    }

    override fun responseBodyStart(call: Call) {
        delegate?.responseBodyStart(call)
        responseBodyStart = System.nanoTime()
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        delegate?.responseBodyEnd(call, byteCount)
        responseBodyTotal += elapsedSince(responseBodyStart)
        responseBodyStart = null
        responseBytes = maxOf(responseBytes, 0) + byteCount
    }

    override fun responseFailed(call: Call, ioe: IOException) {
        delegate?.responseFailed(call, ioe)
        responseHeadersTotal += elapsedSince(responseHeadersStart)
        responseHeadersStart = null
        responseBodyTotal += elapsedSince(responseBodyStart)
        responseBodyStart = null
    }

    // --------------------------------------------------------------- cache

    override fun cacheHit(call: Call, response: Response) {
        delegate?.cacheHit(call, response)
        fromCache = true
        statusCode = response.code
    }

    override fun cacheConditionalHit(call: Call, cachedResponse: Response) {
        delegate?.cacheConditionalHit(call, cachedResponse)
        fromCache = true
    }

    override fun cacheMiss(call: Call) {
        delegate?.cacheMiss(call)
    }

    override fun satisfactionFailure(call: Call, response: Response) {
        delegate?.satisfactionFailure(call, response)
    }

    override fun proxySelectStart(call: Call, url: HttpUrl) {
        delegate?.proxySelectStart(call, url)
    }

    override fun proxySelectEnd(call: Call, url: HttpUrl, proxies: List<Proxy>) {
        delegate?.proxySelectEnd(call, url, proxies)
    }

    override fun connectionReleased(call: Call, connection: Connection) {
        delegate?.connectionReleased(call, connection)
    }

    // --------------------------------------------------------------- output

    private fun finish(error: String?) {
        // callEnd and callFailed can both arrive for one call; emit once.
        if (finished) return
        finished = true

        val callUrl = url ?: return
        val totalNanos = System.nanoTime() - callStartNanos

        LatencyScope.submit(
            RawCall(
                source = Source.OKHTTP,
                url = callUrl,
                method = method,
                statusCode = statusCode,
                startedAtEpochMs = startedAtEpochMs,
                startedAtElapsedMs = startedAtElapsedMs,
                totalMs = totalNanos.toMillis(),
                phases = buildPhases(),
                requestBytes = requestBytes,
                responseBytes = responseBytes,
                protocol = protocol,
                fromCache = fromCache,
                retryCount = (exchangeCount - 1).coerceAtLeast(0),
                threadName = threadName,
                error = error,
            ),
        )
    }

    private fun buildPhases(): Phases {
        // Connect time reported by OkHttp includes the TLS handshake; splitting
        // them out keeps the stacked bar honest (no double counting).
        val connectWithoutTls = (connectTotal - tlsTotal).coerceAtLeast(0)
        val queueNanos = firstWorkNanos?.let { (it - callStartNanos).coerceAtLeast(0) }

        return Phases(
            queueMs = queueNanos?.toMillis(),
            dnsMs = dnsTotal.toMillisOrNull(),
            connectMs = connectWithoutTls.toMillisOrNull(connectTotal > 0),
            tlsMs = tlsTotal.toMillisOrNull(),
            requestHeadersMs = requestHeadersTotal.toMillisOrNull(),
            requestBodyMs = requestBodyTotal.toMillisOrNull(),
            serverMs = serverTotal.toMillisOrNull(),
            responseHeadersMs = responseHeadersTotal.toMillisOrNull(),
            responseBodyMs = responseBodyTotal.toMillisOrNull(),
        )
    }

    private fun markFirstWork() {
        if (firstWorkNanos == null) firstWorkNanos = System.nanoTime()
    }

    private fun elapsedSince(startNanos: Long?): Long =
        startNanos?.let { (System.nanoTime() - it).coerceAtLeast(0) } ?: 0

    private fun Long.toMillis(): Long = this / NANOS_PER_MILLI

    /** Null when the phase never ran, so the report can say "not applicable". */
    private fun Long.toMillisOrNull(occurred: Boolean = this > 0): Long? =
        if (occurred) this / NANOS_PER_MILLI else null

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
