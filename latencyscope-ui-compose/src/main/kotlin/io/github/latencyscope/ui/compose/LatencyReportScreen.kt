package io.github.latencyscope.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.latencyscope.core.ApiCallRecord
import io.github.latencyscope.core.ErrorGroup
import io.github.latencyscope.core.LatencyReport
import io.github.latencyscope.core.RouteStats
import io.github.latencyscope.export.LatencyBand
import io.github.latencyscope.export.LatencyFormat
import io.github.latencyscope.export.PhaseBreakdown
import io.github.latencyscope.export.PhaseSlice

/** Which list the screen is showing. */
public enum class ReportTab(internal val label: String) {
    ROUTES("Routes"),
    CALLS("Calls"),
    FAILURES("Failures"),
}

/**
 * The in-app latency report.
 *
 * Drop it anywhere — a debug drawer, a dedicated screen, a bottom sheet:
 *
 * ```kotlin
 * val report = LatencyScope.report()
 * if (report != null) {
 *     LatencyReportScreen(report, onShare = { LatencyExport.shareAsync(context, report) })
 * }
 * ```
 *
 * It reads from an already-aggregated [LatencyReport] and holds no state of
 * its own beyond the filter and the selected tab, so it is equally happy
 * showing a finished session or a live one that is re-aggregated as you
 * browse.
 */
@Composable
public fun LatencyReportScreen(
    report: LatencyReport,
    modifier: Modifier = Modifier,
    onShare: (() -> Unit)? = null,
) {
    val palette = rememberLatencyPalette()
    var tab by remember { mutableStateOf(ReportTab.ROUTES) }
    var query by remember { mutableStateOf("") }

    Surface(modifier = modifier) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Header(report, onShare) }

            if (report.session.isTruncated) {
                item { TruncationNotice(report, palette) }
            }

            item { SummaryTiles(report) }

            item {
                TabRow(selected = tab, onSelect = { tab = it }, report = report)
            }

            item {
                FilterField(
                    value = query,
                    onValueChange = { query = it },
                    hint = when (tab) {
                        ReportTab.ROUTES -> "Filter routes"
                        ReportTab.CALLS -> "Filter calls"
                        ReportTab.FAILURES -> "Filter failures"
                    },
                )
            }

            when (tab) {
                ReportTab.ROUTES -> {
                    val routes = report.routes.filter { it.matches(query) }
                    if (routes.isEmpty()) {
                        item { EmptyState("No routes match this filter.") }
                    } else {
                        items(routes, key = { it.key + it.host }) { route ->
                            RouteCard(report, route, palette)
                        }
                    }
                }

                ReportTab.CALLS -> {
                    val calls = report.session.records.filter { it.matches(query) }
                    if (calls.isEmpty()) {
                        item { EmptyState("No calls match this filter.") }
                    } else {
                        items(calls, key = { it.id }) { call ->
                            CallCard(report, call, palette)
                        }
                    }
                }

                ReportTab.FAILURES -> {
                    val failures = report.errors.filter { it.matches(query) }
                    if (failures.isEmpty()) {
                        item { EmptyState("No failed calls in this session.") }
                    } else {
                        items(failures, key = { it.key }) { group -> FailureCard(group) }
                    }
                }
            }

            item { Methodology(report) }
        }
    }
}

// ---------------------------------------------------------------- sections

