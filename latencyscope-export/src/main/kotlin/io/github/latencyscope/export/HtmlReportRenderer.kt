package io.github.latencyscope.export

import io.github.latencyscope.core.ApiCallRecord
import io.github.latencyscope.core.DeviceInfo
import io.github.latencyscope.core.LatencyReport
import io.github.latencyscope.core.Phases
import io.github.latencyscope.core.RouteStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders a [LatencyReport] as one self-contained HTML file.
 *
 * No network requests, no external stylesheet, no charting library: the file
 * opens from a phone's Downloads folder on a plane. Every figure is rendered
 * server-side so the report is complete with scripting disabled; the inline
 * script only adds sorting, filtering and tooltips.
 */
internal object HtmlReportRenderer {

    /** Fixed colour order; slot indexes come from [PhaseBreakdown]. */
    private val PHASE_COLOR_VARS = listOf(
        "--series-1", "--series-2", "--series-3", "--series-4", "--series-5",
    )

    private const val MAX_WATERFALL_ROWS = 400
    private const val MAX_CALL_ROWS = 1_000
    private const val MAX_HISTOGRAM_ROUTES = 12
    private const val HISTOGRAM_BUCKETS = 12

    fun render(report: LatencyReport): String {
        val html = StringBuilder(INITIAL_CAPACITY)
        html.append("<!doctype html>\n<html lang=\"en\">\n<head>\n")
        html.append("<meta charset=\"utf-8\">\n")
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        html.append("<title>").append(esc(reportTitle(report))).append("</title>\n")
        html.append("<style>").append(REPORT_CSS).append("</style>\n")
        html.append("</head>\n<body>\n<div class=\"wrap\">\n")

        html.appendHeader(report)
        html.appendTiles(report)
        html.appendRoutes(report)
        html.appendPhases(report)
        html.appendTimeline(report)
        html.appendDistributions(report)
        html.appendNetworks(report)
        html.appendErrors(report)
        html.appendCallLog(report)
        html.appendFooter(report)

        html.append("</div>\n")
        html.append("<script id=\"latencyscope-data\" type=\"application/json\">")
            .append(ReportJson.encode(report))
            .append("</script>\n")
        html.append("<script>").append(REPORT_JS).append("</script>\n")
        html.append("</body>\n</html>\n")
        return html.toString()
    }

    // ---------------------------------------------------------------- header

    private fun StringBuilder.appendHeader(report: LatencyReport) {
        val session = report.session
        val device = session.device

        append("<header class=\"page\">\n")
        append("<h1>").append(esc(session.name)).append("</h1>\n")
        append("<p class=\"subtitle\">API latency report &middot; ")
            .append(esc(device.libraryVersion.let { "LatencyScope $it" }))
            .append("</p>\n")

        append("<div class=\"meta-grid\">\n")
        metaRow("Recorded", formatRange(session.startedAtEpochMs, session.endedAtEpochMs))
        metaRow("Session length", formatMs(session.durationMs))
        metaRow("Calls", session.records.size.toString())
        metaRow(
            "App",
            listOfNotNull(
                device.appName,
                device.appId?.let { "($it)" },
                device.appVersionName?.let { "v$it" },
            ).joinToString(" ").ifBlank { "unknown" },
        )
        metaRow("Device", listOfNotNull(device.manufacturer, device.model).joinToString(" ").ifBlank { "unknown" })
        metaRow(
            "Android",
            device.androidVersion?.let { "$it (API ${device.sdkInt})" } ?: "unknown",
        )
        device.locale?.let { metaRow("Locale", it) }
        device.networkTypeAtStart?.let { metaRow("Network at start", it) }
        linkSpeedLabel(device)?.let { metaRow("Link speed (OS estimate)", it) }
        report.throughputBytesPerSecond?.let {
            metaRow("Measured download", "${LatencyFormat.bytes(it.toLong())}/s")
        }
        append("</div>\n")

        append("<div class=\"controls\" style=\"margin-top:16px\">")
        append("<button id=\"theme-toggle\" type=\"button\">Toggle theme</button>")
        append("</div>\n")

        if (session.isTruncated) {
            append("<div class=\"banner\"><b>Partial data.</b> ")
            append(session.droppedCount)
            append(" earlier call(s) were dropped because the in-memory buffer was full. ")
            append("Totals and percentiles below describe the ")
            append(session.records.size)
            append(" call(s) that were kept. Raise <code>maxRecords</code> to keep more.</div>\n")
        }
        append("</header>\n")
    }

