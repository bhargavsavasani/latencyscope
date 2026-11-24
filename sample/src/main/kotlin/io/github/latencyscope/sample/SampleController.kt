package io.github.latencyscope.sample

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.latencyscope.core.LatencyScope
import io.github.latencyscope.sample.net.KtorSampleClient
import io.github.latencyscope.sample.net.OkHttpSampleClient
import io.github.latencyscope.sample.net.SampleClient
import io.github.latencyscope.sample.net.UrlConnectionSampleClient
import io.github.latencyscope.sample.net.VolleySampleClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * The screen's state and everything it can do.
 *
 * Deliberately not a ViewModel: the sample should show the LatencyScope API,
 * not an architecture opinion, and a plain object survives configuration
 * changes here because the Activity hands it to `remember` at the root.
 */
internal class SampleController(context: Context) {

    private val appContext = context.applicationContext

    var tab: SampleTab by mutableStateOf(SampleTab.OKHTTP)
        private set

    var recording: Boolean by mutableStateOf(LatencyScope.isRecording())
        private set

    /** The scenario currently in flight, if any — used to disable its button. */
    var running: Scenario? by mutableStateOf(null)
        private set

    val log: MutableList<LogLine> = mutableStateListOf()

    // Built on first use and kept: each client owns a connection pool, and
    // rebuilding one per run would make every call look like a cold start.
    private val clients = mutableMapOf<SampleTab, SampleClient>()

    fun selectTab(selected: SampleTab) {
        tab = selected
    }

    fun toggleRecording() {
        if (LatencyScope.isRecording()) {
            LatencyScope.stop()
        } else {
            log.clear()
            LatencyScope.start("sample · ${tab.label}")
        }
        recording = LatencyScope.isRecording()
    }

    fun mark(label: String) {
        LatencyScope.mark(label)
    }

    /** True when there is something for the report screens to show. */
    fun hasReport(): Boolean = LatencyScope.report() != null

    suspend fun run(scenario: Scenario) {
        if (running != null) return
        running = scenario
        try {
            val client = clientFor(tab)
            if (scenario.parallel) {
                // awaitAll, not a loop: the queue and connect contention this
                // scenario exists to show only appears if they overlap.
                coroutineScope {
                    scenario.urls
                        .map { url -> async { fire(client, scenario, url) } }
                        .awaitAll()
                }
            } else {
                scenario.urls.forEach { url -> fire(client, scenario, url) }
            }
        } finally {
            running = null
        }
    }

    private suspend fun fire(client: SampleClient, scenario: Scenario, url: String) {
        // elapsedRealtime, not currentTimeMillis: a clock correction mid-call
        // would otherwise produce a negative duration.
        val startedAt = SystemClock.elapsedRealtime()
        val result = client.get(url, scenario.timeoutMs)
        val line = LogLine(
            tab = client.tab,
            scenario = scenario,
            url = url,
            result = result,
            durationMs = SystemClock.elapsedRealtime() - startedAt,
        )
        log.add(0, line)
        if (log.size > MAX_LOG_LINES) log.removeAt(log.lastIndex)
    }

    private fun clientFor(tab: SampleTab): SampleClient = clients.getOrPut(tab) {
        when (tab) {
            SampleTab.OKHTTP -> OkHttpSampleClient()
            SampleTab.KTOR -> KtorSampleClient()
            SampleTab.VOLLEY -> VolleySampleClient(appContext)
            SampleTab.URL_CONNECTION -> UrlConnectionSampleClient()
        }
    }

    fun close() {
        clients.values.forEach { it.close() }
        clients.clear()
    }

    private companion object {
        /** The log is a debugging aid, not the report; it does not need history. */
        const val MAX_LOG_LINES = 200
    }
}
