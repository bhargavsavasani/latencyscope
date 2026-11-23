package io.github.latencyscope.core

/**
 * Per-phase breakdown of a single call, in milliseconds.
 *
 * Every field is nullable because no stack reports all of them, and even
 * OkHttp skips phases it did not perform: a pooled connection has no DNS,
 * connect or TLS phase, and a GET has no request-body phase.
 *
 * @property queueMs time between the call being enqueued and work starting on
 *   it — connection-pool wait, dispatcher backlog, thread starvation.
 * @property serverMs time between the request finishing upload and the first
 *   response header arriving. This is the closest a client can get to "how
 *   long the backend took", and is usually the number worth optimising.
 */
public data class Phases(
    public val queueMs: Long? = null,
    public val dnsMs: Long? = null,
    public val connectMs: Long? = null,
    public val tlsMs: Long? = null,
    public val requestHeadersMs: Long? = null,
    public val requestBodyMs: Long? = null,
    public val serverMs: Long? = null,
    public val responseHeadersMs: Long? = null,
    public val responseBodyMs: Long? = null,
) {

    /** Sum of every phase that was actually measured. */
    public val accountedMs: Long
        get() = (queueMs ?: 0) + (dnsMs ?: 0) + (connectMs ?: 0) + (tlsMs ?: 0) +
            (requestHeadersMs ?: 0) + (requestBodyMs ?: 0) + (serverMs ?: 0) +
            (responseHeadersMs ?: 0) + (responseBodyMs ?: 0)

    public val isEmpty: Boolean
        get() = queueMs == null && dnsMs == null && connectMs == null && tlsMs == null &&
            requestHeadersMs == null && requestBodyMs == null && serverMs == null &&
            responseHeadersMs == null && responseBodyMs == null

    /** Phase values in display order, paired with their labels. */
    public fun asList(): List<Pair<String, Long?>> = listOf(
        LABEL_QUEUE to queueMs,
        LABEL_DNS to dnsMs,
        LABEL_CONNECT to connectMs,
        LABEL_TLS to tlsMs,
        LABEL_REQUEST_HEADERS to requestHeadersMs,
        LABEL_REQUEST_BODY to requestBodyMs,
        LABEL_SERVER to serverMs,
        LABEL_RESPONSE_HEADERS to responseHeadersMs,
        LABEL_RESPONSE_BODY to responseBodyMs,
    )

    public companion object {
        public val EMPTY: Phases = Phases()

        public const val LABEL_QUEUE: String = "Queue"
        public const val LABEL_DNS: String = "DNS"
        public const val LABEL_CONNECT: String = "Connect"
        public const val LABEL_TLS: String = "TLS"
        public const val LABEL_REQUEST_HEADERS: String = "Request headers"
        public const val LABEL_REQUEST_BODY: String = "Request body"
        public const val LABEL_SERVER: String = "Server"
        public const val LABEL_RESPONSE_HEADERS: String = "Response headers"
        public const val LABEL_RESPONSE_BODY: String = "Response body"

        /** Labels in the same order as [asList], for report legends. */
        public val LABELS: List<String> = listOf(
            LABEL_QUEUE, LABEL_DNS, LABEL_CONNECT, LABEL_TLS,
            LABEL_REQUEST_HEADERS, LABEL_REQUEST_BODY, LABEL_SERVER,
            LABEL_RESPONSE_HEADERS, LABEL_RESPONSE_BODY,
        )
    }
}
