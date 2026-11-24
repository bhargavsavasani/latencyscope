package io.github.latencyscope.sample

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import io.github.latencyscope.core.LatencyConfig
import io.github.latencyscope.core.LatencyScope
import io.github.latencyscope.export.LatencyAutoReport

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
        enableStrictMode()
        installAutoReport()
        LatencyScope.init(
            context = this,
            config = LatencyConfig(
                // Recording from launch, so the sample needs no Start press to
                // be useful. The button is still there to stop and restart.
                autoStart = true,
                maxRecords = 2_000,
                // The sample's burst scenario reuses six distinct paths; keeping
                // query params off means they still collapse into one route.
                captureQueryParams = false,
                warnThresholdMs = 500,
                slowThresholdMs = 1_500,
            ),
        )
    }

    /**
     * Writes the report to disk every time the app goes to the background.
     *
     * With `autoStart` above, this is the whole integration: launch, use the
     * app, press Home, and the files are on disk. There is no reliable hook
     * for an app *exiting* — `onTerminate` never runs on a real device and a
     * swipe-away kills the process outright — so backgrounding is the last
     * moment that is guaranteed to happen.
     *
     * Pull the files into the repo with:
     * ```
     * ./gradlew :sample:pullLatencyReports
     * ```
     */
    private fun installAutoReport() {
        LatencyAutoReport.install(this)
    }

    /**
     * StrictMode, on before LatencyScope so it sees `init` too.
     *
     * A latency library that stalls the main thread would be self-defeating,
     * so the sample runs with the detectors on and logs violations. It is
     * Gated on the debuggable flag rather than `BuildConfig.DEBUG` because
     * that is the condition that actually matters — a variant is either one a
     * developer is running or one a user is — and it needs no build-type
     * branch in source.
     *
     * `penaltyLog`, not `penaltyDeath`: a violation raised from inside a
     * third-party dependency on a device we have not tested would turn the
     * sample into a crash-on-launch, which teaches nobody anything. The log
     * line names the stack either way.
     */
    private fun enableStrictMode() {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable) return

        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .detectCustomSlowCalls()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedSqlLiteObjects()
                .detectActivityLeaks()
                .penaltyLog()
                .build(),
        )
    }
}
