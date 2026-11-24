package io.github.latencyscope.core

/**
 * One completed HTTP call.
 *
 * Records are immutable and are created only after the call finishes, so the
 * recording path never holds a mutable object across the network boundary.
 *
 * @property route the normalized route (`/users/{id}/orders`) used for
 *   aggregation. See [RouteNormalizer] — without normalization every call with
 *   a different id becomes its own bucket and the report is noise.
 * @property statusCode null when the call failed before a response arrived.
 * @property error exception class and message; never a stack trace, and never
 *   a response body.
 * @property startedAtElapsedMs milliseconds from the start of the session.
 *   Negative for a call that was already in flight when recording began; the
 *   timeline clamps those rather than dropping them, since a call that spans
 *   the start of a flow is still part of it.
 */
public data class ApiCallRecord(
    public val id: String,
    public val source: Source,
    public val url: String,
    public val route: String,
    public val host: String,
    public val method: String,
    public val statusCode: Int?,
    public val startedAtEpochMs: Long,
    public val startedAtElapsedMs: Long,
    public val totalMs: Long,
    public val phases: Phases = Phases.EMPTY,
    public val requestBytes: Long = -1,
    public val responseBytes: Long = -1,
    public val protocol: String? = null,
    public val fromCache: Boolean = false,
    public val retryCount: Int = 0,
    public val threadName: String? = null,
    public val error: String? = null,
    public val networkType: String? = null,
) {

    /** A call is successful when it returned a 1xx-3xx status and did not throw. */
    public val isSuccess: Boolean
        get() = error == null && statusCode != null && statusCode < 400

    public val isError: Boolean
        get() = !isSuccess

    /** Grouping key for the error breakdown: either the status or the throwable. */
    public val errorKey: String?
        get() = when {
            isSuccess -> null
            error != null -> error.substringBefore(':').trim()
            else -> "HTTP $statusCode"
        }

    /** Bytes transferred in both directions; unknown counts (-1) are skipped. */
    public val totalBytes: Long
        get() = maxOf(requestBytes, 0) + maxOf(responseBytes, 0)

    /**
     * Measured download throughput in bytes per second, or null when it
     * cannot be derived honestly.
     *
     * This is throughput that actually happened — recorded bytes over the
     * time they took — not the link speed the OS advertises, which describes
     * the radio's capability rather than what this call achieved.
     *
     * It is measured over [Phases.responseBodyMs] where the stack reports it
     * (OkHttp, HttpURLConnection), because that is the window where bytes
     * were really moving. Falling back to [totalMs] would divide by server
     * think time too and understate a fast transfer behind a slow endpoint,
     * so stacks that cannot split the phases report null rather than a
     * number that means something different.
     */
    public val throughputBytesPerSecond: Double?
        get() {
            val transferMs = phases.responseBodyMs ?: return null
            if (transferMs <= 0 || responseBytes <= 0) return null
            return responseBytes * MILLIS_PER_SECOND / transferMs
        }

    public val endedAtElapsedMs: Long
        get() = startedAtElapsedMs + totalMs

    private companion object {
        const val MILLIS_PER_SECOND = 1_000.0
    }
}
