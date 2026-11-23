package io.github.latencyscope.ui.view

import io.github.latencyscope.core.Source
import io.github.latencyscope.export.LatencyBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Row building is pure, so the wording and the threshold decisions are tested
 * without inflating a view.
 */
class ReportRowsTest {

    @Test
    fun everyTabIsBookendedBySummaryAndMethodology() {
        ReportTab.entries.forEach { tab ->
            val rows = ReportRows.build(report(), tab, query = "")

            assertTrue("$tab has no summary", rows.first() is ReportRow.Summary)
            assertTrue("$tab has no footer", rows.last() is ReportRow.Footer)
        }
    }

    @Test
    fun routesTabListsOneEntryPerRoute() {
        val rows = ReportRows.build(
            report(
                records = listOf(
                    call(route = "/users/{id}"),
                    call(route = "/users/{id}", startMs = 10),
                    call(route = "/orders", startMs = 20),
                ),
            ),
            ReportTab.ROUTES,
            query = "",
        )

        val entries = rows.filterIsInstance<ReportRow.Entry>()
        assertEquals(2, entries.size)
        assertTrue(entries.any { it.title == "/users/{id}" })
    }

    @Test
    fun callsTabListsEveryCall() {
        val rows = ReportRows.build(
            report(records = List(7) { call(startMs = it * 10L) }),
            ReportTab.CALLS,
            query = "",
        )

        assertEquals(7, rows.filterIsInstance<ReportRow.Entry>().size)
    }

    @Test
    fun failuresTabGroupsByStatusOrException() {
        val rows = ReportRows.build(
            report(
                records = listOf(
                    call(route = "/a", statusCode = 500),
                    call(route = "/b", statusCode = 500, startMs = 1),
                    call(route = "/c", statusCode = null, error = "SocketTimeoutException: slow", startMs = 2),
                ),
            ),
            ReportTab.FAILURES,
            query = "",
        )

        val entries = rows.filterIsInstance<ReportRow.Entry>()
        assertEquals(2, entries.size)
        assertEquals("HTTP 500", entries[0].title)
        assertTrue(entries[0].subtitle.contains("2 calls"))
    }

    @Test
    fun filterMatchesRouteMethodAndHost() {
        val full = report(
            records = listOf(call(route = "/users/{id}"), call(route = "/orders", startMs = 5)),
        )

        assertEquals(1, ReportRows.build(full, ReportTab.ROUTES, "orders").filterIsInstance<ReportRow.Entry>().size)
        assertEquals(2, ReportRows.build(full, ReportTab.ROUTES, "api.example").filterIsInstance<ReportRow.Entry>().size)
        assertEquals(0, ReportRows.build(full, ReportTab.ROUTES, "nothing").filterIsInstance<ReportRow.Entry>().size)
    }

    @Test
    fun filterIsCaseInsensitive() {
        val rows = ReportRows.build(report(), ReportTab.ROUTES, "USERS")

        assertEquals(1, rows.filterIsInstance<ReportRow.Entry>().size)
    }

    @Test
    fun filterCanFindACallByStatusOrError() {
        val full = report(
            records = listOf(
                call(statusCode = 503),
                call(statusCode = null, error = "SocketTimeoutException: slow", startMs = 5),
            ),
        )

        assertEquals(1, ReportRows.build(full, ReportTab.CALLS, "503").filterIsInstance<ReportRow.Entry>().size)
        assertEquals(1, ReportRows.build(full, ReportTab.CALLS, "timeout").filterIsInstance<ReportRow.Entry>().size)
    }

    @Test
    fun latencyBandsFollowTheReportThresholds() {
        val rows = ReportRows.build(
            report(
                records = listOf(
                    call(route = "/fast", totalMs = 100),
                    call(route = "/borderline", totalMs = 500, startMs = 1),
                    call(route = "/slow", totalMs = 4_000, startMs = 2),
                ),
            ),
            ReportTab.CALLS,
            query = "",
        )
        val bands = rows.filterIsInstance<ReportRow.Entry>().associate { it.title to it.band }

        assertEquals(LatencyBand.FAST, bands["/fast"])
        assertEquals(LatencyBand.WARN, bands["/borderline"])
        assertEquals(LatencyBand.SLOW, bands["/slow"])
    }

    @Test
    fun phaseSlicesArePresentOnlyWhenTheStackReportedThem() {
        val withPhases = ReportRows.build(
            report(records = listOf(call(phases = fullPhases()))),
            ReportTab.CALLS,
            query = "",
        ).filterIsInstance<ReportRow.Entry>().single()

        val withoutPhases = ReportRows.build(
            report(records = listOf(call(source = Source.VOLLEY))),
            ReportTab.CALLS,
            query = "",
        ).filterIsInstance<ReportRow.Entry>().single()

        assertEquals(5, withPhases.slices.size)
        assertTrue(withoutPhases.slices.isEmpty())
        // ...and the detail says why, rather than leaving a blank space.
        val route = ReportRows.build(
            report(records = listOf(call(source = Source.VOLLEY))),
            ReportTab.ROUTES,
            query = "",
        ).filterIsInstance<ReportRow.Entry>().single()
        assertTrue(route.detail, route.detail.contains("no phase breakdown"))
    }

    @Test
    fun truncationIsStatedInTheSummary() {
        val warning = ReportRows.summary(report(droppedCount = 4_382)).warning

        assertNotNull(warning)
        assertTrue(warning!!.contains("4382"))
    }

    @Test
    fun aCompleteSessionHasNoWarning() {
        assertNull(ReportRows.summary(report()).warning)
    }

    @Test
    fun failureRowsCarryNoLatencyBecauseAGroupHasNoSingleDuration() {
        val entry = ReportRows.build(
            report(records = listOf(call(statusCode = 500))),
            ReportTab.FAILURES,
            query = "",
        ).filterIsInstance<ReportRow.Entry>().single()

        assertNull(entry.latency)
        assertNull(entry.band)
        assertNull(entry.method)
    }

    @Test
    fun rowIdsAreStableAndUniqueForDiffing() {
        val rows = ReportRows.build(
            report(records = List(5) { call(startMs = it * 10L) }),
            ReportTab.CALLS,
            query = "",
        ).filterIsInstance<ReportRow.Entry>()

        assertEquals(rows.size, rows.map { it.id }.distinct().size)
    }
}
