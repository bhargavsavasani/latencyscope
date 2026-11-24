@file:Suppress("UNUSED_PARAMETER", "unused")

package io.github.latencyscope.ui.view

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.os.Bundle
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import io.github.latencyscope.core.LatencyReport
import io.github.latencyscope.export.PhaseSlice

/*
 * No-op mirror of latencyscope-ui-view.
 *
 * Same reasoning as the Compose mirror: an app that swapped in this module has
 * no report feature in release, so the views render nothing rather than
 * explaining their own absence to an end user.
 */

/** Mirror of the real [ReportTab]. */
public enum class ReportTab {
    ROUTES,
    CALLS,
    FAILURES,
}

/**
 * Mirror of the real [LatencyReportView]. An empty layout.
 *
 * It stays a real [LinearLayout] so it can still be inflated from a layout
 * file a consumer forgot to put behind a build-type fork; it simply has no
 * children and measures to nothing.
 */
public class LatencyReportView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    /** Settable so consumer code compiles; never invoked, as there is no button. */
    public var onShare: (() -> Unit)? = null

    public fun setReport(report: LatencyReport?) {
        // Nothing to render.
    }

    public fun setTab(tab: ReportTab) {
        // No lists to switch between.
    }
}

/** Mirror of the real [PhaseBarView]. Draws nothing. */
public class PhaseBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    public fun setSlices(slices: List<PhaseSlice>) {
        // No phases were measured.
    }

    public fun legendText(): String = ""

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
    }
}

/** Mirror of the real [LatencyReportActivity]. Finishes immediately. */
public class LatencyReportActivity : AppCompatActivity() {

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

/** Mirror of the real [LatencyReportFragment]. Shows an empty view. */
public class LatencyReportFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = LatencyReportView(inflater.context)
}
