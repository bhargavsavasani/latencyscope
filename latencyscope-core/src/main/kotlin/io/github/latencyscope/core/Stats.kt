package io.github.latencyscope.core

/** Latency and volume statistics over a set of calls. */
public data class Stats(
    public val count: Int,
    public val successCount: Int,
    public val errorCount: Int,
    public val avgMs: Double,
    public val minMs: Long,
    public val maxMs: Long,
    public val p50Ms: Long,
    public val p75Ms: Long,
    public val p90Ms: Long,
    public val p95Ms: Long,
    public val p99Ms: Long,
    public val totalMs: Long,
    public val requestBytes: Long,
    public val responseBytes: Long,
    public val phaseAverages: Phases,
) {
    public val errorRate: Double
        get() = if (count == 0) 0.0 else errorCount.toDouble() / count

    public companion object {
        public val EMPTY: Stats = Stats(
            count = 0, successCount = 0, errorCount = 0,
            avgMs = 0.0, minMs = 0, maxMs = 0,
            p50Ms = 0, p75Ms = 0, p90Ms = 0, p95Ms = 0, p99Ms = 0,
            totalMs = 0, requestBytes = 0, responseBytes = 0,
            phaseAverages = Phases.EMPTY,
        )
    }
}

/** Statistics for one normalized route, optionally split by method. */
public data class RouteStats(
    public val route: String,
    public val method: String,
    public val host: String,
    public val sources: Set<Source>,
    public val stats: Stats,
) {
    public val key: String get() = "$method $route"

    /** Share of the session's total request time spent on this route, 0..1. */
    public fun timeShare(sessionTotalMs: Long): Double =
        if (sessionTotalMs <= 0) 0.0 else stats.totalMs.toDouble() / sessionTotalMs
}

/** Statistics for one host, to separate your backend from third parties. */
/**
 * The same call set, split by the transport it travelled over.
 *
 * This is the split that turns "the app is slow" into "the app is slow on
 * cellular": the same route can be perfectly healthy on wifi and unusable on
 * a train, and a single blended p95 hides both facts. Only present for calls
 * whose network could be read — see [ApiCallRecord.networkType].
 */
public data class NetworkStats(
    /** `wifi`, `cellular`, `ethernet`, `vpn`, `none` or `other`. */
    public val networkType: String,
    public val stats: Stats,
)

public data class HostStats(
    public val host: String,
    public val stats: Stats,
)

/** Failures grouped by status code or exception type. */
public data class ErrorGroup(
    public val key: String,
    public val count: Int,
    public val routes: List<String>,
    public val sampleMessage: String?,
)

/**
 * The aggregated view of a session. This is what the renderers consume; they
 * never re-derive statistics themselves, so HTML, CSV and the UI can never
 * disagree about a number.
 */
public data class LatencyReport(
    public val session: LatencySession,
    public val overall: Stats,
    public val routes: List<RouteStats>,
    public val hosts: List<HostStats>,
    /** Per-transport breakdown, busiest first. Empty when no call recorded one. */
    public val networks: List<NetworkStats>,
    public val errors: List<ErrorGroup>,
    public val warnThresholdMs: Long,
    public val slowThresholdMs: Long,
    public val generatedAtEpochMs: Long,
) {
    public val slowestRoute: RouteStats?
        get() = routes.maxByOrNull { it.stats.p95Ms }

    public val heaviestRoute: RouteStats?
        get() = routes.maxByOrNull { it.stats.totalMs }

    /** True when any phase breakdown is available, i.e. at least one OkHttp call. */
    /**
     * Measured download throughput across the session, in bytes per second.
     *
     * Averaged over the calls that could report it — see
     * [ApiCallRecord.throughputBytesPerSecond] — and weighted by bytes, so one
     * tiny response cannot drag the figure around. Null when no call in the
     * session had a measurable transfer window.
     */
    public val throughputBytesPerSecond: Double?
        get() {
            var bytes = 0L
            var millis = 0L
            session.records.forEach { record ->
                val transferMs = record.phases.responseBodyMs ?: return@forEach
                if (transferMs <= 0 || record.responseBytes <= 0) return@forEach
                bytes += record.responseBytes
                millis += transferMs
            }
            return if (millis <= 0) null else bytes * 1_000.0 / millis
        }

    public val hasPhaseData: Boolean
        get() = session.records.any { !it.phases.isEmpty }
}
