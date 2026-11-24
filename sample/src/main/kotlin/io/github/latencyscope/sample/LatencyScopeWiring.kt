package io.github.latencyscope.sample

import android.content.Context
import com.android.volley.toolbox.HurlStack
import io.github.latencyscope.core.LatencyConfig
import io.github.latencyscope.core.LatencyScope
import io.github.latencyscope.export.LatencyExport
import io.github.latencyscope.ktor.LatencyScopeKtorPlugin
import io.github.latencyscope.okhttp.installLatencyScope
import io.github.latencyscope.urlconnection.LatencyUrlConnection
import io.github.latencyscope.volley.LatencyVolley
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.net.URL
import okhttp3.OkHttpClient

/**
 * One source file, compiled into both build types, that touches every
 * LatencyScope entry point an app would normally call.
 *
 * This is the drop-in claim made checkable. The real modules are wired in on
 * `debug` and `latencyscope-noop` on `release`, so if the no-op ever stopped
 * mirroring the surface, *this file* would fail to compile on release — which
 * is exactly the failure a consumer would hit, caught here instead of by them.
 * `:latencyscope-noop:apiCheck` covers the same ground at the signature level;
 * this covers it the way a caller actually experiences it.
 *
 * The full demo app lives in Phase 8; nothing here is meant to be a UI.
 */
internal object LatencyScopeWiring {

    fun install(context: Context) {
        LatencyScope.init(context, LatencyConfig(maxRecords = 2_000))
    }

    fun okHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .installLatencyScope()
        .build()

    fun ktorClient(): HttpClient = HttpClient(CIO) {
        install(LatencyScopeKtorPlugin) { deferToEngine = false }
    }

    fun volleyQueue(context: Context) = LatencyVolley.newRequestQueue(context, HurlStack())

    fun urlConnection(url: String) = LatencyUrlConnection.open(URL(url))

    fun runFlow(context: Context) {
        val handle = LatencyScope.start("sample flow")
        LatencyScope.mark("flow started")

        LatencyScope.track("https://example.invalid/warmup") { Unit }

        val session = handle.stop() ?: LatencyScope.stop()
        val report = LatencyScope.report(session) ?: return
        LatencyExport.shareAsync(context, report)
    }

    fun openReportScreens(context: Context): List<android.content.Intent> = listOf(
        io.github.latencyscope.ui.view.LatencyReportActivity.intent(context),
        io.github.latencyscope.ui.compose.LatencyReportActivity.intent(context),
    )
}