    private fun StringBuilder.metaRow(label: String, value: String) {
        append("<div>").append(esc(label)).append(": <b>").append(esc(value)).append("</b></div>\n")
    }

    // ----------------------------------------------------------------- tiles

    private fun StringBuilder.appendTiles(report: LatencyReport) {
        val overall = report.overall
        append("<section>\n<h2>Summary</h2>\n")
        append("<p class=\"section-note\">Across every recorded call in this session.</p>\n")
        append("<div class=\"tiles\">\n")

        tile("Calls", overall.count.toString(), "${overall.successCount} succeeded")
        tile(
            "Failed",
            overall.errorCount.toString(),
            formatPercent(overall.errorRate) + " of all calls",
        )
        tile("Median (p50)", formatMs(overall.p50Ms), "half of calls were faster")
        tile("p95", formatMs(overall.p95Ms), "1 call in 20 was slower")
        tile("p99", formatMs(overall.p99Ms), "worst " + formatMs(overall.maxMs))
        tile(
            "Transferred",
            formatBytes(overall.requestBytes + overall.responseBytes),
            formatBytes(overall.requestBytes) + " up / " + formatBytes(overall.responseBytes) + " down",
        )
        report.heaviestRoute?.let { route ->
            tile(
                "Most time spent",
                formatMs(route.stats.totalMs),
                route.key + " (" + route.stats.count + " calls)",
            )
        }
        report.slowestRoute?.let { route ->
            tile("Slowest route (p95)", formatMs(route.stats.p95Ms), route.key)
        }

        append("</div>\n</section>\n")
    }

    private fun StringBuilder.tile(label: String, value: String, hint: String) {
        append("<div class=\"tile\"><div class=\"label\">").append(esc(label)).append("</div>")
        append("<div class=\"value\">").append(esc(value)).append("</div>")
        append("<div class=\"hint\">").append(esc(hint)).append("</div></div>\n")
    }

    // ---------------------------------------------------------------- routes

    private fun StringBuilder.appendRoutes(report: LatencyReport) {
        append("<section>\n<h2>Routes</h2>\n")
        append("<p class=\"section-note\">Grouped by normalized route and method, slowest total time first. ")
        append("Click a column to sort.</p>\n")

        if (report.routes.isEmpty()) {
            append("<p class=\"empty\">No calls were recorded in this session.</p>\n</section>\n")
            return
        }

        append("<div class=\"controls\">")
        append("<input type=\"search\" data-filter-for=\"routes\" placeholder=\"Filter routes, methods or hosts\" aria-label=\"Filter routes\">")
        append("<label><input type=\"checkbox\" data-errors-only-for=\"routes\"> Only routes with errors</label>")
        append("<span class=\"pill\" data-count-for=\"routes\"></span>")
        append("</div>\n")

        append("<div class=\"table-scroll\"><table data-sortable>\n<thead><tr>")
        append("<th class=\"left\" data-key=\"route\">Route</th>")
        append("<th data-key=\"count\">Calls</th>")
        append("<th data-key=\"errors\">Err %</th>")
        append("<th data-key=\"avg\">Avg</th>")
        append("<th data-key=\"min\">Min</th>")
        append("<th data-key=\"p50\">p50</th>")
        append("<th data-key=\"p90\">p90</th>")
        append("<th data-key=\"p95\">p95</th>")
        append("<th data-key=\"p99\">p99</th>")
        append("<th data-key=\"max\">Max</th>")
        append("<th data-key=\"total\">Total</th>")
        append("<th data-key=\"share\">Share</th>")
        append("<th data-key=\"bytes\">Bytes</th>")
        append("</tr></thead>\n<tbody>\n")

        report.routes.forEach { route -> appendRouteRow(report, route) }
        append("</tbody>\n</table></div>\n</section>\n")
    }

