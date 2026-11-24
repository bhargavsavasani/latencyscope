@file:Suppress("UNUSED_PARAMETER", "unused")

package io.github.latencyscope.core

/*
 * No-op mirror of latencyscope-core's model types.
 *
 * These are plain data holders in the real module, so mirroring them means
 * declaring the same shapes. They carry no behaviour to strip; what the no-op
 * removes is everything that *produces* them — see LatencyScope.kt here, where
 * the recorder is gone and nothing is ever constructed.
 *
 * Every declaration is checked against the real module by `:latencyscope-noop:apiCheck`.
 */

/** Mirror of the real [Source]. */
public enum class Source {
    OKHTTP,
    KTOR,
    VOLLEY,
    URL_CONNECTION,
    MANUAL,
    ;

    public val displayName: String
        get() = when (this) {
            OKHTTP -> "OkHttp"
            KTOR -> "Ktor"
            VOLLEY -> "Volley"
            URL_CONNECTION -> "HttpURLConnection"
            MANUAL -> "Manual"
        }

    public val hasFullPhaseBreakdown: Boolean
        get() = this == OKHTTP
}

/** Mirror of the real [Phases]. */
public data class Phases(
    public val queueMs: Long? = null,
    public val dnsMs: Long? = null,
    public val connectMs: Long? = null,
    public val tlsMs: Long? = null,
    public val requestHeadersMs: Long? = null,
    public val requestBodyMs: Long? = null,
    public val serverMs: Long? = null,
    public val responseHeadersMs: Long? = null,
    public val responseBodyMs: Long? = null,
) {
    public val accountedMs: Long get() = 0

    public val isEmpty: Boolean get() = true

    public fun asList(): List<Pair<String, Long?>> = emptyList()

    public companion object {
        public val EMPTY: Phases = Phases()

        public const val LABEL_QUEUE: String = "Queue"
        public const val LABEL_DNS: String = "DNS"
        public const val LABEL_CONNECT: String = "Connect"
        public const val LABEL_TLS: String = "TLS"
        public const val LABEL_REQUEST_HEADERS: String = "Request headers"
        public const val LABEL_REQUEST_BODY: String = "Request body"
        public const val LABEL_SERVER: String = "Server"
        public const val LABEL_RESPONSE_HEADERS: String = "Response headers"
        public const val LABEL_RESPONSE_BODY: String = "Response body"

        public val LABELS: List<String> = emptyList()
    }
}

/** Mirror of the real [ApiCallRecord]. */
public data class ApiCallRecord(
    public val id: String,
    public val source: Source,
    public val url: String,
    public val route: String,
    public val host: String,
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
) {
    public val isSuccess: Boolean get() = true
    public val isError: Boolean get() = false
    public val errorKey: String? get() = null
    public val totalBytes: Long get() = 0
    public val throughputBytesPerSecond: Double? get() = null
    public val endedAtElapsedMs: Long get() = 0
}

/** Mirror of the real [RawCall]. */
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

/** Mirror of the real [Marker]. */
public data class Marker(
    public val label: String,
    public val atEpochMs: Long,
    public val atElapsedMs: Long,
)

/** Mirror of the real [DeviceInfo]. */
public data class DeviceInfo(
    public val manufacturer: String? = null,
    public val model: String? = null,
    public val androidVersion: String? = null,
    public val sdkInt: Int = 0,
    public val appName: String? = null,
    public val appId: String? = null,
    public val appVersionName: String? = null,
    public val appVersionCode: Long = 0,
    public val locale: String? = null,
    public val networkTypeAtStart: String? = null,
    public val linkDownstreamKbps: Int? = null,
    public val linkUpstreamKbps: Int? = null,
    public val libraryVersion: String = BuildInfo.VERSION,
) {
    public companion object {
        public val UNKNOWN: DeviceInfo = DeviceInfo()
    }
}

/** Mirror of the real [BuildInfo]. Same version string, so a report says the truth. */
public object BuildInfo {
    public const val VERSION: String = "0.1.0-SNAPSHOT"
    public const val NAME: String = "LatencyScope"
}

/** Mirror of the real [LatencySession]. */
public data class LatencySession(
    public val id: String,
    public val name: String,
    public val startedAtEpochMs: Long,
    public val endedAtEpochMs: Long?,
    public val durationMs: Long,
    public val device: DeviceInfo,
    public val records: List<ApiCallRecord>,
    public val markers: List<Marker> = emptyList(),
    public val droppedCount: Long = 0,
) {
    public val isComplete: Boolean get() = false
    public val isTruncated: Boolean get() = false
}

/** Mirror of the real [SessionHandle]. [stop] always returns null. */
public class SessionHandle internal constructor(
    public val id: String,
    public val name: String,
    public val startedAtEpochMs: Long,
) {
    public fun stop(): LatencySession? = null

    override fun toString(): String = "SessionHandle(id=$id, name=$name)"
}

/** Mirror of the real [Stats]. */
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
    public val errorRate: Double get() = 0.0

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

/** Mirror of the real [RouteStats]. */
public data class RouteStats(
    public val route: String,
    public val method: String,
    public val host: String,
    public val sources: Set<Source>,
    public val stats: Stats,
) {
    public val key: String get() = "$method $route"

    public fun timeShare(sessionTotalMs: Long): Double = 0.0
}

/** Mirror of the real [NetworkStats]. */
public data class NetworkStats(
    public val networkType: String,
    public val stats: Stats,
)

/** Mirror of the real [HostStats]. */
public data class HostStats(
    public val host: String,
    public val stats: Stats,
)

/** Mirror of the real [ErrorGroup]. */
public data class ErrorGroup(
    public val key: String,
    public val count: Int,
    public val routes: List<String>,
    public val sampleMessage: String?,
)

/** Mirror of the real [LatencyReport]. */
public data class LatencyReport(
    public val session: LatencySession,
    public val overall: Stats,
    public val routes: List<RouteStats>,
    public val hosts: List<HostStats>,
    public val networks: List<NetworkStats>,
    public val errors: List<ErrorGroup>,
    public val warnThresholdMs: Long,
    public val slowThresholdMs: Long,
    public val generatedAtEpochMs: Long,
) {
    public val slowestRoute: RouteStats? get() = null
    public val heaviestRoute: RouteStats? get() = null
    public val hasPhaseData: Boolean get() = false
    public val throughputBytesPerSecond: Double? get() = null
}
