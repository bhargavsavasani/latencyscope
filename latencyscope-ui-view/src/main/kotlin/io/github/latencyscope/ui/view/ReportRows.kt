package io.github.latencyscope.ui.view

import io.github.latencyscope.core.ApiCallRecord
import io.github.latencyscope.core.ErrorGroup
import io.github.latencyscope.core.LatencyReport
import io.github.latencyscope.core.RouteStats
import io.github.latencyscope.export.LatencyBand
import io.github.latencyscope.export.LatencyFormat
import io.github.latencyscope.export.PhaseBreakdown
import io.github.latencyscope.export.PhaseSlice

/** Which list the report view is showing. */
public enum class ReportTab {
    ROUTES,
    CALLS,
    FAILURES,
}

/**
 * A row, ready to bind.
 *
 * Building rows here rather than inside the adapter keeps every string and
 * threshold decision testable without inflating a single view.
 */
internal sealed interface ReportRow {

    data class Summary(
        val headline: String,
        val detail: String,
        val warning: String?,
    ) : ReportRow

    data class Entry(
        val id: String,
        val method: String?,
        val title: String,
        val subtitle: String,
        val latency: String?,
        val band: LatencyBand?,
        val detail: String,
        val slices: List<PhaseSlice>,
        val searchText: String,
    ) : ReportRow

    data class Footer(val text: String) : ReportRow
}

/** Turns a report into rows for a tab, applying the text filter. */
internal object ReportRows {

    fun build(report: LatencyReport, tab: ReportTab, query: String): List<ReportRow> {
        val rows = mutableListOf<ReportRow>()
        rows += summary(report)
        rows += when (tab) {
            ReportTab.ROUTES -> report.routes.map { routeRow(report, it) }
            ReportTab.CALLS -> report.session.records.map { callRow(report, it) }
            ReportTab.FAILURES -> report.errors.map { failureRow(it) }
        }.filter { it.matches(query) }
        rows += ReportRow.Footer(methodology(report))
        return rows
    }

    fun summary(report: LatencyReport): ReportRow.Summary {
        val overall = report.overall
        return ReportRow.Summary(
            headline = report.session.name + " · " + LatencyFormat.duration(report.session.durationMs),
            detail = buildString {
                append("calls   ").append(overall.count)
                append("   failed ").append(overall.errorCount)
                append(" (").append(LatencyFormat.percent(overall.errorRate)).append(")\n")
                append("p50     ").append(LatencyFormat.duration(overall.p50Ms))
                append("   p95 ").append(LatencyFormat.duration(overall.p95Ms))
                append("   p99 ").append(LatencyFormat.duration(overall.p99Ms)).append('\n')
                append("data    ").append(LatencyFormat.bytes(overall.requestBytes + overall.responseBytes))
                report.slowestRoute?.let { append("\nslowest ").append(it.key) }
            },
            warning = if (!report.session.isTruncated) {
                null
            } else {
                report.session.droppedCount.toString() +
                    " earlier calls were dropped because the buffer was full; " +
                    "these figures cover the " + overall.count + " that were kept."
            },
        )
    }

    private fun routeRow(report: LatencyReport, route: RouteStats): ReportRow.Entry {
        val stats = route.stats
        return ReportRow.Entry(
            id = "route:" + route.key + ":" + route.host,
            method = route.method,
            title = route.route,
            subtitle = stats.count.toString() + " calls · total " +
                LatencyFormat.duration(stats.totalMs) + " · " +
                LatencyFormat.percent(stats.errorRate) + " failed",
            latency = LatencyFormat.duration(stats.p95Ms),
            band = LatencyFormat.band(report, stats.p95Ms),
            detail = buildString {
                append("host    ").append(route.host).append('\n')
                append("p50 ").append(LatencyFormat.duration(stats.p50Ms))
                append("  p90 ").append(LatencyFormat.duration(stats.p90Ms))
                append("  p95 ").append(LatencyFormat.duration(stats.p95Ms))
                append("  p99 ").append(LatencyFormat.duration(stats.p99Ms))
                append("  max ").append(LatencyFormat.duration(stats.maxMs)).append('\n')
                append("bytes   ").append(LatencyFormat.bytes(stats.requestBytes + stats.responseBytes))
                val slices = PhaseBreakdown.nonEmpty(stats.phaseAverages)
                if (slices.isEmpty()) {
                    append("\nno phase breakdown — ")
                    append(route.sources.joinToString { it.displayName })
                    append(" reports total time only")
                } else {
                    append('\n')
                    slices.forEach { slice ->
                        append('\n').append(slice.label.padEnd(PHASE_LABEL_WIDTH))
                        append(LatencyFormat.duration(slice.ms))
                        append("  ").append(LatencyFormat.percent(slice.share(slices.sumOf { it.ms })))
                    }
                }
            },
            slices = PhaseBreakdown.nonEmpty(stats.phaseAverages),
            searchText = (route.method + " " + route.route + " " + route.host).lowercase(),
        )
    }

    private fun callRow(report: LatencyReport, call: ApiCallRecord): ReportRow.Entry =
        ReportRow.Entry(
            id = "call:" + call.id,
            method = call.method,
            title = call.route,
            subtitle = (call.statusCode?.toString() ?: (call.error ?: "failed")) + " · " +
                call.source.displayName + " · at " + LatencyFormat.duration(call.startedAtElapsedMs),
            latency = LatencyFormat.duration(call.totalMs),
            band = LatencyFormat.band(report, call.totalMs),
            detail = buildString {
                append(call.url).append('\n')
                append("request  ").append(LatencyFormat.bytesOrUnknown(call.requestBytes)).append('\n')
                append("response ").append(LatencyFormat.bytesOrUnknown(call.responseBytes))
                call.protocol?.let { append("\nprotocol ").append(it) }
                if (call.retryCount > 0) append("\nexchanges ").append(call.retryCount + 1)
                if (call.fromCache) append("\ncache    served from cache")
                call.networkType?.let { append("\nnetwork  ").append(it) }
                call.error?.let { append("\nerror    ").append(it) }
                call.phases.asList().forEach { (label, value) ->
                    if (value != null) {
                        append('\n').append(label.lowercase().padEnd(PHASE_DETAIL_WIDTH))
                        append(LatencyFormat.duration(value))
                    }
                }
            },
            slices = PhaseBreakdown.nonEmpty(call.phases),
            searchText = (
                call.method + " " + call.url + " " + call.route + " " +
                    (call.statusCode?.toString() ?: "") + " " + (call.error ?: "")
                ).lowercase(),
        )

    private fun failureRow(group: ErrorGroup): ReportRow.Entry = ReportRow.Entry(
        id = "error:" + group.key,
        method = null,
        title = group.key,
        subtitle = group.count.toString() + " calls · " + group.routes.joinToString(", "),
        latency = null,
        band = null,
        detail = group.sampleMessage ?: "No further detail recorded.",
        slices = emptyList(),
        searchText = (group.key + " " + group.routes.joinToString(" ") + " " +
            (group.sampleMessage ?: "")).lowercase(),
    )

    private fun methodology(report: LatencyReport): String =
        "Percentiles are exact, nearest rank — p95 is a duration some call actually took. " +
            "Server is the gap between the request being fully sent and the first response " +
            "byte. Absent phases are omitted, never shown as zero. " +
            LatencyFormat.scaleSummary(report)

    private fun ReportRow.Entry.matches(query: String): Boolean {
        if (query.isBlank()) return true
        return searchText.contains(query.trim().lowercase())
    }

    private const val PHASE_LABEL_WIDTH = 10
    private const val PHASE_DETAIL_WIDTH = 17
}