    private fun StringBuilder.appendRouteRow(report: LatencyReport, route: RouteStats) {
        val stats = route.stats
        val search = route.method + " " + route.route + " " + route.host
        append("<tr data-scope=\"routes\" data-search=\"").append(esc(search))
        append("\" data-error=\"").append(if (stats.errorCount > 0) "1" else "0").append("\">")

        append("<td class=\"left route-cell\" data-key=\"route\" data-value=\"")
            .append(esc(route.route)).append("\">")
        append("<span class=\"pill method\">").append(esc(route.method)).append("</span> ")
        append("<span title=\"").append(esc(route.host)).append("\">").append(esc(route.route))
        append("</span></td>")

        numCell("count", stats.count.toLong(), stats.count.toString())
        val errClass = if (stats.errorCount > 0) " err-rate-bad" else ""
        append("<td class=\"num").append(errClass).append("\" data-key=\"errors\" data-value=\"")
            .append(stats.errorRate).append("\">").append(formatPercent(stats.errorRate)).append("</td>")

        latencyCell(report, "avg", stats.avgMs.toLong())
        latencyCell(report, "min", stats.minMs)
        latencyCell(report, "p50", stats.p50Ms)
        latencyCell(report, "p90", stats.p90Ms)
        latencyCell(report, "p95", stats.p95Ms)
        latencyCell(report, "p99", stats.p99Ms)
        latencyCell(report, "max", stats.maxMs)
        numCell("total", stats.totalMs, formatMs(stats.totalMs))

        val share = route.timeShare(report.overall.totalMs)
        append("<td class=\"num\" data-key=\"share\" data-value=\"").append(share).append("\">")
            .append(formatPercent(share)).append("</td>")

        val bytes = stats.requestBytes + stats.responseBytes
        numCell("bytes", bytes, formatBytes(bytes))
        append("</tr>\n")
    }

    private fun StringBuilder.numCell(key: String, value: Long, text: String) {
        append("<td class=\"num\" data-key=\"").append(key).append("\" data-value=\"")
            .append(value).append("\">").append(esc(text)).append("</td>")
    }

    /**
     * Latency cells carry a status colour *and* the number, plus a glyph on the
     * slow band — colour alone would fail for a colour-blind or printed reader.
     */
    private fun StringBuilder.latencyCell(report: LatencyReport, key: String, ms: Long) {
        append("<td class=\"num lat ").append(latencyClass(report, ms))
        append("\" data-key=\"").append(key).append("\" data-value=\"").append(ms).append("\">")
        append(esc(formatMs(ms))).append("</td>")
    }

    private fun latencyClass(report: LatencyReport, ms: Long): String =
        when (LatencyFormat.band(report, ms)) {
            LatencyBand.SLOW -> "lat-slow"
            LatencyBand.WARN -> "lat-warn"
            LatencyBand.FAST -> "lat-good"
        }

    // ----------------------------------------------------------- phase bars

    private fun StringBuilder.appendPhases(report: LatencyReport) {
        append("<section>\n<h2>Where the time went</h2>\n")

        if (!report.hasPhaseData) {
            append("<p class=\"empty\">No phase breakdown available. ")
            append("Only the OkHttp integration can split a call into connection phases; ")
            append("Ktor, Volley and HttpURLConnection report coarser timings.</p>\n</section>\n")
            return
        }

        append("<p class=\"section-note\">Average time per phase for each route, from calls that reported a breakdown. ")
        append("Segments are drawn to scale against the slowest route.</p>\n")

        append("<ul class=\"legend\">\n")
        PhaseBreakdown.of(Phases.EMPTY).forEach { slice ->
            append("<li tabindex=\"0\" data-tip=\"<b>").append(escForTip(slice.label)).append("</b><br>")
                .append(escForTip(slice.description)).append("\">")
            append("<i class=\"swatch\" style=\"background:var(").append(colorVar(slice.slot)).append(")\"></i>")
            append(esc(slice.label)).append("</li>\n")
        }
        append("</ul>\n")

        val rows = report.routes
            .filter { route -> !route.stats.phaseAverages.isEmpty }
            .sortedByDescending { PhaseBreakdown.total(it.stats.phaseAverages) }
        val scale = rows.maxOfOrNull { PhaseBreakdown.total(it.stats.phaseAverages) } ?: 0L

        append("<div class=\"table-scroll\">\n")
        rows.forEach { route -> appendPhaseRow(route, scale) }
        append("</div>\n</section>\n")
    }

