package io.github.latencyscope.ui.view

import android.graphics.Insets
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Android 15 draws every app edge to edge and ignores the opt-out, so a screen
 * that does not inset itself has its first row hidden under the status bar and
 * its last under the navigation bar — which is exactly what the report screen
 * did until this was added.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WindowInsetsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun theReportIsPaddedClearOfTheSystemBars() {
        val view = View(context)
        applySystemBarInsets(view)

        dispatch(view, statusBar = 83, navigationBar = 44)

        assertEquals("content would sit under the status bar", 83, view.paddingTop)
        assertEquals("content would sit under the navigation bar", 44, view.paddingBottom)
    }

    /** A camera cutout eats the same space as a status bar and is reported separately. */
    @Test
    fun aDisplayCutoutIsTreatedLikeASystemBar() {
        val view = View(context)
        applySystemBarInsets(view)

        dispatch(view, statusBar = 0, navigationBar = 0, cutoutTop = 120)

        assertEquals(120, view.paddingTop)
    }

    /** Landscape: the gesture bar and cutout move to the sides. */
    @Test
    fun horizontalInsetsArePaddedToo() {
        val view = View(context)
        applySystemBarInsets(view)

        dispatch(view, statusBar = 0, navigationBar = 0, left = 48, right = 48)

        assertEquals(48, view.paddingLeft)
        assertEquals(48, view.paddingRight)
    }

    /** Nothing below should inset a second time and double the gap. */
    @Test
    fun theInsetsAreConsumed() {
        val view = View(context)
        applySystemBarInsets(view)

        val result = dispatch(view, statusBar = 83, navigationBar = 44)

        assertEquals(
            Insets.NONE,
            result.getInsets(WindowInsetsCompat.Type.systemBars()).toPlatformInsets(),
        )
    }

    private fun dispatch(
        view: View,
        statusBar: Int,
        navigationBar: Int,
        cutoutTop: Int = 0,
        left: Int = 0,
        right: Int = 0,
    ): WindowInsetsCompat {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(
                WindowInsetsCompat.Type.systemBars(),
                androidx.core.graphics.Insets.of(left, statusBar, right, navigationBar),
            )
            .setInsets(
                WindowInsetsCompat.Type.displayCutout(),
                androidx.core.graphics.Insets.of(0, cutoutTop, 0, 0),
            )
            .build()

        return ViewCompat.dispatchApplyWindowInsets(view, insets)
    }
}
