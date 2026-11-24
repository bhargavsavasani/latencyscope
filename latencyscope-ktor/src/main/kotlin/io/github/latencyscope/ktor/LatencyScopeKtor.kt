package io.github.latencyscope.ktor

import io.github.latencyscope.core.LatencyScope
import io.github.latencyscope.core.Phases
import io.github.latencyscope.core.RawCall
import io.github.latencyscope.core.Source
import io.github.latencyscope.core.describe
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent

/** Configuration for [LatencyScopeKtorPlugin]. */
public class LatencyScopeKtorConfig {

    /**
     * Skip recording in this plugin.
     *
     * Set this when the client runs on the OkHttp engine *and* that engine's
     * `OkHttpClient` already has `installLatencyScope()` on it — otherwise one
     * call is recorded twice, once coarsely here and once with a full phase
     * breakdown there.
     *
     * Deliberately **not** auto-detected: guessing wrong in the other
     * direction produces an empty report, and a report that is silently empty
     * is worse than one with a duplicate the user can see.
     */
    public var deferToEngine: Boolean = false
}

/**
 * Ktor client plugin that records every call into LatencyScope.
 *
 * ```kotlin
 * val client = HttpClient(CIO) {
 *     install(LatencyScopeKtorPlugin)
 * }
 * ```
 *
 * ### What it can and cannot see
 * Ktor's plugin pipeline sits above the engine, so this records the total
 * round trip, the status, and whatever sizes the headers declare. It cannot
 * break a call into DNS/TLS/server phases — no Ktor engine exposes them. The
 * report marks these records as [Source.KTOR] and omits the phase bar for
 * them rather than inventing numbers.
 *
 * For a full breakdown on Android, run Ktor on the OkHttp engine, install
 * `latencyscope-okhttp` on that engine's client, and set [deferToEngine].
 */
public val LatencyScopeKtorPlugin: ClientPlugin<LatencyScopeKtorConfig> =
    createClientPlugin("LatencyScope", ::LatencyScopeKtorConfig) {
        val deferToEngine = pluginConfig.deferToEngine

        on(Send) { request ->
            if (deferToEngine || !LatencyScope.isRecording()) {
                return@on proceed(request)
            }

            val startEpochMs = LatencyScope.epochMs()
            val startElapsedMs = LatencyScope.elapsedMs()
            val startNanos = System.nanoTime()
            val threadName = Thread.currentThread().name

            try {
                val call = proceed(request)
                LatencyScope.submit(
                    request.toRawCall(
                        response = call.response,
                        totalMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLI,
                        startEpochMs = startEpochMs,
                        startElapsedMs = startElapsedMs,
                        threadName = threadName,
                        error = null,
                    ),
                )
                call
            } catch (throwable: Throwable) {
                LatencyScope.submit(
                    request.toRawCall(
                        response = null,
                        totalMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLI,
                        startEpochMs = startEpochMs,
                        startElapsedMs = startElapsedMs,
                        threadName = threadName,
                        error = throwable.describe(),
                    ),
                )
                // The caller's failure handling must be unaffected by recording.
                throw throwable
            }
        }
    }

private const val NANOS_PER_MILLI = 1_000_000L

private fun HttpRequestBuilder.toRawCall(
    response: HttpResponse?,
    totalMs: Long,
    startEpochMs: Long,
    startElapsedMs: Long,
    threadName: String?,
    error: String?,
): RawCall = RawCall(
    source = Source.KTOR,
    url = url.buildString(),
    method = method.value,
    statusCode = response?.status?.value,
    startedAtEpochMs = startEpochMs,
    startedAtElapsedMs = startElapsedMs,
    totalMs = totalMs,
    // No Ktor engine exposes connection phases; an empty breakdown is honest.
    phases = Phases.EMPTY,
    requestBytes = requestBodySize(),
    responseBytes = response?.declaredContentLength() ?: -1,
    protocol = response?.version?.toString(),
    threadName = threadName,
    error = error,
)

/**
 * Size of the outgoing body when the content declares one, else -1.
 *
 * Only declared lengths are read. `ByteArrayContent.bytes()` and
 * `String.toByteArray()` each copy the whole body, and copying a request body
 * on the caller's thread to put a number in a debug report is not a trade this
 * library makes — an unknown size reports as unknown.
 */
private fun HttpRequestBuilder.requestBodySize(): Long {
    val content = body
    return when (content) {
        is OutgoingContent -> content.contentLength ?: -1L
        is ByteArray -> content.size.toLong()
        else -> -1L
    }
}

/**
 * Content-Length as declared by the server.
 *
 * Reading the actual body here would consume it before the caller ever sees
 * it, so an undeclared length stays unknown (-1) rather than being measured.
 */
private fun HttpResponse.declaredContentLength(): Long =
    headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: -1L
