package io.github.latencyscope.ui.compose

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.latencyscope.core.LatencyScope
import io.github.latencyscope.export.LatencyExport

/**
 * A ready-made screen showing the most recent session.
 *
 * ```kotlin
 * startActivity(LatencyReportActivity.intent(context))
 * ```
 *
 * Launched as its own Activity there is no ambient Compose theme to inherit,
 * so it supplies a plain Material 3 scheme that follows the system's light or
 * dark setting. Drop [LatencyReportScreen] into your own screen instead to
 * have it pick up your app's theme and chrome.
 *
 * Window insets are handled here rather than in [LatencyReportScreen], because
 * only a full-screen host knows it owns the whole window. A consumer who
 * embeds the screen inside their own `Scaffold` already has padding applied
 * by that scaffold, and padding it twice would be just as wrong as not
 * padding it at all.
 */
public class LatencyReportActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15 draws every app edge to edge and ignores the opt-out, so
        // a screen that does not inset itself has its first and last rows
        // hidden under the status bar and the navigation bar.
        enableEdgeToEdge()
        setContent {
            // Without an explicit scheme MaterialTheme is always light, while
            // the report's own accent palette follows the system — which in
            // dark mode produces dark-mode accents on light surfaces.
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
            ) {
                Surface(Modifier.fillMaxSize()) {
                    // safeDrawing, not systemBars: it also covers the display
                    // cutout and any IME, so the title does not end up behind
                    // a camera hole on the phones that have one.
                    LatestReport(Modifier.safeDrawingPadding())
                }
            }
        }
    }

    public companion object {
        @JvmStatic
        public fun intent(context: Context): Intent =
            Intent(context, LatencyReportActivity::class.java)
    }
}

/**
 * Renders the session that finished most recently, or the one in progress.
 *
 * Says plainly when there is nothing to show — an empty screen would read as
 * a broken integration rather than as "you have not recorded anything yet".
 */
@Composable
public fun LatestReport(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val report = LatencyScope.report()

    if (report == null) {
        Text(
            text = "No session recorded yet. Call LatencyScope.start(), use the app, " +
                "then stop the session to see a report here.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier,
        )
        return
    }

    LatencyReportScreen(
        report = report,
        modifier = modifier,
        onShare = { LatencyExport.shareAsync(context, report) },
    )
}