    private fun StringBuilder.appendPhaseRow(route: RouteStats, scale: Long) {
        val slices = PhaseBreakdown.nonEmpty(route.stats.phaseAverages)
        val accounted = slices.sumOf { it.ms }
        if (accounted <= 0L) return

        append("<div class=\"phase-row\">\n")
        append("<div class=\"phase-head\"><span class=\"name\">").append(esc(route.key)).append("</span>")
        append("<span class=\"total\">").append(esc(formatMs(accounted))).append(" avg &middot; ")
            .append(route.stats.count).append(" calls</span></div>\n")

        // Each route's bar is a share of the widest route, so rows are
        // comparable to each other rather than each filling its own width.
        val widthPercent = if (scale <= 0) 0.0 else accounted.toDouble() / scale * 100.0
        append("<div class=\"stack\" style=\"width:").append(percent(widthPercent)).append("\">")
        slices.forEach { slice ->
            append("<span style=\"width:").append(percent(slice.share(accounted) * 100.0))
            append(";background:var(").append(colorVar(slice.slot)).append(")\" data-tip=\"<b>")
            append(escForTip(slice.label)).append("</b> ").append(escForTip(formatMs(slice.ms)))
            append(" &middot; ").append(escForTip(formatPercent(slice.share(accounted))))
            append(" of this route<br>").append(escForTip(slice.description)).append("\"></span>")
        }
        append("</div>\n")

        // Direct labels: three of the five hues sit below 3:1 contrast on the
        // light surface, so the numbers are always on the page, never colour-only.
        append("<div class=\"phase-values\">")
        slices.forEach { slice ->
            append("<span><i style=\"background:var(").append(colorVar(slice.slot)).append(")\"></i>")
            append(esc(slice.label)).append(' ').append(esc(formatMs(slice.ms))).append("</span>")
        }
        append("</div>\n</div>\n")
    }

    private fun colorVar(slot: Int): String = PHASE_COLOR_VARS[slot % PHASE_COLOR_VARS.size]

    // -------------------------------------------------------------- timeline

