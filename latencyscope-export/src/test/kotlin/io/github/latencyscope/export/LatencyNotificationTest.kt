package io.github.latencyscope.export

import android.app.Activity
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
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
        // The real production location, so these tests cover the path a
        // device actually writes to rather than a stand-in.
        directory = LatencyExport.reportsRoot(application)
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

        val ready = awaitReadyNotification()
        assertNotNull("tapping it has to open the report", ready.contentIntent)
    }

    /**
     * One press is enough.
     *
     * Rendering a busy session takes a second or two, and the row has to say
     * so for that whole time. If it reads as "nothing happened", the natural
     * response is to press again — which is exactly the report that brought
     * this test about.
     */
    @Test
    fun onePressIsEnoughAndTheRowNeverGoesBlank() {
        LatencyNotification.install(application, config())
        LatencyScope.initForTesting(LatencyConfig())
        LatencyScope.start("checkout")
        repeat(3) { submit() }
        drainMainThread()

        pressStop()

        // Whatever stage it has reached, something must be on screen saying so.
        val titleNow = shadowOf(manager).allNotifications
            .lastOrNull()
            ?.let { shadowOf(it).contentTitle?.toString() }
        assertNotNull("the row went blank the instant Stop was pressed", titleNow)
        assertTrue(
            "expected progress or the finished report, saw \"$titleNow\"",
            titleNow!!.contains("Writing", ignoreCase = true) ||
                titleNow.contains("ready", ignoreCase = true),
        )

        // And it gets there on its own, with no further input.
        assertNotNull(awaitReadyNotification().contentIntent)
        assertEquals(1, awaitSessions(1).size)
    }

    /**
     * The press that was reported as "nothing happened, so I pressed again".
     *
     * The second press used to find the session already stopped and cancel the
     * row outright — so the progress disappeared, the screen went blank, and
     * the report arriving a second later looked like the second press had
     * caused it.
     */
    @Test
    fun aSecondPressWhileGeneratingLeavesTheProgressAlone() {
        LatencyNotification.install(application, config())
        LatencyScope.initForTesting(LatencyConfig())
        LatencyScope.start("checkout")
        repeat(3) { submit() }
        drainMainThread()

        pressStop()
        pressStop()
        pressStop()

        assertTrue(
            "the row must not be cancelled by an impatient second press",
            shadowOf(manager).allNotifications.isNotEmpty(),
        )
        assertNotNull(awaitReadyNotification().contentIntent)
        assertEquals("three presses, one report", 1, awaitSessions(1).size)
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
        awaitReadyNotification()

        val titles = shadowOf(manager).allNotifications.map {
            shadowOf(it).contentTitle?.toString().orEmpty()
        }
        assertTrue("no row should still claim to be recording: $titles", titles.none { it.contains("Recording") })
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

    /**
     * Granted already: the helper must not put a dialog in front of someone
     * who has nothing left to agree to.
     */
    @Test
    fun requestPermissionDoesNothingWhenAlreadyAllowed() {
        shadowOf(manager).setNotificationsEnabled(true)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        val allowed = LatencyNotification.requestPermissionIfNeeded(activity)

        assertTrue("already allowed, so nothing to ask", allowed)
        assertNull(
            "no permission request should have been made",
            shadowOf(activity).lastRequestedPermission,
        )
    }

    /**
     * Denied on 13+: the point of the helper. Without this the notification
     * never appears and the only route is the system settings screen, which
     * is where this started.
     */
    @Test
    fun requestPermissionAsksWhenDenied() {
        shadowOf(manager).setNotificationsEnabled(false)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        val allowed = LatencyNotification.requestPermissionIfNeeded(activity)

        assertFalse("not allowed yet, so it must ask", allowed)
        val requested = shadowOf(activity).lastRequestedPermission
        assertNotNull("a permission request should have been made", requested)
        assertTrue(
            "POST_NOTIFICATIONS is the permission that matters",
            requested!!.requestedPermissions.contains(
                android.Manifest.permission.POST_NOTIFICATIONS,
            ),
        )
        assertEquals(
            LatencyNotification.PERMISSION_REQUEST_CODE,
            requested.requestCode,
        )
    }

    /**
     * An explicit request code must reach the activity, so an app that already
     * uses 0x1A7E for something else can pass its own and still route the
     * result correctly.
     */
    @Test
    fun requestPermissionHonoursAnExplicitRequestCode() {
        shadowOf(manager).setNotificationsEnabled(false)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        LatencyNotification.requestPermissionIfNeeded(activity, requestCode = 4321)

        assertEquals(4321, shadowOf(activity).lastRequestedPermission!!.requestCode)
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

    /**
     * Waits for the notification that offers the finished report.
     *
     * Files appearing on disk is not the end of the flow: the writer thread
     * creates them, then posts back to the main thread to swap the
     * notification. Asserting straight after [awaitSessions] races that post
     * and sometimes sees the "writing report…" row instead.
     */
    private fun awaitReadyNotification(timeoutMs: Long = 5_000): android.app.Notification {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            drainMainThread()
            val ready = shadowOf(manager).allNotifications.lastOrNull { notification ->
                shadowOf(notification).contentTitle?.toString()
                    ?.contains("ready", ignoreCase = true) == true
            }
            if (ready != null) return ready
            Thread.sleep(POLL_MS)
        }
        throw AssertionError(
            "no finished-report notification appeared; saw " +
                shadowOf(manager).allNotifications.map {
                    shadowOf(it).contentTitle?.toString()
                },
        )
    }

    /**
     * Waits for [expected] *complete* session folders.
     *
     * Complete matters: `writeAll` calls `mkdirs()` before it writes anything,
     * so a folder exists for a moment while it is still empty. Waiting only
     * for the directory meant the very next assertion could read a half-written
     * report and fail for no reason anyone could reproduce.
     */
    private fun awaitSessions(expected: Int, timeoutMs: Long = 5_000): List<File> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            drainMainThread()
            val complete = completeSessions()
            if (complete.size >= expected) return complete
            Thread.sleep(POLL_MS)
        }
        return completeSessions()
    }

    private fun completeSessions(): List<File> =
        directory.listFiles()
            ?.filter { it.isDirectory && (it.listFiles()?.size ?: 0) >= FILES_PER_REPORT }
            .orEmpty()

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
        const val POLL_MS = 20L
        const val FILES_PER_REPORT = 4
    }
}
