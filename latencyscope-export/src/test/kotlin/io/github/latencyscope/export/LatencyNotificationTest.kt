package io.github.latencyscope.export

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.latencyscope.core.Clock
import io.github.latencyscope.core.LatencyConfig
import io.github.latencyscope.core.LatencyScope
import io.github.latencyscope.core.RawCall
import io.github.latencyscope.core.Source
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * The recording notification is the whole user-facing flow now: start a
 * session however you like, see it on screen, press Stop, get a report.
 */
@RunWith(RobolectricTestRunner::class)
// 33, not 34: Android 13 is where POST_NOTIFICATIONS became a runtime
// permission, which is the boundary this class cares about. It also gives
// these tests their own Robolectric sandbox — androidx FileProvider caches
// its parsed roots in a static map keyed by authority, and sharing that cache
// with another test class means whichever ran first decides which data
// directory the roots point at.
@Config(sdk = [33])
class LatencyNotificationTest {

    private val application: Application get() = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager
        get() = application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private lateinit var directory: File

    @Before
    fun setUp() {
        // cacheDir, not the production default of external files: Robolectric
        // does not populate getExternalFilesDirs(), so FileProvider can map
        // nothing there and the open/share intents could not be asserted.
        // Both are declared roots in latencyscope_file_paths.xml.
        directory = File(application.cacheDir, "latencyscope")
        directory.deleteRecursively()
        clearFileProviderCache()
        LatencyScope.reset()
        LatencyScope.setClockForTesting(TestClock())
    }

    /**
     * androidx FileProvider parses its paths file once per authority and keeps
     * the result in a static map. Robolectric hands every test method a fresh
     * data directory, so without this the first method to touch the provider
     * pins the roots to its own directory and every later one fails to map a
     * file that is perfectly valid.
     *
     * A quirk of the test environment, not of production, where one
     * Application lives for the whole process.
     */
    private fun clearFileProviderCache() {
        runCatching {
            val field = androidx.core.content.FileProvider::class.java
                .getDeclaredField("sCache")
            field.isAccessible = true
            (field.get(null) as? MutableMap<*, *>)?.clear()
        }
    }

    @After
    fun tearDown() {
        LatencyNotification.uninstall(application)
        LatencyScope.reset()
        directory.deleteRecursively()
    }

    /** However the session started, it has to become visible. */
    @Test
    fun startingASessionShowsTheNotification() {
        LatencyNotification.install(application, config())
        LatencyScope.initForTesting(LatencyConfig())

        LatencyScope.start("checkout")
        drainMainThread()

        val posted = shadowOf(manager).allNotifications
        assertEquals(1, posted.size)
        assertTrue(
            "the session name belongs on screen",
            shadowOf(posted.single()).contentTitle.toString().contains("checkout"),
        )
    }

