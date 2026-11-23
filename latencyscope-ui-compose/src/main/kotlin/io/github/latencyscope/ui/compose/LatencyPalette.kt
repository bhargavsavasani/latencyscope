package io.github.latencyscope.ui.compose

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import io.github.latencyscope.export.LatencyBand

/**
 * The same validated palette the HTML report uses.
 *
 * The dark values are selected for a dark surface, not an automatic inversion
 * of the light ones. Phase hues are assigned by slot index and never cycled,
 * so a route's "Server" segment is the same colour in every view.
 */
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

@Composable
internal fun rememberLatencyPalette(): LatencyPalette =
    if (isSystemInDarkTheme()) LatencyPalette.Dark else LatencyPalette.Light
