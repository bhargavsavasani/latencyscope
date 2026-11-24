package io.github.latencyscope.volley

import com.android.volley.Request
import com.android.volley.toolbox.BaseHttpStack
import com.android.volley.toolbox.HttpResponse
import io.github.latencyscope.core.LatencyScope
import io.github.latencyscope.core.Phases
import io.github.latencyscope.core.RawCall
import io.github.latencyscope.core.Source
import io.github.latencyscope.core.describe
import java.io.IOException

/**
 * Wraps a Volley [BaseHttpStack] and records every request it executes.
 *
 * ```kotlin
 * val queue = Volley.newRequestQueue(context, LatencyHttpStack(HurlStack()))
 * ```
 *
 * ### Fidelity
 * Volley's stack interface hands over one opaque "execute this request" call,
 * so this measures the total round trip and nothing finer. There is no
 * connection phase breakdown — records are marked [Source.VOLLEY] and the
 * report omits the phase bar for them rather than inventing zeros.
 *
 * ### Retries
 * Volley implements its retry policy *above* the stack by calling it again,
 * so each attempt is recorded as its own call. That is deliberate: two
 * attempts really did cost the user two round trips, and collapsing them
 * would hide a retry storm, which is exactly the kind of thing a latency
 * report exists to expose.
 */
public class LatencyHttpStack(
    private val delegate: BaseHttpStack,
) : BaseHttpStack() {

    @Throws(IOException::class)
    override fun executeRequest(
        request: Request<*>,
        additionalHeaders: MutableMap<String, String>?,
    ): HttpResponse {
        if (!LatencyScope.isRecording()) {
            return delegate.executeRequest(request, additionalHeaders)
        }

        val startEpochMs = LatencyScope.epochMs()
        val startElapsedMs = LatencyScope.elapsedMs()
        val startNanos = System.nanoTime()
        val threadName = Thread.currentThread().name

        try {
            val response = delegate.executeRequest(request, additionalHeaders)
            submit(
                request = request,
                statusCode = response.statusCode,
                responseBytes = response.contentLength.toLong(),
                totalNanos = System.nanoTime() - startNanos,
                startEpochMs = startEpochMs,
                startElapsedMs = startElapsedMs,
                threadName = threadName,
                error = null,
            )
            return response
        } catch (throwable: Throwable) {
            submit(
                request = request,
                statusCode = null,
                responseBytes = -1,
                totalNanos = System.nanoTime() - startNanos,
                startEpochMs = startEpochMs,
                startElapsedMs = startElapsedMs,
                threadName = threadName,
                error = throwable.describe(),
            )
            // Volley's own retry and error handling must see the original failure.
            throw throwable
        }
    }

    @Suppress("LongParameterList")
    private fun submit(
        request: Request<*>,
        statusCode: Int?,
        responseBytes: Long,
        totalNanos: Long,
        startEpochMs: Long,
        startElapsedMs: Long,
        threadName: String?,
        error: String?,
    ) {
        // Sized once and reused: Request.getBody() re-serialises, so asking
        // twice would encode the payload twice on the network thread.
        val requestBytes = requestBodySize(request)

        LatencyScope.submit(
            RawCall(
                source = Source.VOLLEY,
                url = request.url.orEmpty(),
                method = methodName(request.method, requestBytes),
                statusCode = statusCode,
                startedAtEpochMs = startEpochMs,
                startedAtElapsedMs = startElapsedMs,
                totalMs = totalNanos / NANOS_PER_MILLI,
                phases = Phases.EMPTY,
                requestBytes = requestBytes,
                // Volley reports -1 for a chunked or unknown-length response.
                responseBytes = responseBytes,
                retryCount = request.retryPolicy?.currentRetryCount ?: 0,
                threadName = threadName,
                error = error,
            ),
        )
    }

    /**
     * Size of the outgoing body.
     *
     * `Request.getBody()` *serialises* the body rather than returning a cached
     * copy, so this is a second encode of the payload on the network thread —
     * the stack itself already made the first one. That cost is accepted
     * knowingly: `Content-Length` is the only alternative source and Volley
     * does not set it in `getHeaders()`, so reading headers instead would
     * report "unknown" for essentially every POST. It throws for a malformed
     * body, and an unknown size is a better outcome than a failed request.
     */
    private fun requestBodySize(request: Request<*>): Long = try {
        request.body?.size?.toLong() ?: -1L
    } catch (_: Throwable) {
        -1L
    }

    /**
     * Volley's integer method, as a name.
     *
     * [Request.Method.DEPRECATED_GET_OR_POST] has no fixed name — Volley itself
     * decides at execution time, picking POST when the request carries a body
     * and GET when it does not. Labelling every such request "GET" would put a
     * POST in the report under the wrong method and split one route into two
     * rows, so the same rule is applied here, reusing the body size already
     * measured rather than serialising again.
     */
    private fun methodName(method: Int, requestBytes: Long): String = when (method) {
        Request.Method.GET -> "GET"
        Request.Method.POST -> "POST"
        Request.Method.PUT -> "PUT"
        Request.Method.DELETE -> "DELETE"
        Request.Method.HEAD -> "HEAD"
        Request.Method.OPTIONS -> "OPTIONS"
        Request.Method.TRACE -> "TRACE"
        Request.Method.PATCH -> "PATCH"
        Request.Method.DEPRECATED_GET_OR_POST -> if (requestBytes > 0) "POST" else "GET"
        // Anything a future Volley version adds. GET is the safer guess: it is
        // what Volley falls back to, and it has no side effects.
        else -> "GET"
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
