@file:Suppress("UNUSED_PARAMETER", "unused")

package io.github.latencyscope.core

/*
 * No-op mirrors of core's remaining public types: the clock, configuration,
 * route normalization, redaction, the record store and the aggregator.
 *
 * A consumer can construct any of these in release code — a custom
 * RouteNormalizer, a LatencyConfig built at startup — so the types have to
 * exist and behave benignly. None of them is ever consulted, because nothing
 * records.
 */

/** Mirror of the real [Clock]. */
public interface Clock {
    public fun elapsedMs(): Long

    public fun epochMs(): Long

    public companion object {
        public val SYSTEM: Clock = object : Clock {
            override fun elapsedMs(): Long = 0
            override fun epochMs(): Long = 0
        }
    }
}

/** Mirror of the real [RouteNormalizer]. */
public fun interface RouteNormalizer {
    public fun normalize(path: String): String
}

/**
 * Mirror of the real [DefaultRouteNormalizer].
 *
 * Returns the path unchanged: with no report to aggregate, there is nothing
 * for a placeholder to bucket, and the regex work is exactly the cost a
 * release build is swapping this module in to avoid.
 */
public class DefaultRouteNormalizer(
    private val rules: List<Rule> = emptyList(),
) : RouteNormalizer {

    public data class Rule(
        public val pattern: Regex,
        public val replacement: String,
    )

    override fun normalize(path: String): String = path

    /** Present only because the real class has one; it holds private regexes. */
    public companion object
}

/** Mirror of the real [IdentityRouteNormalizer]. */
public object IdentityRouteNormalizer : RouteNormalizer {
    override fun normalize(path: String): String = path
}

/** Mirror of the real [LatencyConfig]. Validation is kept; it costs nothing. */
public data class LatencyConfig(
    public val maxRecords: Int = DEFAULT_MAX_RECORDS,
    public val captureQueryParams: Boolean = false,
    public val routeNormalizer: RouteNormalizer = DefaultRouteNormalizer(),
    public val ratingScale: LatencyRatingScale = LatencyRatingScale(),
    public val autoStart: Boolean = false,
    public val urlFilter: (String) -> Boolean = { true },
) {
    public class Builder {
        private var config = LatencyConfig()

        public fun maxRecords(value: Int): Builder = apply { config = config.copy(maxRecords = value) }
        public fun captureQueryParams(value: Boolean): Builder = apply {
            config = config.copy(captureQueryParams = value)
        }
        public fun routeNormalizer(value: RouteNormalizer): Builder = apply {
            config = config.copy(routeNormalizer = value)
        }
        public fun ratingScale(value: LatencyRatingScale): Builder = apply {
            config = config.copy(ratingScale = value)
        }
        public fun autoStart(value: Boolean): Builder = apply { config = config.copy(autoStart = value) }
        public fun urlFilter(value: (String) -> Boolean): Builder = apply {
            config = config.copy(urlFilter = value)
        }

        public fun build(): LatencyConfig = config
    }

    public companion object {
        public const val DEFAULT_MAX_RECORDS: Int = 5_000

    }
}

/*
 * Redactor and RecordStore are not mirrored: both are `internal` in
 * latencyscope-core, so no consumer can name them and there is nothing for
 * this module to stand in for.
 */

/** Mirror of the real [Aggregator]. There is never a session to aggregate. */
public object Aggregator {

    @JvmStatic
    @JvmOverloads
    public fun aggregate(
        session: LatencySession,
        ratingScale: LatencyRatingScale = LatencyRatingScale(),
        generatedAtEpochMs: Long = 0,
    ): LatencyReport = LatencyReport(
        session = session,
        overall = Stats.EMPTY,
        routes = emptyList(),
        hosts = emptyList(),
        networks = emptyList(),
        errors = emptyList(),
        ratingScale = ratingScale,
        generatedAtEpochMs = generatedAtEpochMs,
    )

    @JvmStatic
    public fun statsOf(records: List<ApiCallRecord>): Stats = Stats.EMPTY

    @JvmStatic
    public fun percentile(sortedAscending: LongArray, percentile: Double): Long = 0
}