@Composable
private fun Header(report: LatencyReport, onShare: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(report.session.name, style = MaterialTheme.typography.titleLarge)
            Text(
                LatencyFormat.duration(report.session.durationMs) + " · " +
                    report.overall.count + " calls",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onShare != null) {
            Card(onClick = onShare, modifier = Modifier.semantics { contentDescription = "Share report" }) {
                Text("Share", Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun TruncationNotice(report: LatencyReport, palette: LatencyPalette) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp)) {
            Text("Partial data", style = MaterialTheme.typography.titleSmall, color = palette.warn)
            Text(
                report.session.droppedCount.toString() +
                    " earlier calls were dropped because the buffer was full. " +
                    "Everything below describes the " + report.overall.count + " that were kept.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SummaryTiles(report: LatencyReport) {
    val overall = report.overall
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile("p50", LatencyFormat.duration(overall.p50Ms), "half were faster", Modifier.weight(1f))
            Tile("p95", LatencyFormat.duration(overall.p95Ms), "1 in 20 slower", Modifier.weight(1f))
            Tile("p99", LatencyFormat.duration(overall.p99Ms), "worst " + LatencyFormat.duration(overall.maxMs), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile("Calls", overall.count.toString(), overall.successCount.toString() + " ok", Modifier.weight(1f))
            Tile("Failed", overall.errorCount.toString(), LatencyFormat.percent(overall.errorRate), Modifier.weight(1f))
            Tile(
                "Data",
                LatencyFormat.bytes(overall.requestBytes + overall.responseBytes),
                "up and down",
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Tile(label: String, value: String, hint: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
            Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun TabRow(selected: ReportTab, onSelect: (ReportTab) -> Unit, report: LatencyReport) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ReportTab.entries.forEach { tab ->
            val count = when (tab) {
                ReportTab.ROUTES -> report.routes.size
                ReportTab.CALLS -> report.session.records.size
                ReportTab.FAILURES -> report.errors.sumOf { it.count }
            }
            FilterChip(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                label = { Text(tab.label + " " + count) },
            )
        }
    }
}

@Composable
private fun FilterField(value: String, onValueChange: (String) -> Unit, hint: String) {
    Card {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (value.isEmpty()) {
                Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

// ------------------------------------------------------------------- cards

@Composable
private fun RouteCard(report: LatencyReport, route: RouteStats, palette: LatencyPalette) {
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MethodPill(route.method)
                Spacer(Modifier.width(8.dp))
                Text(
                    route.route,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                LatencyText(report, route.stats.p95Ms, palette)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                route.stats.count.toString() + " calls · total " +
                    LatencyFormat.duration(route.stats.totalMs) + " · " +
                    LatencyFormat.percent(route.stats.errorRate) + " failed",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (expanded) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))
                StatGrid(route.stats.p50Ms, route.stats.p90Ms, route.stats.p95Ms, route.stats.p99Ms, route.stats.maxMs)
                val slices = PhaseBreakdown.nonEmpty(route.stats.phaseAverages)
                if (slices.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Average time per phase", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    PhaseBar(slices, palette)
                } else {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "No phase breakdown — " + route.sources.joinToString { it.displayName } +
                            " reports total time only.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CallCard(report: LatencyReport, call: ApiCallRecord, palette: LatencyPalette) {
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MethodPill(call.method)
                Spacer(Modifier.width(8.dp))
                Text(
                    call.route,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                LatencyText(report, call.totalMs, palette)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                (call.statusCode?.toString() ?: (call.error ?: "failed")) + " · " +
                    call.source.displayName + " · at " +
                    LatencyFormat.duration(call.startedAtElapsedMs),
                style = MaterialTheme.typography.labelMedium,
                color = if (call.isError) palette.slow else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (expanded) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))
                DetailRow("URL", call.url)
                DetailRow("Request", LatencyFormat.bytesOrUnknown(call.requestBytes))
                DetailRow("Response", LatencyFormat.bytesOrUnknown(call.responseBytes))
                call.protocol?.let { DetailRow("Protocol", it) }
                if (call.retryCount > 0) DetailRow("Extra exchanges", call.retryCount.toString())
                if (call.fromCache) DetailRow("Cache", "served from cache")
                call.networkType?.let { DetailRow("Network", it) }
                call.error?.let { DetailRow("Error", it) }
                call.phases.asList().forEach { (label, value) ->
                    if (value != null) DetailRow(label, LatencyFormat.duration(value))
                }
                val slices = PhaseBreakdown.nonEmpty(call.phases)
                if (slices.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    PhaseBar(slices, palette)
                }
            }
        }
    }
}

@Composable
private fun FailureCard(group: ErrorGroup) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row {
                Text(group.key, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(group.count.toString() + " calls", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                group.routes.joinToString(", "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            group.sampleMessage?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// -------------------------------------------------------------- components

/**
 * The stacked phase bar.
 *
 * Segments are separated by a surface-coloured gap so adjacent fills never
 * touch, and every segment is labelled with its name and duration underneath:
 * three of the five hues sit below 3:1 contrast on a light surface, so the
 * numbers must be on screen rather than implied by colour.
 */
@Composable
private fun PhaseBar(slices: List<PhaseSlice>, palette: LatencyPalette) {
    val total = slices.sumOf { it.ms }
    if (total <= 0) return

    Row(
        Modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(palette.track),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        slices.forEach { slice ->
            Box(
                Modifier
                    .weight(slice.ms.toFloat())
                    .fillMaxWidth()
                    .height(14.dp)
                    .background(palette.forSlot(slice.slot))
                    .semantics {
                        contentDescription = slice.label + " " + LatencyFormat.duration(slice.ms)
                    },
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        slices.forEach { slice ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(palette.forSlot(slice.slot)),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    slice.label,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    LatencyFormat.duration(slice.ms) + "  " + LatencyFormat.percent(slice.share(total)),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Latency carries its band as a word in the description, not only as colour. */
@Composable
private fun LatencyText(report: LatencyReport, ms: Long, palette: LatencyPalette) {
    val band = LatencyFormat.band(report, ms)
    val text = LatencyFormat.duration(ms)
    Text(
        text = if (band == LatencyBand.SLOW) "! $text" else text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (band == LatencyBand.SLOW) FontWeight.Bold else FontWeight.Normal,
        fontFamily = FontFamily.Monospace,
        color = palette.forBand(band),
        modifier = Modifier.semantics { contentDescription = "$text, ${band.label}" },
    )
}

@Composable
private fun MethodPill(method: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            method,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun StatGrid(p50: Long, p90: Long, p95: Long, p99: Long, max: Long) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("p50" to p50, "p90" to p90, "p95" to p95, "p99" to p99, "max" to max).forEach { (label, value) ->
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    LatencyFormat.duration(value),
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 1.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(130.dp),
        )
        Text(value, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun EmptyState(message: String) {
    Card(Modifier.fillMaxWidth()) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun Methodology(report: LatencyReport) {
    Column(Modifier.padding(top = 8.dp)) {
        HorizontalDivider(color = Color.Transparent)
        Text(
            "Percentiles are exact, nearest rank — p95 is a duration some call actually took. " +
                "Server is the gap between the request being fully sent and the first response " +
                "byte, the closest a client gets to backend time. Absent phases are omitted, " +
                "never shown as zero. " + LatencyFormat.scaleSummary(report),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --------------------------------------------------------------- filtering

private fun RouteStats.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val needle = query.trim().lowercase()
    return route.lowercase().contains(needle) ||
        method.lowercase().contains(needle) ||
        host.lowercase().contains(needle)
}

private fun ApiCallRecord.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val needle = query.trim().lowercase()
    return url.lowercase().contains(needle) ||
        route.lowercase().contains(needle) ||
        method.lowercase().contains(needle) ||
        statusCode?.toString()?.contains(needle) == true ||
        error?.lowercase()?.contains(needle) == true
}

private fun ErrorGroup.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val needle = query.trim().lowercase()
    return key.lowercase().contains(needle) ||
        routes.any { it.lowercase().contains(needle) } ||
        sampleMessage?.lowercase()?.contains(needle) == true
}
