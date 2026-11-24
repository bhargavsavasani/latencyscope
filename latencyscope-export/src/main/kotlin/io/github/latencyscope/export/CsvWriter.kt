package io.github.latencyscope.export

import io.github.latencyscope.core.LatencyReport

/**
 * RFC 4180 CSV for the two questions a spreadsheet gets asked: "which route is
 * slow" (summary) and "show me every call" (calls).
 */
internal object CsvWriter {

    private val SUMMARY_HEADER = listOf(
        "method", "route", "host", "sources", "count", "errors", "error_rate_pct",
        "avg_ms", "min_ms", "p50_ms", "p75_ms", "p90_ms", "p95_ms", "p99_ms", "max_ms",
        "total_ms", "time_share_pct", "request_bytes", "response_bytes",
        "avg_queue_ms", "avg_dns_ms", "avg_connect_ms", "avg_tls_ms",
        "avg_request_ms", "avg_server_ms", "avg_response_ms",
    )

    private val CALLS_HEADER = listOf(
        "id", "source", "method", "route", "host", "url", "status", "success",
        "start_ms", "total_ms", "queue_ms", "dns_ms", "connect_ms", "tls_ms",
        "request_headers_ms", "request_body_ms", "server_ms",
        "response_headers_ms", "response_body_ms",
        "request_bytes", "response_bytes", "protocol", "from_cache", "retry_count",
        "thread", "network_type", "error",
    )

    fun summary(report: LatencyReport): String = buildString {
        appendRow(SUMMARY_HEADER)
        report.routes.forEach { route ->
            val stats = route.stats
            val phases = stats.phaseAverages
            appendRow(
                listOf(
                    route.method,
                    route.route,
                    route.host,
                    route.sources.joinToString(" "),
                    stats.count,
                    stats.errorCount,
                    percent(stats.errorRate),
                    round(stats.avgMs),
                    stats.minMs,
                    stats.p50Ms,
                    stats.p75Ms,
                    stats.p90Ms,
                    stats.p95Ms,
                    stats.p99Ms,
                    stats.maxMs,
                    stats.totalMs,
                    percent(route.timeShare(report.overall.totalMs)),
                    stats.requestBytes,
                    stats.responseBytes,
                    phases.queueMs,
                    phases.dnsMs,
                    phases.connectMs,
                    phases.tlsMs,
                    sumOrNull(phases.requestHeadersMs, phases.requestBodyMs),
                    phases.serverMs,
                    sumOrNull(phases.responseHeadersMs, phases.responseBodyMs),
                ),
            )
        }
    }

    fun calls(report: LatencyReport): String = buildString {
        appendRow(CALLS_HEADER)
        report.session.records.forEach { record ->
            val phases = record.phases
            appendRow(
                listOf(
                    record.id,
                    record.source.displayName,
                    record.method,
                    record.route,
                    record.host,
                    record.url,
                    record.statusCode,
                    record.isSuccess,
                    record.startedAtElapsedMs,
                    record.totalMs,
                    phases.queueMs,
                    phases.dnsMs,
                    phases.connectMs,
                    phases.tlsMs,
                    phases.requestHeadersMs,
                    phases.requestBodyMs,
                    phases.serverMs,
                    phases.responseHeadersMs,
                    phases.responseBodyMs,
                    unknownAsBlank(record.requestBytes),
                    unknownAsBlank(record.responseBytes),
                    record.protocol,
                    record.fromCache,
                    record.retryCount,
                    record.threadName,
                    record.networkType,
                    record.error,
                ),
            )
        }
    }

    /** -1 means "this stack could not tell us"; a blank cell says that, 0 lies. */
    private fun unknownAsBlank(bytes: Long): Long? = if (bytes < 0) null else bytes

    private fun sumOrNull(first: Long?, second: Long?): Long? =
        if (first == null && second == null) null else (first ?: 0) + (second ?: 0)

    private fun percent(ratio: Double): String = round(ratio * 100)

    private fun round(value: Double): String = ((value * 100).toLong() / 100.0).toString()

    private fun StringBuilder.appendRow(cells: List<Any?>) {
        cells.forEachIndexed { index, cell ->
            if (index > 0) append(',')
            append(escape(cell))
        }
        // RFC 4180 specifies CRLF, which also keeps Excel on Windows happy.
        append("\r\n")
    }

    private fun escape(cell: Any?): String {
        val raw = cell?.toString() ?: return ""
        val text = defuseFormula(raw)
        val needsQuoting = text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuoting) return text
        return "\"" + text.replace("\"", "\"\"") + "\""
    }

    /**
     * Neutralises spreadsheet formula injection.
     *
     * These files hold recorded URLs and server error messages and are made to
     * be opened in Excel, Numbers or Sheets, where a cell beginning `=`, `+`,
     * `-` or `@` is executed as a formula rather than shown as text. A URL that
     * happens to start with one, or a hostile server reply, would otherwise run
     * on the reader's machine. A leading apostrophe is the conventional fix:
     * every spreadsheet reads the rest of the cell as literal text.
     *
     * A cell that is simply a negative number is left alone — quoting `-42`
     * would turn a figure the reader wants to average into text.
     */
    private fun defuseFormula(text: String): String = when {
        text.isEmpty() -> text
        text[0] !in FORMULA_TRIGGERS -> text
        text.toDoubleOrNull() != null -> text
        else -> "'$text"
    }

    private val FORMULA_TRIGGERS = charArrayOf('=', '+', '-', '@', '\t', '\r')
}