    private fun StringBuilder.appendTimeline(report: LatencyReport) {
        val records = report.session.records
        append("<section>\n<h2>Timeline</h2>\n")
        if (records.isEmpty()) {
            append("<p class=\"empty\">Nothing to plot.</p>\n</section>\n")
            return
        }

        val span = maxOf(
            report.session.durationMs,
            records.maxOf { it.endedAtElapsedMs }.coerceAtLeast(1),
        )
        val shown = records.take(MAX_WATERFALL_ROWS)

        append("<p class=\"section-note\">Every call placed on the session clock — ")
        append("bursts, serial chains and overlap are visible here in a way an average hides.</p>\n")
        append("<div class=\"waterfall\">\n<div class=\"axis\">")
        for (step in 0..AXIS_STEPS) {
            val at = span * step / AXIS_STEPS
            append("<span style=\"left:").append(percent(step * 100.0 / AXIS_STEPS)).append("\">")
                .append(esc(formatMs(at))).append("</span>")
        }
        append("</div>\n<div class=\"wf-track\">\n")

        report.session.markers.forEach { marker ->
            val left = (marker.atElapsedMs.coerceIn(0, span)).toDouble() / span * 100.0
            append("<div class=\"wf-marker\" style=\"left:").append(percent(left)).append("\" data-tip=\"<b>")
                .append(escForTip(marker.label)).append("</b><br>at ").append(escForTip(formatMs(marker.atElapsedMs)))
                .append("\"><b>").append(esc(marker.label)).append("</b></div>\n")
        }

        shown.forEach { record ->
            // A call already in flight when recording started has a negative
            // offset; clamp it to the axis rather than dropping the row.
            val start = record.startedAtElapsedMs.coerceAtLeast(0)
            val left = start.toDouble() / span * 100.0
            val width = (record.totalMs.toDouble() / span * 100.0).coerceAtLeast(MIN_BAR_PERCENT)
            append("<div class=\"wf-row\"><span class=\"wf-bar")
            if (record.isError) append(" err")
            append("\" style=\"left:").append(percent(left)).append(";width:")
                .append(percent(width.coerceAtMost(100.0 - left))).append("\" data-tip=\"")
            append(callTooltip(record)).append("\"></span></div>\n")
        }

        append("</div>\n<p class=\"wf-legend\">")
        append("Bars are calls; red bars failed. ")
        if (records.size > shown.size) {
            append("Showing the first ").append(shown.size).append(" of ").append(records.size)
                .append(" calls — the full set is in the CSV and JSON exports. ")
        }
        if (report.session.markers.isNotEmpty()) {
            append("Vertical lines are markers you dropped with <code>mark()</code>.")
        }
        append("</p>\n</div>\n</section>\n")
    }

    private fun callTooltip(record: ApiCallRecord): String {
        val status = record.statusCode?.toString() ?: (record.error ?: "failed")
        return "<b>" + escForTip(record.method + " " + record.route) + "</b><br>" +
            escForTip(formatMs(record.totalMs)) + " &middot; " + escForTip(status) +
            "<br>starts at " + escForTip(formatMs(record.startedAtElapsedMs))
    }

    // --------------------------------------------------------- distributions

    private fun StringBuilder.appendDistributions(report: LatencyReport) {
        val byRoute = report.session.records.groupBy { it.method + " " + it.route }
        val candidates = report.routes
            .filter { (byRoute[it.key]?.size ?: 0) >= MIN_HISTOGRAM_CALLS }
            .take(MAX_HISTOGRAM_ROUTES)

        append("<section>\n<h2>Latency distribution</h2>\n")
        if (candidates.isEmpty()) {
            append("<p class=\"empty\">Needs at least ").append(MIN_HISTOGRAM_CALLS)
                .append(" calls on a route before a distribution says anything.</p>\n</section>\n")
            return
        }

        append("<p class=\"section-note\">How a route's calls spread out. ")
        append("A long right tail means the average is hiding your slow calls.</p>\n")

        candidates.forEach { route ->
            val durations = byRoute[route.key].orEmpty().map { it.totalMs }.sorted()
            append("<details><summary>").append(esc(route.key))
            append("<span class=\"muted\">").append(durations.size).append(" calls &middot; p50 ")
                .append(esc(formatMs(route.stats.p50Ms))).append(" &middot; p95 ")
                .append(esc(formatMs(route.stats.p95Ms))).append("</span></summary>\n<div>")
            appendHistogram(durations)
            append("</div></details>\n")
        }
        append("</section>\n")
    }

    private fun StringBuilder.appendHistogram(sortedDurations: List<Long>) {
        val min = sortedDurations.first()
        val max = sortedDurations.last()
        val bucketWidth = ((max - min).toDouble() / HISTOGRAM_BUCKETS).coerceAtLeast(1.0)
        val counts = IntArray(HISTOGRAM_BUCKETS)
        sortedDurations.forEach { duration ->
            val index = (((duration - min) / bucketWidth).toInt()).coerceIn(0, HISTOGRAM_BUCKETS - 1)
            counts[index]++
        }
        val peak = counts.max().coerceAtLeast(1)

        append("<div class=\"hist\">")
        counts.forEachIndexed { index, count ->
            val from = min + (bucketWidth * index).toLong()
            val to = min + (bucketWidth * (index + 1)).toLong()
            append("<span style=\"height:").append(percent(count * 100.0 / peak))
                .append("\" data-tip=\"<b>").append(count).append(" call(s)</b><br>")
                .append(escForTip(formatMs(from))).append(" to ").append(escForTip(formatMs(to)))
                .append("\"></span>")
        }
        append("</div>\n<div class=\"hist-axis\"><span>").append(esc(formatMs(min)))
            .append("</span><span>").append(esc(formatMs(max))).append("</span></div>\n")
    }

