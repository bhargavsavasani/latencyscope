package io.github.latencyscope.export

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.latencyscope.core.LatencyReport
import io.github.latencyscope.core.LatencyScope
import io.github.latencyscope.core.LatencySession
import java.io.File
import java.util.concurrent.Executors

/**
 * A notification that says a session is recording, and stops it when tapped.
 *
 * ```kotlin
 * class App : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         LatencyScope.init(this, LatencyConfig(autoStart = true))
 *         LatencyNotification.install(this)
 *     }
 * }
 * ```
 *
 * From then on, whenever a session starts — by [LatencyScope.start] or by
 * `autoStart` — a notification appears showing how long it has been running
 * and how many calls it has seen, with a **Stop** button. Pressing Stop ends
 * the session, writes the report, and replaces the notification with one that
 * opens it.
 *
 * ### Why a notification rather than stopping on its own
 * Recording that leaves no trace is easy to forget, and a library that
 * decided for itself when a flow had ended would guess wrong: pressing Home
 * mid-checkout is not the end of the checkout. The notification makes the
 * recording visible the whole time it is running, and leaves the decision to
 * the only party who knows the answer.
 *
 * ### What it is not
 * Not a foreground service. The recording happens inside the app's own
 * process while that process is already alive, so there is nothing to keep
 * running — which spares every consumer the `FOREGROUND_SERVICE` permissions
 * and service-type declarations Android 14 requires.
 */
public object LatencyNotification {

