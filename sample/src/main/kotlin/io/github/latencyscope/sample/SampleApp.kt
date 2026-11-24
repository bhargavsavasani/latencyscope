package io.github.latencyscope.sample

import android.app.Application
import io.github.latencyscope.core.LatencyConfig
import io.github.latencyscope.core.LatencyScope

/**
 * Initialisation, in the one place a consumer should do it.
 *
 * `init` is called unconditionally, in both build types. On release this is
 * the no-op's `init`, which does nothing and holds nothing — the point being
 * that an app never needs a `BuildConfig.DEBUG` branch around LatencyScope.
 */
internal class SampleApp : Application() {

    override fun onCreate() {
        super.onCreate()
        LatencyScope.init(
            context = this,
            config = LatencyConfig(
                maxRecords = 2_000,
                // The sample's burst scenario reuses six distinct paths; keeping
                // query params off means they still collapse into one route.
                captureQueryParams = false,
                warnThresholdMs = 500,
                slowThresholdMs = 1_500,
            ),
        )
    }
}