    // ---------------------------------------------------------------- errors

    private fun StringBuilder.appendErrors(report: LatencyReport) {
        append("<section>\n<h2>Failures</h2>\n")
        if (report.errors.isEmpty()) {
            append("<p class=\"empty\">No failed calls in this session.</p>\n</section>\n")
            return
        }
        append("<p class=\"section-note\">Grouped by HTTP status or exception type.</p>\n")
        append("<div class=\"table-scroll\"><table data-sortable>\n<thead><tr>")
        append("<th class=\"left\" data-key=\"what\">What</th><th data-key=\"count\">Calls</th>")
        append("<th class=\"left\" data-key=\"routes\">Routes</th><th class=\"left\">Example</th>")
        append("</tr></thead>\n<tbody>\n")
        report.errors.forEach { group ->
            append("<tr><td class=\"left\" data-key=\"what\" data-value=\"").append(esc(group.key))
                .append("\">").append(esc(group.key)).append("</td>")
            append("<td class=\"num\" data-key=\"count\" data-value=\"").append(group.count)
                .append("\">").append(group.count).append("</td>")
            append("<td class=\"left\" data-key=\"routes\">")
                .append(esc(group.routes.joinToString(", "))).append("</td>")
            append("<td class=\"left\">").append(esc(group.sampleMessage ?: "—")).append("</td></tr>\n")
        }
        append("</tbody></table></div>\n</section>\n")
    }

    /**
     * The same calls, split by the transport they travelled over.
     *
     * The section that answers "is it the app or is it the network": a route
     * can be healthy on wifi and unusable on cellular, and a single blended
     * p95 conceals both halves of that.
     */
    private fun StringBuilder.appendNetworks(report: LatencyReport) {
        if (report.networks.isEmpty()) return

        append("<section>\n<h2>By network</h2>\n")
        append("<p class=\"section-note\">")
        append("Only calls whose transport could be read are counted here; this ")
        append("needs the app to hold ACCESS_NETWORK_STATE, which LatencyScope ")
        append("never requests on your behalf.")
        append("</p>\n")
        append("<div class=\"table-scroll\"><table data-sortable>\n<thead><tr>")
        append("<th class=\"left\" data-key=\"network\">Network</th>")
        append("<th data-key=\"count\">Calls</th><th data-key=\"errors\">Errors</th>")
        append("<th data-key=\"p50\">p50</th><th data-key=\"p95\">p95</th>")
        append("<th data-key=\"max\">Max</th><th data-key=\"bytes\">Downloaded</th>")
        append("</tr></thead>\n<tbody>\n")

        report.networks.forEach { network ->
            val stats = network.stats
            append("<tr><td class=\"left\" data-key=\"network\" data-value=\"")
                .append(esc(network.networkType)).append("\">")
                .append(esc(network.networkType)).append("</td>")
            numCell("count", stats.count.toLong(), stats.count.toString())
            numCell("errors", stats.errorCount.toLong(), stats.errorCount.toString())
            latencyCell(report, "p50", stats.p50Ms)
            latencyCell(report, "p95", stats.p95Ms)
            latencyCell(report, "max", stats.maxMs)
            numCell("bytes", stats.responseBytes, formatBytes(stats.responseBytes))
            append("</tr>\n")
        }
        append("</tbody></table></div>\n</section>\n")
    }

    // -------------------------------------------------------------- call log

