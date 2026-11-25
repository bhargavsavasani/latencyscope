package io.github.latencyscope.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LatencyConfig.Builder] exists for Java callers, who have no named or
 * default arguments. It is public API and every setter is reachable from
 * outside the library, so each one is checked here.
 */
class LatencyConfigBuilderTest {

    /**
     * Field by field, not `assertEquals(LatencyConfig(), …)`: two default
     * configs are never equal, because `routeNormalizer` defaults to a fresh
     * [DefaultRouteNormalizer] and `urlFilter` to a fresh lambda, neither of
     * which defines equality. That is a wart in a `data class`, but changing
     * it would change published behaviour — it is recorded in PLAN.md instead.
     */
    @Test
    fun anUntouchedBuilderMatchesTheKotlinDefaults() {
        val defaults = LatencyConfig()
        val built = LatencyConfig.Builder().build()

        assertEquals(defaults.maxRecords, built.maxRecords)
        assertEquals(defaults.captureQueryParams, built.captureQueryParams)
        assertEquals(defaults.ratingScale, built.ratingScale)
        assertEquals(defaults.autoStart, built.autoStart)
        assertTrue("the default filter accepts everything", built.urlFilter("https://anything"))
    }

    @Test
    fun everySetterLandsOnTheBuiltConfig() {
        val normalizer = IdentityRouteNormalizer

        val config = LatencyConfig.Builder()
            .maxRecords(17)
            .captureQueryParams(true)
            .routeNormalizer(normalizer)
            .ratingScale(LatencyRatingScale(100, 200, 300, 400))
            .autoStart(true)
            .urlFilter { url -> url.contains("keep") }
            .build()

        assertEquals(17, config.maxRecords)
        assertTrue(config.captureQueryParams)
        assertSame(normalizer, config.routeNormalizer)
        assertEquals(LatencyRatingScale(100, 200, 300, 400), config.ratingScale)
        assertTrue(config.autoStart)
        assertTrue(config.urlFilter("https://host/keep"))
        assertFalse(config.urlFilter("https://host/drop"))
    }

    /** Chaining must not fork: every call has to mutate the same builder. */
    @Test
    fun theBuilderIsOneObjectAllTheWayThrough() {
        val builder = LatencyConfig.Builder()

        assertSame(builder, builder.maxRecords(5))
        assertSame(builder, builder.autoStart(true))

        val config = builder.build()
        assertEquals(5, config.maxRecords)
        assertTrue(config.autoStart)
    }
}
