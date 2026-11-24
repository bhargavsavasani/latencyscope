package io.github.latencyscope.sample.net

import android.content.Context
import com.android.volley.DefaultRetryPolicy
import com.android.volley.NetworkResponse
import com.android.volley.Request
import com.android.volley.Response
import com.android.volley.VolleyError
import com.android.volley.toolbox.HttpHeaderParser
import io.github.latencyscope.sample.CallResult
import io.github.latencyscope.sample.SampleTab
import io.github.latencyscope.volley.LatencyVolley
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Volley — instrumented by wrapping its `BaseHttpStack`, so the measurement
 * sits below Volley's own queue. The queue wait therefore shows up as time the
 * report cannot attribute, which is exactly what it is.
 */
internal class VolleySampleClient(context: Context) : SampleClient {

    override val tab: SampleTab get() = SampleTab.VOLLEY

    // Application context: a queue outliving an Activity it held a reference to
    // is the classic Volley leak.
    private val queue = LatencyVolley.newRequestQueue(context.applicationContext)

    override suspend fun get(url: String, timeoutMs: Long): CallResult =
        suspendCancellableCoroutine { continuation ->
            val request = BytesRequest(
                url = url,
                onResult = { result -> if (continuation.isActive) continuation.resume(result) },
            ).apply {
                // 0 retries: Volley retries by default, and a silent retry would
                // put a second recorded call in the report with no sign of why.
                retryPolicy = DefaultRetryPolicy(timeoutMs.toInt(), 0, 1f)
                setShouldCache(false)
            }

            continuation.invokeOnCancellation { request.cancel() }
            queue.add(request)
        }

    override fun close() {
        queue.stop()
    }

    /**
     * Volley has no built-in request that keeps the raw bytes and the status of
     * a failure, and both are what the sample's log shows.
     */
    private class BytesRequest(
        url: String,
        private val onResult: (CallResult) -> Unit,
    ) : Request<CallResult>(Method.GET, url, null) {

        override fun parseNetworkResponse(response: NetworkResponse): Response<CallResult> =
            Response.success(
                CallResult(
                    statusCode = response.statusCode,
                    bytes = response.data?.size?.toLong() ?: 0L,
                    error = null,
                ),
                HttpHeaderParser.parseCacheHeaders(response),
            )

        override fun deliverResponse(response: CallResult) {
            onResult(response)
        }

        override fun deliverError(error: VolleyError) {
            // A 404 or 500 reaches Volley as an error, but it is a completed
            // call: keep the status so this tab groups with the others.
            val status = error.networkResponse?.statusCode
            onResult(
                CallResult(
                    statusCode = status,
                    bytes = error.networkResponse?.data?.size?.toLong() ?: -1,
                    error = if (status == null) error.describeForLog() else null,
                ),
            )
        }
    }
}
