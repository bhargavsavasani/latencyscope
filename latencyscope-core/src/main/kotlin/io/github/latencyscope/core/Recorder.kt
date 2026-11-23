package io.github.latencyscope.core

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Holds the state of one recording session.
 *
 * Everything here is reached from arbitrary network threads, so each field is
 * either immutable or a concurrent structure. There is no lock on the
 * recording path.
 */
internal class Recorder(
    val id: String,
    val name: String,
    val config: LatencyConfig,
    val device: DeviceInfo,
    val clock: Clock,
    val startedAtEpochMs: Long,
    val startedAtElapsedMs: Long,
) {
    private val store = RecordStore(config.maxRecords)
    private val markers = CopyOnWriteArrayList<Marker>()
    private val sequence = AtomicLong(0)
    private val redactor = Redactor(config.captureQueryParams)

    fun nextId(): String = "${id.take(SHORT_ID_LENGTH)}-${sequence.incrementAndGet()}"

    fun accepts(url: String): Boolean = try {
        config.urlFilter(url)
    } catch (_: Exception) {
        // A consumer filter that throws must not break their networking.
        true
    }

    fun record(record: ApiCallRecord) {
        store.add(record)
    }

    fun buildRecord(call: RawCall): ApiCallRecord {
        val redactedUrl = redactor.redactUrl(call.url)
        val parts = UrlParts.parse(redactedUrl)
        val route = try {
            config.routeNormalizer.normalize(parts.path)
        } catch (_: Exception) {
            parts.path
        }
        return ApiCallRecord(
            id = nextId(),
            source = call.source,
            url = redactedUrl,
            route = route,
            host = parts.host,
            method = call.method.uppercase(),
            statusCode = call.statusCode,
            startedAtEpochMs = call.startedAtEpochMs,
            startedAtElapsedMs = call.startedAtElapsedMs - startedAtElapsedMs,
            totalMs = call.totalMs,
            phases = call.phases,
            requestBytes = call.requestBytes,
            responseBytes = call.responseBytes,
            protocol = call.protocol,
            fromCache = call.fromCache,
            retryCount = call.retryCount,
            threadName = call.threadName,
            error = call.error,
            networkType = call.networkType,
        )
    }

    fun mark(label: String) {
        markers += Marker(
            label = label,
            atEpochMs = clock.epochMs(),
            atElapsedMs = clock.elapsedMs() - startedAtElapsedMs,
        )
    }

    fun snapshot(endedAtEpochMs: Long?, nowElapsedMs: Long): LatencySession = LatencySession(
        id = id,
        name = name,
        startedAtEpochMs = startedAtEpochMs,
        endedAtEpochMs = endedAtEpochMs,
        durationMs = (nowElapsedMs - startedAtElapsedMs).coerceAtLeast(0),
        device = device,
        records = store.snapshot(),
        markers = markers.toList(),
        droppedCount = store.droppedCount,
    )

    fun clear() {
        store.clear()
        markers.clear()
    }

    private companion object {
        const val SHORT_ID_LENGTH = 8
    }
}

/**
 * Raw timing data handed in by an instrumentation module.
 *
 * Instrumentation modules stay deliberately dumb: they measure and hand over.
 * Normalization, redaction and id assignment all happen in core so every stack
 * produces identical, comparable records.
 */
public data class RawCall(
    public val source: Source,
    public val url: String,
    public val method: String,
    public val statusCode: Int?,
    public val startedAtEpochMs: Long,
    public val startedAtElapsedMs: Long,
    public val totalMs: Long,
    public val phases: Phases = Phases.EMPTY,
    public val requestBytes: Long = -1,
    public val responseBytes: Long = -1,
    public val protocol: String? = null,
    public val fromCache: Boolean = false,
    public val retryCount: Int = 0,
    public val threadName: String? = null,
    public val error: String? = null,
    public val networkType: String? = null,
)
