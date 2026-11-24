package io.github.latencyscope.ui.compose

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
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
 * It uses the host app's Material theme, so it looks like part of the app it
 * is embedded in. Drop [LatencyReportScreen] into your own screen instead if
 * you want different chrome.
 */
public class LatencyReportActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    LatestReport()
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
