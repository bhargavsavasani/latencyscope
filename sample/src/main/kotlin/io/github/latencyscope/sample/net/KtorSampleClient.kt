package io.github.latencyscope.sample.net

import io.github.latencyscope.ktor.LatencyScopeKtorPlugin
import io.github.latencyscope.sample.CallResult
import io.github.latencyscope.sample.SampleTab
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes

/**
 * Ktor — instrumented by a client plugin rather than a listener, so phases are
 * limited to what a plugin can see: the whole round trip, plus the split
 * between headers and body the engine exposes.
 *
 * `deferToEngine = false` because CIO is not OkHttp: there is no engine-level
 * event listener to hand the measurement to.
 */
internal class KtorSampleClient : SampleClient {

    override val tab: SampleTab get() = SampleTab.KTOR

    private val client: HttpClient = HttpClient(CIO) {
        install(LatencyScopeKtorPlugin) { deferToEngine = false }
        install(HttpTimeout)
        // A non-2xx is data here, not a failure: throwing would hide the 404
        // and 500 scenarios from the report.
        expectSuccess = false
    }

    override suspend fun get(url: String, timeoutMs: Long): CallResult = runCatching {
        val response = client.get(url) {
            timeout { requestTimeoutMillis = timeoutMs }
        }
        val bytes = response.bodyAsBytes().size.toLong()
        // A 404 or 500 is a completed call: it gets a status and no error, the
        // same way OkHttp reports it, so the two tabs stay comparable.
        CallResult(statusCode = response.status.value, bytes = bytes, error = null)
    }.getOrElse { failure ->
        CallResult(statusCode = null, bytes = -1, error = failure.describeForLog())
    }

    override fun close() {
        client.close()
    }
}
