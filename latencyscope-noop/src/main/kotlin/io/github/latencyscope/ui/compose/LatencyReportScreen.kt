@file:Suppress("UNUSED_PARAMETER", "unused")

package io.github.latencyscope.ui.compose

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.latencyscope.core.LatencyReport
import io.github.latencyscope.export.LatencyBand

/*
 * No-op mirror of latencyscope-ui-compose.
 *
 * A release build has no session to show, so the screen composes nothing at
 * all. Emitting an empty composable rather than a "not available" message is
 * deliberate: this module exists because the app shipped without the report
 * feature, and a stub screen in a release build is an unexplained blank panel
 * to whoever finds it.
 */

/** Mirror of the real [ReportTab]. */
public enum class ReportTab(internal val label: String) {
    ROUTES("Routes"),
    CALLS("Calls"),
    FAILURES("Failures"),
}

/** Mirror of the real [LatencyPalette]. The same colours, since they are data. */
@Immutable
public data class LatencyPalette(
    public val series: List<Color>,
    public val fast: Color,
    public val warn: Color,
    public val slow: Color,
    public val track: Color,
) {
    public fun forSlot(slot: Int): Color = series[slot % series.size]

    public fun forBand(band: LatencyBand): Color = when (band) {
        LatencyBand.FAST -> fast
        LatencyBand.WARN -> warn
        LatencyBand.SLOW -> slow
    }

    public companion object {
        public val Light: LatencyPalette = LatencyPalette(
            series = listOf(
                Color(0xFF2A78D6),
                Color(0xFFEB6834),
                Color(0xFF1BAF7A),
                Color(0xFFEDA100),
                Color(0xFFE87BA4),
            ),
            fast = Color(0xFF006300),
            warn = Color(0xFF8A5A00),
            slow = Color(0xFFA52222),
            track = Color(0xFFE6E5E0),
        )

        public val Dark: LatencyPalette = LatencyPalette(
            series = listOf(
                Color(0xFF3987E5),
                Color(0xFFD95926),
                Color(0xFF199E70),
                Color(0xFFC98500),
                Color(0xFFD55181),
            ),
            fast = Color(0xFF0CA30C),
            warn = Color(0xFFFAB219),
            slow = Color(0xFFE66767),
            track = Color(0xFF2C2C2A),
        )
    }
}

/** Mirror of the real [LatencyReportScreen]. Composes nothing. */
@Composable
public fun LatencyReportScreen(
    report: LatencyReport,
    modifier: Modifier = Modifier,
    onShare: (() -> Unit)? = null,
) {
    // Nothing to show.
}

/** Mirror of the real [LatestReport]. Composes nothing. */
@Composable
public fun LatestReport(modifier: Modifier = Modifier) {
    // There is never a last session.
}

/**
 * Mirror of the real [LatencyReportActivity].
 *
 * Finishes as soon as it starts, so a `startActivity(...)` left behind a debug
 * flag is a no-op rather than an empty screen the user has to back out of.
 */
public class LatencyReportActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }

    public companion object {
        @JvmStatic
        public fun intent(context: Context): Intent =
            Intent(context, LatencyReportActivity::class.java)
    }
}
