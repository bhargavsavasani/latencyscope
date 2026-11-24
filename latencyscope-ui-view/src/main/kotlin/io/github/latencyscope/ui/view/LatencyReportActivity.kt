package io.github.latencyscope.ui.view

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import io.github.latencyscope.core.LatencyScope
import io.github.latencyscope.export.LatencyExport

/**
 * A ready-made screen showing the most recent session.
 *
 * ```kotlin
 * startActivity(LatencyReportActivity.intent(context))
 * ```
 *
 * Insets are applied here and not in [LatencyReportView], because only a
 * full-screen host knows it owns the whole window — a consumer embedding the
 * view in their own layout handles insets at their root, and padding it twice
 * would be as wrong as not padding it at all.
 */
public class LatencyReportActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = reportView(this)
        setContentView(view)
        applySystemBarInsets(view)
        title = getString(R.string.latencyscope_title)
    }

    public companion object {
        @JvmStatic
        public fun intent(context: Context): Intent =
            Intent(context, LatencyReportActivity::class.java)
    }
}

/**
 * Pads [view] clear of the status bar, navigation bar and display cutout.
 *
 * Android 15 draws every app edge to edge and ignores the opt-out, so without
 * this the first row of the report sits under the status bar and the last one
 * under the navigation bar.
 */
internal fun applySystemBarInsets(view: View) {
    ViewCompat.setOnApplyWindowInsetsListener(view) { target, windowInsets ->
        val bars = windowInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        target.updatePadding(
            left = bars.left,
            top = bars.top,
            right = bars.right,
            bottom = bars.bottom,
        )
        // Consumed: nothing below this view needs to inset itself again.
        WindowInsetsCompat.CONSUMED
    }
}

/**
 * The same screen as a fragment, for apps with a single-activity debug menu.
 */
public class LatencyReportFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = reportView(inflater.context)
}

/**
 * Builds a view bound to the latest report.
 *
 * The report is read once, when the view is created: a report that silently
 * changed underneath the reader while they scrolled would make the numbers
 * impossible to reason about.
 */
internal fun reportView(context: Context): LatencyReportView =
    LatencyReportView(context).apply {
        val report = LatencyScope.report()
        setReport(report)
        if (report != null) {
            onShare = { LatencyExport.shareAsync(context, report) }
        }
    }
