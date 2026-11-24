package io.github.latencyscope.sample.net

import io.github.latencyscope.okhttp.installLatencyScope
import io.github.latencyscope.sample.CallResult
import io.github.latencyscope.sample.SampleTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * OkHttp — the only stack that reports a full phase breakdown, because
 * `EventListener` is the only one of the four that is told about DNS, connect
 * and TLS separately.
 */
internal class OkHttpSampleClient : SampleClient {

    override val tab: SampleTab get() = SampleTab.OKHTTP

    // One client, shared: a new OkHttpClient per call would mean a cold
    // connection pool every time, and the report would show a TLS handshake on
    // calls that should have reused a connection.
    private val client: OkHttpClient = OkHttpClient.Builder()
        .installLatencyScope()
        .build()

    override suspend fun get(url: String, timeoutMs: Long): CallResult =
        withContext(Dispatchers.IO) {
            // newBuilder shares the pool and dispatcher with the parent client,
            // so a per-scenario timeout costs nothing.
            val scoped = client.newBuilder()
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()

            runCatching {
                scoped.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    val bytes = response.body?.bytes()?.size?.toLong() ?: 0L
                    CallResult(statusCode = response.code, bytes = bytes, error = null)
                }
            }.getOrElse { failure ->
                CallResult(statusCode = null, bytes = -1, error = failure.describeForLog())
            }
        }

    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