    /**
     * Starts showing a notification for every session from now on.
     *
     * Call once, from `Application.onCreate`, **before** [LatencyScope.init]
     * if you use `autoStart` — otherwise the session that `init` starts has
     * already begun and its notification is missed.
     *
     * Does nothing if the user has turned notifications off; see
     * [areNotificationsAllowed].
     */
    @JvmStatic
    @JvmOverloads
    public fun install(
        application: Application,
        config: NotificationConfig = NotificationConfig(),
    ) {
        uninstall(application)

        createChannel(application, config)
        val controller = Controller(application, config)
        ContextCompat.registerReceiver(
            application,
            controller.stopReceiver,
            IntentFilter(ACTION_STOP),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        LatencyScope.addSessionListener(controller)
        installed = controller

        // A session may already be running — `install` after `init` with
        // autoStart, or a re-install. Show it rather than waiting for the
        // next one.
        if (LatencyScope.isRecording()) controller.onSessionChanged(LatencyScope.currentSession())
    }

    /** Removes the notification and stops listening. */
    @JvmStatic
    public fun uninstall(application: Application) {
        val controller = installed ?: return
        installed = null
        LatencyScope.removeSessionListener(controller)
        runCatching { application.unregisterReceiver(controller.stopReceiver) }
        controller.cancelAll()
    }

    /**
     * Whether a notification would actually appear.
     *
     * False when the user denied `POST_NOTIFICATIONS` (Android 13+) or turned
     * the channel off. Nothing here throws in that case — the session still
     * records, it is just invisible — so check this if you want to prompt.
     */
    @JvmStatic
    public fun areNotificationsAllowed(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    private const val ACTION_STOP = "io.github.latencyscope.STOP_SESSION"
    private const val CHANNEL_ID = "latencyscope"
    private const val RECORDING_ID = 0x1A7E
    private const val READY_ID = 0x1A7F

    @Volatile private var installed: Controller? = null

    private fun createChannel(context: Context, config: NotificationConfig) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            config.channelName,
            // Low: this is a status line, not an interruption. No sound, no
            // heads-up banner over whatever the developer is doing.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = config.channelDescription
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    /**
     * Owns the two notifications and the refresh loop.
     *
     * One instance per [install]; [uninstall] drops it.
     */
    private class Controller(
        private val application: Application,
        private val config: NotificationConfig,
    ) : LatencyScope.SessionListener {

        private val manager = NotificationManagerCompat.from(application)
        private val main = Handler(Looper.getMainLooper())

        // Report writing is disk work and must not run on the main thread,
        // which is where a notification action lands.
        private val writer = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "latencyscope-notification").apply { isDaemon = true }
        }

        @Volatile private var sessionName: String? = null
        @Volatile private var startedAtElapsedMs: Long = 0

        // Set while the Stop button is driving the stop, so the listener that
        // fires inside LatencyScope.stop() does not cancel the row this is
        // about to replace with "writing report…".
        private val stoppingFromAction = java.util.concurrent.atomic.AtomicBoolean(false)

        val stopReceiver: BroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                stopAndReport()
            }
        }

        override fun onSessionChanged(session: LatencySession?) {
            if (session == null) {
                if (stoppingFromAction.get()) return
                // The session ended somewhere else — app code calling
                // LatencyScope.stop(), or reset(). Stopping should mean the
                // same thing however it was triggered, so this writes the
                // report too rather than leaving two different meanings of
                // "stop" depending on which button was pressed.
                val finished = LatencyScope.lastSession()
                main.post {
                    refresh.stop()
                    if (finished == null) {
                        // reset(), or a session with nothing to report.
                        manager.cancel(RECORDING_ID)
                    } else {
                        report(finished)
                    }
                }
                return
            }
            sessionName = session.name
            startedAtElapsedMs = LatencyScope.elapsedMs()
            main.post {
                showRecording()
                refresh.start()
            }
        }

        /** Keeps the elapsed time and call count honest while it is on screen. */
        private val refresh = object {
            private var running = false
            private val tick = object : Runnable {
                override fun run() {
                    if (!running) return
                    showRecording()
                    main.postDelayed(this, config.refreshIntervalMs)
                }
            }

            fun start() {
                if (running) return
                running = true
                main.postDelayed(tick, config.refreshIntervalMs)
            }

            fun stop() {
                running = false
                main.removeCallbacks(tick)
            }
        }

        fun cancelAll() {
            main.post { refresh.stop() }
            manager.cancel(RECORDING_ID)
            manager.cancel(READY_ID)
        }

        private fun showRecording() {
            if (!LatencyScope.isRecording()) return

            val elapsed = LatencyScope.elapsedMs() - startedAtElapsedMs
            val calls = LatencyScope.recordedCount()
            val errors = LatencyScope.errorCount()

            val detail = buildString {
                append(LatencyFormat.duration(elapsed))
                append(" · ")
                append(calls)
                append(if (calls == 1) " call" else " calls")
                if (errors > 0) {
                    append(" · ")
                    append(errors)
                    append(" failed")
                }
            }

            val notification = NotificationCompat.Builder(application, CHANNEL_ID)
                .setSmallIcon(config.smallIcon)
                .setContentTitle(config.recordingTitle(sessionName))
                .setContentText(detail)
                // Ongoing and unswipeable: an accidental swipe that silently
                // ended a recording would be worse than a sticky row.
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(0, config.stopLabel, stopIntent())
                .build()

            notifySafely(RECORDING_ID, notification)
        }

        private fun stopAndReport() {
            stoppingFromAction.set(true)
            val session = try {
                LatencyScope.stop()
            } finally {
                stoppingFromAction.set(false)
            }
            if (session == null) {
                manager.cancel(RECORDING_ID)
                return
            }

            report(session)
        }

        /**
         * Renders and writes [session], then swaps the notification for one
         * that opens it.
         *
         * Shared by both ways of stopping, so the Stop button and a plain
         * `LatencyScope.stop()` leave exactly the same thing behind.
         */
        private fun report(session: LatencySession) {
            // Hold the row with a "working" state: rendering a busy session is
            // not instant, and a notification that vanished and came back
            // would read as a glitch.
            notifySafely(RECORDING_ID, workingNotification())

            writer.execute {
                val outcome = runCatching {
                    val report = LatencyScope.report(session)
                        ?: error("session had no report")
                    LatencyExport.writeAll(application, report, directoryFor(report))
                }
                main.post {
                    manager.cancel(RECORDING_ID)
                    outcome
                        .onSuccess { files -> showReady(files) }
                        .onFailure { failure ->
                            config.onError(failure)
                            showFailed(failure)
                        }
                }
            }
        }

        /**
         * One place, shared with [LatencyExport.writeAll].
         *
         * These used to be two different defaults — the notification wrote
         * somewhere the plain writers did not — which meant "where is my
         * report" had two answers depending on which button produced it.
         */
        private fun directoryFor(report: LatencyReport): File {
            val root = config.directory ?: LatencyExport.reportsRoot(application)
            prune(root, config.keepLastSessions)
            return File(root, LatencyExport.sessionFolderName(report))
        }

        private fun showReady(files: ExportedFiles) {
            // Both intents need a content:// URI, and FileProvider can only
            // produce one for a directory declared in its paths file. A
            // consumer pointing `directory` somewhere else is not a reason to
            // crash the app from a notification — the report still exists, so
            // say where it is and drop the buttons.
            // Not routed to onError: that reports a failure to *write*, and
            // the report did write. A missing tap target is a degraded
            // notification, not an error the host needs to hear about.
            val open = runCatching { openIntent(files) }.getOrNull()
            val share = runCatching { shareIntent(files) }.getOrNull()

            val builder = NotificationCompat.Builder(application, CHANNEL_ID)
                .setSmallIcon(config.smallIcon)
                .setContentTitle(config.readyTitle)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)

            if (open == null) {
                builder.setContentText(files.html.parentFile?.name ?: files.html.name)
                builder.setStyle(
                    NotificationCompat.BigTextStyle().bigText(files.html.absolutePath),
                )
            } else {
                builder.setContentText(config.readyText)
                // Activity PendingIntents, not broadcasts: since Android 12 a
                // notification may not bounce through a receiver to start an
                // activity.
                builder.setContentIntent(open)
                if (share != null) builder.addAction(0, config.shareLabel, share)
            }

            notifySafely(READY_ID, builder.build())
        }

        private fun showFailed(failure: Throwable) {
            val notification = NotificationCompat.Builder(application, CHANNEL_ID)
                .setSmallIcon(config.smallIcon)
                .setContentTitle(config.failedTitle)
                .setContentText(failure.message ?: failure::class.java.simpleName)
                .setAutoCancel(true)
                .build()
            notifySafely(READY_ID, notification)
        }

        private fun workingNotification() =
            NotificationCompat.Builder(application, CHANNEL_ID)
                .setSmallIcon(config.smallIcon)
                .setContentTitle(config.workingTitle)
                .setOngoing(true)
                .setShowWhen(false)
                .setProgress(0, 0, true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

        /**
         * Posts, unless the user has said no.
         *
         * `notify` throws a SecurityException without POST_NOTIFICATIONS on
         * Android 13+, and a debug tool must never be the reason an app
         * crashes.
         */
        private fun notifySafely(id: Int, notification: android.app.Notification) {
            if (!manager.areNotificationsEnabled()) return
            runCatching { manager.notify(id, notification) }
        }

        private fun stopIntent(): PendingIntent = PendingIntent.getBroadcast(
            application,
            0,
            Intent(ACTION_STOP).setPackage(application.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun openIntent(files: ExportedFiles): PendingIntent {
            val view = Intent(Intent.ACTION_VIEW)
                .setDataAndType(LatencyExport.uriFor(application, files.html), "text/html")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            // Wrapped in a chooser so a device with no HTML viewer says so
            // instead of throwing ActivityNotFoundException on tap.
            val chooser = Intent.createChooser(view, config.openChooserTitle)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            return PendingIntent.getActivity(
                application,
                1,
                config.openIntent?.invoke(files) ?: chooser,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun shareIntent(files: ExportedFiles): PendingIntent {
            val uris = ArrayList(files.all.map { LatencyExport.uriFor(application, it) })
            val send = Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType("*/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            val chooser = Intent.createChooser(send, config.shareChooserTitle)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            return PendingIntent.getActivity(
                application,
                2,
                chooser,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Keeps the newest [keep] session directories.
         *
         * A tool meant to be left switched on would otherwise fill the device
         * one session at a time.
         */
        private fun prune(root: File, keep: Int) {
            val sessions = root.listFiles()?.filter { it.isDirectory } ?: return
            if (sessions.size < keep) return
            sessions
                .sortedByDescending { it.lastModified() }
                .drop(keep - 1)
                .forEach { stale -> stale.deleteRecursively() }
        }
    }

}

/** Appearance and behaviour of the recording notification. */
public data class NotificationConfig(
    /**
     * Icon for both notifications. Defaults to Android's own info icon so the
     * library ships no drawable of its own; pass yours for something better.
     */
    public val smallIcon: Int = android.R.drawable.ic_dialog_info,
    public val channelName: String = "LatencyScope",
    public val channelDescription: String = "Shows when API latency is being recorded.",
    /** Title while recording. Receives the session name, which may be null. */
    public val recordingTitle: (String?) -> String = { name ->
        if (name.isNullOrBlank()) "Recording latency" else "Recording · $name"
    },
    public val stopLabel: String = "Stop & report",
    public val workingTitle: String = "Writing report…",
    public val readyTitle: String = "Latency report ready",
    public val readyText: String = "Tap to open, or share all four files.",
    public val failedTitle: String = "Could not write the report",
    public val shareLabel: String = "Share",
    public val openChooserTitle: String = "Open latency report",
    public val shareChooserTitle: String = "Share latency report",
    /** How often the elapsed time and call count are refreshed. */
    public val refreshIntervalMs: Long = 1_000,
    /** Where session folders go. Null means [LatencyExport.reportsRoot]. */
    public val directory: File? = null,
    /** Session folders kept before the oldest are deleted. */
    public val keepLastSessions: Int = 10,
    /** Replaces what tapping the finished notification does. */
    public val openIntent: ((ExportedFiles) -> Intent)? = null,
    /** Called on the main thread if writing the report fails. */
    public val onError: (Throwable) -> Unit = {},
) {
    init {
        require(keepLastSessions > 0) {
            "keepLastSessions must be positive, was $keepLastSessions"
        }
        require(refreshIntervalMs > 0) {
            "refreshIntervalMs must be positive, was $refreshIntervalMs"
        }
    }
}