    private fun StringBuilder.appendCallLog(report: LatencyReport) {
        val records = report.session.records
        append("<section>\n<h2>Call log</h2>\n")
        if (records.isEmpty()) {
            append("<p class=\"empty\">Nothing recorded.</p>\n</section>\n")
            return
        }

        append("<p class=\"section-note\">Every call, newest timing detail inside. ")
        append("URLs are redacted as configured at record time.</p>\n")
        append("<div class=\"controls\">")
        append("<input type=\"search\" data-filter-for=\"calls\" placeholder=\"Filter by URL, route, status or error\" aria-label=\"Filter calls\">")
        append("<label><input type=\"checkbox\" data-errors-only-for=\"calls\"> Only failures</label>")
        append("<button id=\"expand-all\" type=\"button\" data-open=\"0\">Expand all</button>")
        append("<span class=\"pill\" data-count-for=\"calls\"></span>")
        append("</div>\n")

        records.take(MAX_CALL_ROWS).forEach { record -> appendCall(report, record) }

        if (records.size > MAX_CALL_ROWS) {
            append("<p class=\"section-note\">Showing the first ").append(MAX_CALL_ROWS)
                .append(" of ").append(records.size)
                .append(" calls to keep this file small. The CSV export has them all.</p>\n")
        }
        append("</section>\n")
    }

    private fun StringBuilder.appendCall(report: LatencyReport, record: ApiCallRecord) {
        val status = record.statusCode?.toString() ?: "failed"
        val search = record.method + " " + record.url + " " + status + " " + (record.error ?: "")
        append("<details class=\"call\" data-scope=\"calls\" data-search=\"").append(esc(search))
            .append("\" data-error=\"").append(if (record.isError) "1" else "0").append("\">")
        append("<summary><span><span class=\"pill method\">").append(esc(record.method))
            .append("</span> ").append(esc(record.route)).append("</span>")
        append("<span class=\"muted\"><span class=\"lat ").append(latencyClass(report, record.totalMs))
            .append("\">").append(esc(formatMs(record.totalMs))).append("</span> &middot; ")
            .append(esc(status)).append("</span></summary>\n<div>\n<dl class=\"kv\">\n")

        kv("URL", record.url)
        kv("Source", record.source.displayName)
        kv("Started at", formatMs(record.startedAtElapsedMs) + " into the session")
        kv("Total", formatMs(record.totalMs))
        record.protocol?.let { kv("Protocol", it) }
        kv("Request size", formatBytesOrUnknown(record.requestBytes))
        kv("Response size", formatBytesOrUnknown(record.responseBytes))
        if (record.retryCount > 0) kv("Extra exchanges", record.retryCount.toString())
        if (record.fromCache) kv("Cache", "served from cache")
        record.networkType?.let { kv("Network", it) }
        record.throughputBytesPerSecond?.let {
            kv("Download speed", "${LatencyFormat.bytes(it.toLong())}/s")
        }
        record.threadName?.let { kv("Thread", it) }
        record.error?.let { kv("Error", it) }

        record.phases.asList().forEach { (label, value) ->
            if (value != null) kv(label, formatMs(value))
        }
        append("</dl>\n</div></details>\n")
    }

    private fun StringBuilder.kv(key: String, value: String) {
        append("<dt>").append(esc(key)).append("</dt><dd>").append(esc(value)).append("</dd>\n")
    }

    // ---------------------------------------------------------------- footer

    private fun StringBuilder.appendFooter(report: LatencyReport) {
        append("<footer class=\"page\">\n")
        append("<h3>How to read this</h3>\n")
        append("<p><b>Percentiles</b> are exact and use nearest rank: p95 is a duration some ")
        append("call actually took, not an interpolated value no request ever saw.</p>\n")
        append("<p><b>Server</b> is the gap between the request being fully sent and the first ")
        append("response byte arriving. It is the closest a client can get to backend time, and ")
        append("it excludes connection setup and download.</p>\n")
        append("<p><b>Absent phases are blank, never zero.</b> A reused connection has no DNS or ")
        append("TLS phase at all, which is a different fact from one that took no time.</p>\n")
        append("<p><b>Routes are normalized</b> — <code>/users/42</code> and <code>/users/7</code> ")
        append("are counted together as <code>/users/{id}</code>.</p>\n")
        append("<p>Thresholds: under ").append(esc(formatMs(report.warnThresholdMs)))
            .append(" reads as fast, over ").append(esc(formatMs(report.slowThresholdMs)))
            .append(" as slow.</p>\n")
        append("<p>Generated ").append(esc(formatTimestamp(report.generatedAtEpochMs)))
            .append(" by LatencyScope ").append(esc(report.session.device.libraryVersion))
            .append(". Session id ").append(esc(report.session.id)).append(".</p>\n")
        append("</footer>\n")
    }

