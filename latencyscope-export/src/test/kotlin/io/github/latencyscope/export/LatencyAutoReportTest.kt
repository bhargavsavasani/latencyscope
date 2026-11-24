package io.github.latencyscope.export

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.latencyscope.core.Clock
import io.github.latencyscope.core.LatencyConfig
import io.github.latencyscope.core.LatencyScope
import io.github.latencyscope.core.RawCall
import io.github.latencyscope.core.Source
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The point of this class is that a session needs no `stop()` call, so these
 * drive the real lifecycle rather than calling the writer directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LatencyAutoReportTest {

    private val application: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = File.createTempFile("autoreport", "").let { file ->
            file.delete()
            file.mkdirs()
            file
        }
        LatencyScope.reset()
        LatencyScope.setClockForTesting(TestClock())
    }

    @After
    fun tearDown() {
        LatencyAutoReport.uninstall(application)
        LatencyScope.reset()
        directory.deleteRecursively()
    }

    @Test
    fun goingToTheBackgroundWritesTheReport() {
        LatencyAutoReport.install(application, config())
        LatencyScope.initForTesting(LatencyConfig())
        LatencyScope.start("auto")
        LatencyScope.submit(call())

        backgroundTheApp()

        val sessions = awaitSessions(1)
        assertEquals("one session directory expected", 1, sessions.size)
        val written = sessions.single().listFiles()?.map { it.extension }?.sorted()
        assertEquals(listOf("csv", "csv", "html", "json"), written)
    }

    /**
     * The session is deliberately not stopped. Backgrounding twice has to
     * refresh the same session's files, not fragment one flow into two
     * reports because the user glanced at another app.
     */
    @Test
    fun backgroundingTwiceRefreshesOneSessionRatherThanSplittingIt() {
        LatencyAutoReport.install(application, config())
        LatencyScope.initForTesting(LatencyConfig())
        LatencyScope.start("auto")

        LatencyScope.submit(call())
        backgroundTheApp()
        awaitSessions(1)
        LatencyScope.submit(call())
        backgroundTheApp()
        awaitSessions(1)

        assertEquals(1, directory.listFiles()?.count { it.isDirectory })
        assertTrue("still recording after an export", LatencyScope.isRecording())
    }

    /** Nothing recorded means nothing written — not an empty report. */
    @Test
    fun writesNothingWhenNoSessionIsRecording() {
        LatencyAutoReport.install(application, config())
        LatencyScope.initForTesting(LatencyConfig())

        backgroundTheApp()

        assertNothingWritten()
    }

    /**
     * A library meant to be left switched on would otherwise fill the device
     * one background at a time.
     */
    @Test
    fun oldSessionsArePrunedToTheCap() {
        LatencyScope.initForTesting(LatencyConfig())

        repeat(5) { index ->
            LatencyScope.start("session $index")
            LatencyScope.submit(call())
            LatencyAutoReport.writeNow(application, config(keep = 2))
            LatencyScope.stop()
        }

        assertEquals(2, directory.listFiles()?.count { it.isDirectory })
    }

    @Test
    fun writeNowReturnsNullWithNothingRecorded() {
        LatencyScope.initForTesting(LatencyConfig())

        assertNull(LatencyAutoReport.writeNow(application, config()))
    }

    @Test
    fun theDefaultLocationIsReachableWithoutAPermission() {
        val root = LatencyAutoReport.defaultRoot(application)

        assertTrue(
            "must sit under the app's own files directory, which needs no permission",
            root.path.contains("files"),
        )
        assertEquals("latencyscope", root.name)
    }

    /** Uninstall has to actually detach, or a test suite leaks writers. */
    @Test
    fun uninstallStopsWriting() {
        LatencyAutoReport.install(application, config())
        LatencyScope.initForTesting(LatencyConfig())
        LatencyScope.start("auto")
        LatencyScope.submit(call())

        LatencyAutoReport.uninstall(application)
        backgroundTheApp()

        assertNothingWritten()
    }

    @Test
    fun aRotationIsNotABackgroundTransition() {
        LatencyAutoReport.install(application, config())
        LatencyScope.initForTesting(LatencyConfig())
        LatencyScope.start("auto")
        LatencyScope.submit(call())

        // A configuration change starts the replacement activity before
        // stopping the old one, so the started count never reaches zero.
        val first = Robolectric.buildActivity(Activity::class.java).setup()
        val second = Robolectric.buildActivity(Activity::class.java).setup()
        first.pause().stop()

        assertNothingWritten("a rotation must not write a report")

        second.pause().stop()
        assertEquals("leaving the app still writes one", 1, awaitSessions(1).size)
    }

    /**
     * The write runs on a background thread, because blocking the lifecycle
     * callback is exactly what a library measuring latency must not do. So
     * the test waits for it rather than assuming it already happened.
     */
    private fun awaitSessions(expected: Int, timeoutMs: Long = 5_000): List<File> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val sessions = directory.listFiles()?.filter { it.isDirectory }.orEmpty()
            if (sessions.size >= expected) return sessions
            Thread.sleep(POLL_MS)
        }
        return directory.listFiles()?.filter { it.isDirectory }.orEmpty()
    }

    /** Give the writer a chance to misbehave before declaring it silent. */
    private fun assertNothingWritten(message: String = "nothing should have been written") {
        Thread.sleep(SETTLE_MS)
        assertEquals(message, 0, directory.listFiles()?.size ?: 0)
    }

    private fun backgroundTheApp() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        activity.pause().stop()
    }

    private fun config(keep: Int = 10) = AutoReportConfig(
        directory = directory,
        keepLastSessions = keep,
        onError = { failure -> throw AssertionError("auto export failed", failure) },
    )

    private fun call(): RawCall = RawCall(
        source = Source.MANUAL,
        url = "https://api.example.com/users",
        method = "GET",
        statusCode = 200,
        startedAtEpochMs = 1_700_000_000_000,
        startedAtElapsedMs = 0,
        totalMs = 12,
    )

    private class TestClock : Clock {
        override fun epochMs(): Long = 1_700_000_000_000
        override fun elapsedMs(): Long = 0
    }

    private companion object {
        const val POLL_MS = 20L
        const val SETTLE_MS = 300L
    }
}
