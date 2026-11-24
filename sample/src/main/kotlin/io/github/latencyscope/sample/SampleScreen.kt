@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.latencyscope.sample

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
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

    Scaffold(
        // safeDrawing rather than the default systemBars: it also clears the
        // display cutout, so the title is not drawn under a camera hole.
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { SampleTopBar(controller) },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            PrimaryScrollableTabRow(
                selectedTabIndex = controller.tab.ordinal,
                edgePadding = 12.dp,
            ) {
                SampleTab.entries.forEach { tab ->
                    Tab(
                        selected = tab == controller.tab,
                        onClick = { controller.selectTab(tab) },
                        text = {
                            Text(
                                text = tab.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }

            // A thin bar rather than a blocking spinner: a scenario can take
            // ten seconds, and the rest of the screen stays readable.
            if (controller.running != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                HorizontalDivider()
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { SectionHeader("Traffic · ${controller.tab.label}") }

                items(Scenario.entries) { scenario ->
                    ScenarioCard(
                        scenario = scenario,
                        enabled = controller.running == null,
                        inFlight = controller.running == scenario,
                        onRun = { scope.launch { controller.run(scenario) } },
                    )
                }

                item { SectionHeader("Flow markers") }
                item { MarkRow(controller, context) }

                item { SectionHeader("Report") }
                item { ReportRow(controller, context) }

                item {
                    SectionHeader(
                        if (controller.log.isEmpty()) {
                            "Call log"
                        } else {
                            "Call log · ${controller.log.size}"
                        },
                    )
                }

                if (controller.log.isEmpty()) {
                    item {
                        Text(
                            text = "Nothing yet. Run a scenario above — the log fills in as each " +
                                "call finishes, newest first.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(controller.log) { line -> LogRow(line) }
                }
            }
        }
    }
}

@Composable
private fun SampleTopBar(controller: SampleController) {
    TopAppBar(
        title = {
            Column {
                Text("LatencyScope sample", maxLines = 1, overflow = TextOverflow.Ellipsis)
                RecordingStatus(controller)
            }
        },
        actions = {
            Button(
                onClick = controller::toggleRecording,
                modifier = Modifier.padding(end = 8.dp),
            ) {
                Text(if (controller.recording) "Stop" else "Start")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    )
}

@Composable
private fun RecordingStatus(controller: SampleController) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // A dot as well as the word: the state has to survive a greyscale
        // screenshot, which is the same rule the report itself follows.
        Surface(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape),
            color = if (controller.recording) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline
            },
            content = {},
        )
        Text(
            text = if (controller.recording) "  Recording" else "  Not recording",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun ScenarioCard(
    scenario: Scenario,
    enabled: Boolean,
    inFlight: Boolean,
    onRun: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(scenario.label, style = MaterialTheme.typography.titleSmall)
            Text(
                text = scenario.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(
                onClick = onRun,
                enabled = enabled,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                if (inFlight) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text("  Running…")
                } else {
                    Text("Run")
                }
            }
        }
    }
}

/**
 * Markers are what make a report readable: without them it is a list of calls
 * with no indication of which screen caused which burst.
 */
@Composable
private fun MarkRow(controller: SampleController, context: Context) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Drop a landmark on the timeline, the way you would when moving " +
                "between screens.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FLOW_MARKS.forEach { label ->
            OutlinedButton(
                onClick = {
                    controller.mark(label)
                    context.toast(
                        if (controller.recording) {
                            "Marked “$label”"
                        } else {
                            "Not recording — press Start first"
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun ReportRow(controller: SampleController, context: Context) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Both screens show the same report — one drawn in Compose, one in Views.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { openReport(context, compose = true) },
                modifier = Modifier.weight(1f),
            ) {
                Text("Compose UI")
            }
            OutlinedButton(
                onClick = { openReport(context, compose = false) },
                modifier = Modifier.weight(1f),
            ) {
                Text("View UI")
            }
        }
        Button(
            onClick = {
                val report = LatencyScope.report()
                if (report == null) {
                    context.toast("No report yet — press Start, run a scenario, then Stop.")
                } else {
                    // Async: writing four files is disk I/O, and doing it on the
                    // main thread is exactly the stall the library warns about.
                    LatencyExport.shareAsync(context, report) { failure ->
                        context.toast("Export failed: ${failure.message}")
                    }
                }
            },
            enabled = controller.running == null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Export & share")
        }
    }
}

@Composable
private fun LogRow(line: LogLine) {
    val accent = if (line.isError) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "${line.tab.label} · ${line.scenario.label}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = line.path,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            // The glyph carries the outcome too, so a greyscale screenshot
            // still says which rows failed.
            text = if (line.isError) "! ${line.summary}" else line.summary,
            style = MaterialTheme.typography.bodyMedium,
            color = accent,
        )
        HorizontalDivider(color = Color.Transparent, thickness = 4.dp)
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
