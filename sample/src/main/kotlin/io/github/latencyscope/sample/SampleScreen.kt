package io.github.latencyscope.sample

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.latencyscope.core.LatencyScope
import io.github.latencyscope.export.LatencyExport
import io.github.latencyscope.ui.compose.LatencyReportActivity as ComposeReportActivity
import io.github.latencyscope.ui.view.LatencyReportActivity as ViewReportActivity
import kotlinx.coroutines.launch

/**
 * The whole sample: pick a stack, fire traffic at it, read the report.
 *
 * Nothing here branches on the build type. The same source compiles against
 * the real modules on debug and against `latencyscope-noop` on release; on
 * release the buttons still work, the calls still happen, and the report is
 * simply never there.
 */
@Composable
internal fun SampleScreen(controller: SampleController) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(Modifier.fillMaxSize()) {
        RecordingBar(controller)

        PrimaryScrollableTabRow(selectedTabIndex = controller.tab.ordinal, edgePadding = 8.dp) {
            SampleTab.entries.forEach { tab ->
                Tab(
                    selected = tab == controller.tab,
                    onClick = { controller.selectTab(tab) },
                    text = { Text(tab.label) },
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    text = "Traffic · ${controller.tab.label}",
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            items(Scenario.entries) { scenario ->
                ScenarioCard(
                    scenario = scenario,
                    enabled = controller.running == null,
                    inFlight = controller.running == scenario,
                    onRun = { scope.launch { controller.run(scenario) } },
                )
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { MarkRow(controller) }
            item { ReportRow(controller, context) }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }

            item {
                Text(
                    text = if (controller.log.isEmpty()) {
                        "No calls yet."
                    } else {
                        "Last ${controller.log.size} calls"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            items(controller.log) { line -> LogRow(line) }
        }
    }
}

@Composable
private fun RecordingBar(controller: SampleController) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text("LatencyScope sample", style = MaterialTheme.typography.titleLarge)
            Text(
                text = if (controller.recording) "Recording" else "Not recording",
                style = MaterialTheme.typography.bodySmall,
                color = if (controller.recording) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Button(onClick = controller::toggleRecording) {
            Text(if (controller.recording) "Stop" else "Start")
        }
    }
}

@Composable
private fun ScenarioCard(
    scenario: Scenario,
    enabled: Boolean,
    inFlight: Boolean,
    onRun: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(scenario.label, style = MaterialTheme.typography.titleSmall)
            Text(scenario.detail, style = MaterialTheme.typography.bodySmall)
            Button(onClick = onRun, enabled = enabled) {
                Text(if (inFlight) "Running…" else "Run")
            }
        }
    }
}

/**
 * Markers are what make a report readable: without them it is a list of calls
 * with no indication of which screen caused which burst.
 */
@Composable
private fun MarkRow(controller: SampleController) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Flow markers", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FLOW_MARKS.forEach { label ->
                OutlinedButton(onClick = { controller.mark(label) }) { Text(label) }
            }
        }
    }
}

@Composable
private fun ReportRow(controller: SampleController, context: Context) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Report", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { openReport(context, compose = true) }) { Text("Compose UI") }
            OutlinedButton(onClick = { openReport(context, compose = false) }) { Text("View UI") }
        }
        Button(
            onClick = {
                val report = LatencyScope.report()
                if (report == null) {
                    context.toast("No report yet — record a session first.")
                } else {
                    // Async: writing four files is disk I/O, and doing it on the
                    // main thread is exactly the stall the library warns about.
                    LatencyExport.shareAsync(context, report) { failure ->
                        context.toast("Export failed: ${failure.message}")
                    }
                }
            },
            enabled = controller.running == null,
        ) {
            Text("Export & share")
        }
    }
}

@Composable
private fun LogRow(line: LogLine) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "${line.tab.label} · ${line.scenario.label}",
            style = MaterialTheme.typography.labelMedium,
        )
        Text(text = line.path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        Text(
            text = line.summary,
            style = MaterialTheme.typography.bodyMedium,
            color = if (line.isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

private fun openReport(context: Context, compose: Boolean) {
    val intent = if (compose) {
        ComposeReportActivity.intent(context)
    } else {
        ViewReportActivity.intent(context)
    }
    context.startActivity(intent)
}

private fun Context.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

private val FLOW_MARKS = listOf("opened list", "opened detail", "checkout")
