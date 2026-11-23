package io.github.latencyscope.ui.view

import android.content.Context
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LatencyReportViewTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun view(): LatencyReportView = LatencyReportView(context)

    private fun LatencyReportView.settle(): List<ReportRow> {
        // AsyncListDiffer posts to the main looper.
        ShadowLooper.idleMainLooper()
        return currentRows()
    }

    @Test
    fun inflatesAndShowsRoutesByDefault() {
        val view = view()
        view.setReport(report(records = listOf(call(route = "/users/{id}"), call(route = "/orders", startMs = 5))))

        val rows = view.settle()
        assertTrue(rows.first() is ReportRow.Summary)
        assertEquals(2, rows.filterIsInstance<ReportRow.Entry>().size)
    }

    @Test
    fun switchingTabsChangesTheList() {
        val view = view()
        view.setReport(report(records = List(4) { call(startMs = it * 10L) }))
        assertEquals(1, view.settle().filterIsInstance<ReportRow.Entry>().size)

        view.setTab(ReportTab.CALLS)

        assertEquals(4, view.settle().filterIsInstance<ReportRow.Entry>().size)
    }

    @Test
    fun theSelectedTabIsMarkedInTextNotOnlyByButtonState() {
        val view = view()
        view.setReport(report())

        val routes = view.findViewById<TextView>(R.id.latencyscope_tab_routes)
        val calls = view.findViewById<TextView>(R.id.latencyscope_tab_calls)
        // A pressed-looking button vanishes in a screenshot pasted into a bug
        // report; the bullet does not.
        assertTrue(routes.text.toString(), routes.text.startsWith("•"))
        assertFalse(calls.text.toString(), calls.text.startsWith("•"))

        view.setTab(ReportTab.CALLS)
        assertTrue(calls.text.startsWith("•"))
    }

    @Test
    fun tabLabelsCarryTheirCounts() {
        val view = view()
        view.setReport(
            report(
                records = listOf(
                    call(route = "/a"),
                    call(route = "/b", startMs = 5, statusCode = 500),
                ),
            ),
        )

        val routes = view.findViewById<TextView>(R.id.latencyscope_tab_routes).text.toString()
        val failures = view.findViewById<TextView>(R.id.latencyscope_tab_failures).text.toString()
        assertTrue(routes, routes.contains("2"))
        assertTrue(failures, failures.contains("1"))
    }

    @Test
    fun typingInTheFilterNarrowsTheList() {
        val view = view()
        view.setReport(
            report(records = listOf(call(route = "/users/{id}"), call(route = "/orders", startMs = 5))),
        )

        view.findViewById<EditText>(R.id.latencyscope_filter).setText("orders")

        val entries = view.settle().filterIsInstance<ReportRow.Entry>()
        assertEquals(1, entries.size)
        assertEquals("/orders", entries.single().title)
    }

    @Test
    fun anEmptyFilterResultSaysSo() {
        val view = view()
        view.setReport(report())

        view.findViewById<EditText>(R.id.latencyscope_filter).setText("nothing matches this")

        val empty = view.findViewById<TextView>(R.id.latencyscope_empty)
        assertEquals(View.VISIBLE, empty.visibility)
        assertEquals(context.getString(R.string.latencyscope_empty_filter), empty.text)
    }

    @Test
    fun noReportExplainsHowToGetOneRatherThanShowingABlankScreen() {
        val view = view()

        view.setReport(null)

        val empty = view.findViewById<TextView>(R.id.latencyscope_empty)
        assertEquals(View.VISIBLE, empty.visibility)
        assertEquals(context.getString(R.string.latencyscope_empty_no_session), empty.text)
        // No point offering a filter over nothing.
        assertEquals(View.GONE, view.findViewById<View>(R.id.latencyscope_filter).visibility)
        assertTrue(view.settle().isEmpty())
    }

    @Test
    fun shareIsHiddenUntilAHandlerIsAttached() {
        val view = view()
        view.setReport(report())
        val share = view.findViewById<View>(R.id.latencyscope_share)
        assertEquals(View.GONE, share.visibility)

        var shared = false
        view.onShare = { shared = true }

        assertEquals(View.VISIBLE, share.visibility)
        share.performClick()
        assertTrue(shared)
    }

    @Test
    fun theHeaderNamesTheSession() {
        val view = view()
        view.setReport(report(name = "browse and checkout"))

        assertEquals(
            "browse and checkout",
            view.findViewById<TextView>(R.id.latencyscope_session_name).text,
        )
    }
}
