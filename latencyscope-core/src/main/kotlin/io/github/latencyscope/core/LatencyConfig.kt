package io.github.latencyscope.core

/**
 * Consumer-facing configuration. Defaults are the safe-in-a-shared-report
 * choice: no query values, no headers, redaction on.
 *
 * @property maxRecords ring-buffer cap. Bounded on purpose — an app left
 *   recording overnight must not grow without limit.
 * @property captureQueryParams keep query *values*. Off by default; tokens
 *   live in query strings far more often than people expect.
 * @property routeNormalizer turns `/users/8311/orders` into
 *   `/users/{id}/orders` so one row covers every id. Replace it to teach the
 *   report your own path shapes.
 * @property warnThresholdMs the point at which a latency is shown as
 *   borderline rather than fast. Must be >0 and <= [slowThresholdMs].
 * @property slowThresholdMs the point at which a latency is shown as slow.
 *   These two decide the colour bands in the report and nothing else — they
 *   never affect what is recorded.
 * @property autoStart begin a session as soon as [LatencyScope.init] runs,
 *   for measuring app startup, where there is no later moment to call
 *   [LatencyScope.start].
 * @property urlFilter return false to ignore a URL entirely — analytics
 *   beacons, image CDNs, log uploads. Called on the recording path, so keep
 *   it cheap.
 */
public data class LatencyConfig(
    public val maxRecords: Int = DEFAULT_MAX_RECORDS,
    public val captureQueryParams: Boolean = false,
    public val routeNormalizer: RouteNormalizer = DefaultRouteNormalizer(),
    public val warnThresholdMs: Long = DEFAULT_WARN_THRESHOLD_MS,
    public val slowThresholdMs: Long = DEFAULT_SLOW_THRESHOLD_MS,
    public val autoStart: Boolean = false,
    public val urlFilter: (String) -> Boolean = { true },
) {
    init {
        require(maxRecords > 0) { "maxRecords must be positive, was $maxRecords" }
        require(warnThresholdMs in 1..slowThresholdMs) {
            "warnThresholdMs ($warnThresholdMs) must be >0 and <= slowThresholdMs ($slowThresholdMs)"
        }
    }

    /** Java-friendly builder; Kotlin callers should use named arguments. */
    public class Builder {
        private var config = LatencyConfig()

        public fun maxRecords(value: Int): Builder = apply { config = config.copy(maxRecords = value) }
        public fun captureQueryParams(value: Boolean): Builder = apply {
            config = config.copy(captureQueryParams = value)
        }
        public fun routeNormalizer(value: RouteNormalizer): Builder = apply {
            config = config.copy(routeNormalizer = value)
        }
        public fun warnThresholdMs(value: Long): Builder = apply {
            config = config.copy(warnThresholdMs = value)
        }
        public fun slowThresholdMs(value: Long): Builder = apply {
            config = config.copy(slowThresholdMs = value)
        }
        public fun autoStart(value: Boolean): Builder = apply { config = config.copy(autoStart = value) }
        public fun urlFilter(value: (String) -> Boolean): Builder = apply {
            config = config.copy(urlFilter = value)
        }

        public fun build(): LatencyConfig = config
    }

    public companion object {
        public const val DEFAULT_MAX_RECORDS: Int = 5_000
        public const val DEFAULT_WARN_THRESHOLD_MS: Long = 300
        public const val DEFAULT_SLOW_THRESHOLD_MS: Long = 1_000
    }
}
