package io.github.latencyscope.export

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.github.latencyscope.core.LatencyScope
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Writes the report to disk on its own, so a session needs no `stop()` call.
 *
 * ```kotlin
 * class App : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         LatencyScope.init(this, LatencyConfig(autoStart = true))
 *         LatencyAutoReport.install(this)
 *     }
 * }
 * ```
 *
 * With `autoStart = true` that is the whole integration: launch the app, use
 * it, press Home, and the report is on disk.
 *
 * ### Why "goes to background" and not "app exits"
 * There is no reliable hook for an app exiting. `Application.onTerminate` is
 * never called on a real device, and a swipe-away, an out-of-memory kill, a
 * force-stop and a crash all destroy the process without running any code at
 * all. Anything written to fire "on exit" silently loses reports — the worst
 * possible failure here, because you only discover it when you go looking for
 * data that was never saved.
 *
 * The last moment that is guaranteed to run is the foreground → background
 * transition, so that is when this writes. In practice it is the same instant
 * for a person — you finish with the app and press Home — but it still works
 * when the system kills the process a minute later.
 *
 * ### What it writes, and what it does not disturb
 * The session is **not** stopped. The report is a snapshot of whatever has
 * been recorded so far, so returning to the app and backgrounding it again
 * refreshes the same session's files rather than starting a new set. Nothing
 * is lost, and a flow split across two visits stays one flow.
 *
 * Files land in `getExternalFilesDir("latencyscope")`, which needs no
 * permission and is reachable over `adb pull` — see the `pullLatencyReports`
 * Gradle task in `:sample`. Writing happens on a background thread; the
 * lifecycle callback itself returns immediately.
 */
public object LatencyAutoReport {

    /**
     * Starts watching [application] for the background transition.
     *
     * Safe to call twice; the second call replaces the first. Intended for
     * debug builds — see `latencyscope-noop`, where this does nothing at all.
     */
    @JvmStatic
    @JvmOverloads
    public fun install(
        application: Application,
        config: AutoReportConfig = AutoReportConfig(),
    ) {
        uninstall(application)
        val callbacks = BackgroundWatcher(application, config)
        watcher = callbacks
        application.registerActivityLifecycleCallbacks(callbacks)
    }

    /** Stops watching. */
    @JvmStatic
    public fun uninstall(application: Application) {
        watcher?.let(application::unregisterActivityLifecycleCallbacks)
        watcher = null
    }

    /**
     * Writes the current report now, on the calling thread.
     *
     * The same thing the background transition does, for a debug menu button
     * or a test. Returns null when nothing has been recorded yet.
     */
    @JvmStatic
    @JvmOverloads
    public fun writeNow(
        application: Application,
        config: AutoReportConfig = AutoReportConfig(),
    ): ExportedFiles? {
        val report = LatencyScope.report() ?: return null
        val root = config.directory ?: defaultRoot(application)
        val directory = File(root, directoryNameFor(report.session.id))
        val files = LatencyExport.writeAll(application, report, directory)
        prune(root, config.keepLastSessions)
        return files
    }

    /** `Android/data/<your app>/files/latencyscope` — no permission needed. */
    @JvmStatic
    public fun defaultRoot(application: Application): File =
        File(
            application.getExternalFilesDir(null) ?: application.filesDir,
            DIRECTORY_NAME,
        )

    @Volatile private var watcher: BackgroundWatcher? = null

    private fun directoryNameFor(sessionId: String): String =
        sessionId.take(SESSION_DIR_ID_LENGTH)

    /**
     * Keeps the newest [keep] session directories and deletes the rest.
     *
     * Without this, a library whose whole purpose is being left switched on
     * would fill the device one background at a time.
     */
    private fun prune(root: File, keep: Int) {
        val sessions = root.listFiles()?.filter { it.isDirectory } ?: return
        if (sessions.size <= keep) return
        sessions
            .sortedByDescending { it.lastModified() }
            .drop(keep)
            .forEach { stale -> stale.deleteRecursively() }
    }

    private class BackgroundWatcher(
        private val application: Application,
        private val config: AutoReportConfig,
    ) : Application.ActivityLifecycleCallbacks {

        // Counting started activities, rather than using ProcessLifecycleOwner,
        // keeps androidx.lifecycle out of every consumer's dependency graph for
        // what is ultimately two callbacks.
        private val started = AtomicInteger(0)

        override fun onActivityStarted(activity: Activity) {
            started.incrementAndGet()
        }

        override fun onActivityStopped(activity: Activity) {
            // A rotation stops the old activity before starting the new one,
            // so only a drop to zero means the app actually left the screen.
            if (started.decrementAndGet() > 0) return
            if (!LatencyScope.isRecording()) return

            writer.execute {
                runCatching { writeNow(application, config) }
                    .onFailure(config.onError)
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    /** One daemon thread: a daemon never holds the process open on its own. */
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "latencyscope-autoreport").apply { isDaemon = true }
    }

    private const val DIRECTORY_NAME = "latencyscope"
    private const val SESSION_DIR_ID_LENGTH = 8
}

/** Settings for [LatencyAutoReport]. */
public data class AutoReportConfig(
    /** Where session folders go. Null means [LatencyAutoReport.defaultRoot]. */
    public val directory: File? = null,
    /** How many session folders to keep before the oldest are deleted. */
    public val keepLastSessions: Int = 10,
    /** Called on the writer thread if a write fails. Silent by default. */
    public val onError: (Throwable) -> Unit = {},
) {
    init {
        require(keepLastSessions > 0) {
            "keepLastSessions must be positive, was $keepLastSessions"
        }
    }
}