    /**
     * autoStart is the case the notification matters most for: nothing in the
     * app's own code says "recording has begun", so the notification is the
     * only signal.
     */
    @Test
    fun anAutoStartedSessionIsAnnouncedToo() {
        LatencyNotification.install(application, config())

        LatencyScope.initForTesting(LatencyConfig(autoStart = true))
        drainMainThread()

        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    /** Installing after a session is already running must still show it. */
    @Test
    fun installingMidSessionPicksUpTheRunningSession() {
        LatencyScope.initForTesting(LatencyConfig())
        LatencyScope.start("already going")

        LatencyNotification.install(application, config())
        drainMainThread()

        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    @Test
    fun theNotificationCarriesLiveCounts() {
        LatencyNotification.install(application, config())
        LatencyScope.initForTesting(LatencyConfig())
        LatencyScope.start("live")

        submit()
        submit(statusCode = 500)
        ShadowLooper.idleMainLooper()
        // Let one refresh tick land.
        ShadowLooper.idleMainLooper(REFRESH_MS, java.util.concurrent.TimeUnit.MILLISECONDS)

        val text = shadowOf(shadowOf(manager).allNotifications.last()).contentText.toString()
        assertTrue("expected a call count in \"$text\"", text.contains("2 calls"))
        assertTrue("a failure should be called out in \"$text\"", text.contains("1 failed"))
    }

    /** The headline flow: Stop ends the session and leaves a report behind. */
    @Test
    fun pressingStopWritesTheReportAndOffersIt() {
        LatencyNotification.install(application, config())
        LatencyScope.initForTesting(LatencyConfig())
        LatencyScope.start("checkout")
        submit()
        drainMainThread()

        pressStop()

        assertFalse("Stop must end the session", LatencyScope.isRecording())

        val sessions = awaitSessions(1)
        assertEquals(1, sessions.size)
        assertEquals(
            listOf("csv", "csv", "html", "json"),
            sessions.single().listFiles()?.map { it.extension }?.sorted(),
        )

        val ready = shadowOf(manager).allNotifications.last()
        assertTrue(
            "the finished notification should say the report is ready",
            shadowOf(ready).contentTitle.toString().contains("ready", ignoreCase = true),
        )
        assertNotNull("tapping it has to open the report", ready.contentIntent)
    }

    /** Pressing Stop with nothing running must not leave a stuck row. */
    @Test
    fun stopWithNoSessionJustClearsTheNotification() {
        LatencyNotification.install(application, config())
        LatencyScope.initForTesting(LatencyConfig())

        pressStop()
        drainMainThread()

        assertEquals(0, shadowOf(manager).allNotifications.size)
    }

    /**
     * Stop has to mean one thing. Whether the Stop button ends the session or
     * the app calls LatencyScope.stop() itself, the result is the same: the
     * recording row goes, the report is written, and a notification offers it.
     */
    @Test
    fun stoppingFromAppCodeProducesTheSameReportAsTheButton() {
        LatencyNotification.install(application, config())
        LatencyScope.initForTesting(LatencyConfig())
        LatencyScope.start("manual")
        submit()
        drainMainThread()

        LatencyScope.stop()
        drainMainThread()

        assertEquals("the report must be written either way", 1, awaitSessions(1).size)

        val titles = shadowOf(manager).allNotifications.map {
            shadowOf(it).contentTitle?.toString().orEmpty()
        }
        assertTrue("no row should still claim to be recording: $titles", titles.none { it.contains("Recording") })
        assertTrue("the finished report should be offered: $titles", titles.any { it.contains("ready", ignoreCase = true) })
    }

    @Test
    fun uninstallRemovesEverything() {
        LatencyNotification.install(application, config())
        LatencyScope.initForTesting(LatencyConfig())
        LatencyScope.start("gone soon")
        drainMainThread()

        LatencyNotification.uninstall(application)
        drainMainThread()

        assertEquals(0, shadowOf(manager).allNotifications.size)
    }

    /**
     * A debug tool must never be the reason an app crashes, and on Android 13+
     * posting without the permission throws.
     */
    @Test
    fun recordingContinuesWhenNotificationsAreTurnedOff() {
        shadowOf(manager).setNotificationsEnabled(false)
        LatencyNotification.install(application, config())
        LatencyScope.initForTesting(LatencyConfig())

        LatencyScope.start("invisible")
        submit()
        drainMainThread()

        assertFalse(LatencyNotification.areNotificationsAllowed(application))
        assertEquals(0, shadowOf(manager).allNotifications.size)
        assertTrue("the session must record regardless", LatencyScope.isRecording())
        assertEquals(1, LatencyScope.recordedCount())
    }

    // ----------------------------------------------------------------- utils

    private fun pressStop() {
        application.sendBroadcast(
            android.content.Intent("io.github.latencyscope.STOP_SESSION")
                .setPackage(application.packageName),
        )
        drainMainThread()
    }

    private fun drainMainThread() {
        ShadowLooper.idleMainLooper()
    }

    /** The report is written off the main thread, so the test waits for it. */
    private fun awaitSessions(expected: Int, timeoutMs: Long = 5_000): List<File> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            drainMainThread()
            val sessions = directory.listFiles()?.filter { it.isDirectory }.orEmpty()
            if (sessions.size >= expected) return sessions
            Thread.sleep(20)
        }
        return directory.listFiles()?.filter { it.isDirectory }.orEmpty()
    }

    private fun config() = NotificationConfig(
        // null would resolve to exactly `directory`; naming it keeps the
        // assertions readable.
        directory = directory,
        refreshIntervalMs = REFRESH_MS,
        onError = { failure -> throw AssertionError("report write failed", failure) },
    )

    private fun submit(statusCode: Int = 200) {
        LatencyScope.submit(
            RawCall(
                source = Source.MANUAL,
                url = "https://api.example.com/users",
                method = "GET",
                statusCode = statusCode,
                startedAtEpochMs = 1_700_000_000_000,
                startedAtElapsedMs = 0,
                totalMs = 12,
            ),
        )
    }

    private class TestClock : Clock {
        override fun epochMs(): Long = 1_700_000_000_000
        override fun elapsedMs(): Long = 0
    }

    private companion object {
        const val REFRESH_MS = 50L
    }
}
