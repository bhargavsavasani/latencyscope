package io.github.latencyscope.ui.view

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import io.github.latencyscope.core.LatencyScope
import io.github.latencyscope.export.LatencyExport

/**
 * A ready-made screen showing the most recent session.
 *
 * ```kotlin
 * startActivity(LatencyReportActivity.intent(context))
 * ```
 */
public class LatencyReportActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(reportView(this))
        title = getString(R.string.latencyscope_title)
    }

    public companion object {
        @JvmStatic
        public fun intent(context: Context): Intent =
            Intent(context, LatencyReportActivity::class.java)
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
            onShare = { LatencyExport.share(context, report) }
        }
    }
