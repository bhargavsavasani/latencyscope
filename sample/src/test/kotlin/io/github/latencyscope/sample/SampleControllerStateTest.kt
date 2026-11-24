package io.github.latencyscope.sample

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import io.github.latencyscope.core.Clock
import io.github.latencyscope.core.LatencyConfig
import io.github.latencyscope.core.LatencyScope
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The header and its Start/Stop button must describe the session that is
 * actually running — not the last one this screen happened to start.
 *
 * The notification's Stop button ends sessions without the screen being
 * involved, and before the session listener existed the header went on
 * saying "Recording", offering a Stop button for a session that was over.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SampleControllerStateTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var controller: SampleController

    @Before
    fun setUp() {
        LatencyScope.reset()
        LatencyScope.setClockForTesting(TestClock())
        LatencyScope.initForTesting(LatencyConfig())
        controller = SampleController(context)
    }

    @After
    fun tearDown() {
        controller.close()
        LatencyScope.reset()
    }

    @Test
    fun stoppingFromOutsideTheScreenUpdatesTheHeader() {
        controller.toggleRecording()
        assertTrue("the screen started a session", controller.recording)

        // Exactly what the notification's Stop button does.
        LatencyScope.stop()

        assertFalse("the header must not still offer to stop a finished session", controller.recording)
    }

    @Test
    fun startingFromOutsideTheScreenUpdatesTheHeaderToo() {
        assertFalse(controller.recording)

        LatencyScope.start("started elsewhere")

        assertTrue(controller.recording)
    }

    @Test
    fun theScreensOwnButtonStillWorksBothWays() {
        controller.toggleRecording()
        assertTrue(controller.recording)

        controller.toggleRecording()
        assertFalse(controller.recording)
    }

    /** A closed screen must not keep reacting to sessions. */
    @Test
    fun closingStopsListening() {
        controller.close()

        LatencyScope.start("after close")

        assertFalse(controller.recording)
    }

    private class TestClock : Clock {
        override fun epochMs(): Long = 1_700_000_000_000
        override fun elapsedMs(): Long = 0
    }
}
