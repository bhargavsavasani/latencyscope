package io.github.latencyscope.core

/**
 * The boundaries a call duration is rated against, in milliseconds.
 *
 * The defaults are the scale published widely for API response times, and the
 * report prints them beside its verdict. That matters more than it sounds: a
 * reader who looks the numbers up should find the report agreeing with what
 * they find, not quietly using its own private idea of "slow".
 *
 * | Under | Reads as |
 * |---|---|
 * | 200 ms | Excellent |
 * | 500 ms | Good |
 * | 1 s | Acceptable — worth watching |
 * | 2 s | Slow — worth investigating |
 * | above | Poor — users will feel this |
 *
 * These describe a **whole round trip from the device**, which is what the
 * app's user actually waits through. Published figures for a *backend* are
 * usually stricter, because they exclude the radio, DNS and TLS the phone
 * pays for first — compare those against the report's Server phase instead.
 *
 * Only wording and colour depend on this. Nothing about what gets recorded
 * changes if you move a boundary.
 */
public data class LatencyRatingScale(
    public val excellentUnderMs: Long = DEFAULT_EXCELLENT_UNDER_MS,
    public val goodUnderMs: Long = DEFAULT_GOOD_UNDER_MS,
    public val acceptableUnderMs: Long = DEFAULT_ACCEPTABLE_UNDER_MS,
    public val slowUnderMs: Long = DEFAULT_SLOW_UNDER_MS,
) {
    init {
        require(excellentUnderMs in 1 until goodUnderMs) {
            "excellentUnderMs ($excellentUnderMs) must be >0 and below goodUnderMs ($goodUnderMs)"
        }
        require(goodUnderMs < acceptableUnderMs) {
            "goodUnderMs ($goodUnderMs) must be below acceptableUnderMs ($acceptableUnderMs)"
        }
        require(acceptableUnderMs < slowUnderMs) {
            "acceptableUnderMs ($acceptableUnderMs) must be below slowUnderMs ($slowUnderMs)"
        }
    }

    public companion object {
        public const val DEFAULT_EXCELLENT_UNDER_MS: Long = 200
        public const val DEFAULT_GOOD_UNDER_MS: Long = 500
        public const val DEFAULT_ACCEPTABLE_UNDER_MS: Long = 1_000
        public const val DEFAULT_SLOW_UNDER_MS: Long = 2_000
    }
}