    // ----------------------------------------------------------------- utils

    private fun reportTitle(report: LatencyReport): String =
        "Latency report — " + report.session.name

    internal fun esc(value: String): String {
        val needsEscaping = value.any { it == '&' || it == '<' || it == '>' || it == '"' || it == '\'' }
        if (!needsEscaping) return value
        val out = StringBuilder(value.length + ESCAPE_HEADROOM)
        value.forEach { char ->
            when (char) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&#39;")
                else -> out.append(char)
            }
        }
        return out.toString()
    }

    /**
     * Escaping for text that ends up inside a `data-tip` attribute.
     *
     * A tooltip's value is read back with `getAttribute` and assigned to
     * `innerHTML`, so it is decoded **twice**: once by the HTML attribute
     * parser, once by the HTML parser inside `innerHTML`. Escaping only once
     * means `&lt;img onerror=…&gt;` survives the first decode as a live tag and
     * executes on the second — a recorded URL, route or [mark] label could run
     * script in whoever opened the report. Escaping twice makes the markup
     * survive both decodes as text.
     */
    /**
     * The OS link-speed estimate, or null when it reported nothing.
     *
     * Labelled an estimate wherever it appears: it describes what the radio
     * is nominally capable of, not what any call achieved, and it is often
     * wrong by an order of magnitude.
     */
    private fun linkSpeedLabel(device: DeviceInfo): String? {
        val down = device.linkDownstreamKbps
        val up = device.linkUpstreamKbps
        if (down == null && up == null) return null
        val parts = listOfNotNull(
            down?.let { "↓ ${formatKbps(it)}" },
            up?.let { "↑ ${formatKbps(it)}" },
        )
        return parts.joinToString("  ")
    }

    private const val KBPS_PER_MBPS = 1_000

    private fun formatKbps(kbps: Int): String =
        if (kbps >= KBPS_PER_MBPS) {
            "%.1f Mbps".format(Locale.US, kbps.toDouble() / KBPS_PER_MBPS)
        } else {
            "$kbps kbps"
        }

    internal fun escForTip(value: String): String = esc(esc(value))

    internal fun formatMs(ms: Long): String = LatencyFormat.duration(ms)

    internal fun formatBytes(bytes: Long): String = LatencyFormat.bytes(bytes)

    private fun formatBytesOrUnknown(bytes: Long): String = LatencyFormat.bytesOrUnknown(bytes)

    internal fun formatPercent(ratio: Double): String = LatencyFormat.percent(ratio)

    private fun percent(value: Double): String = trimZero(value.coerceIn(0.0, 100.0)) + "%"

    private fun trimZero(value: Double): String {
        val rounded = Math.round(value * 10) / 10.0
        return if (rounded == Math.floor(rounded)) rounded.toLong().toString() else rounded.toString()
    }

    private fun formatTimestamp(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(epochMs))

    private fun formatRange(startEpochMs: Long, endEpochMs: Long?): String {
        val start = formatTimestamp(startEpochMs)
        val end = endEpochMs?.let { SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(it)) }
        return if (end == null) "$start (still recording)" else "$start to $end"
    }

    private const val INITIAL_CAPACITY = 64 * 1024
    private const val AXIS_STEPS = 4
    private const val MIN_BAR_PERCENT = 0.4
    private const val MIN_HISTOGRAM_CALLS = 5
    private const val ESCAPE_HEADROOM = 16
}
