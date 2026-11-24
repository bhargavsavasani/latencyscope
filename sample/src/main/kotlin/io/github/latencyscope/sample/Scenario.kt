package io.github.latencyscope.sample

/**
 * The four instrumentation modules, one tab each.
 *
 * The point of the tabs is comparison: the same scenario run through OkHttp and
 * through HttpURLConnection should produce the same totals but a very different
 * phase breakdown, because only OkHttp reports DNS/connect/TLS separately.
 */
internal enum class SampleTab(val label: String) {
    OKHTTP("OkHttp"),
    KTOR("Ktor"),
    VOLLEY("Volley"),
    URL_CONNECTION("HttpURLConnection"),
}

/**
 * A scenario is one button: a set of URLs, a timeout, and whether they are
 * fired at once.
 *
 * These hit real public endpoints rather than a local mock on purpose. A
 * loopback server has no DNS, no TLS handshake and no server think-time, so it
 * would exercise exactly the phases the library exists to show.
 */
// Top-level, not in a companion: an enum constant is initialised before its
// own companion object is, so referencing one from a constructor argument does
// not compile.
private const val HTTPBIN = "https://httpbin.org"
private const val JSONPLACEHOLDER = "https://jsonplaceholder.typicode.com"
internal const val DEFAULT_TIMEOUT_MS: Long = 10_000

internal enum class Scenario(
    val label: String,
    val detail: String,
    val urls: List<String>,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    val parallel: Boolean = false,
) {
    FAST_GET(
        label = "Fast GET",
        detail = "A small JSON document — the baseline every other row is read against.",
        urls = listOf("$JSONPLACEHOLDER/todos/1"),
    ),
    SLOW(
        label = "Slow endpoint (2s)",
        detail = "Server think-time only. Should land almost entirely in the Server phase.",
        urls = listOf("$HTTPBIN/delay/2"),
    ),
    NOT_FOUND(
        label = "404",
        detail = "A completed call with a failing status, not a transport error.",
        urls = listOf("$HTTPBIN/status/404"),
    ),
    SERVER_ERROR(
        label = "500",
        detail = "Grouped under errors by status, alongside the 404.",
        urls = listOf("$HTTPBIN/status/500"),
    ),
    TIMEOUT(
        label = "Timeout",
        detail = "A 10s endpoint against a 2s client timeout: recorded as an error with no status.",
        urls = listOf("$HTTPBIN/delay/10"),
        timeoutMs = 2_000,
    ),
    LARGE_PAYLOAD(
        label = "Large payload",
        detail = "~1 MB of JSON. Pushes the time into Response body and the bytes into the totals.",
        urls = listOf("$JSONPLACEHOLDER/photos"),
        timeoutMs = 30_000,
    ),
    BURST(
        label = "Parallel burst (6)",
        detail = "Six calls at once. Connection-pool contention shows up as queue and connect time.",
        urls = (1..6).map { "$JSONPLACEHOLDER/posts/$it" },
        parallel = true,
    ),
    ;

}

/** What one request did, as the sample's own log sees it. */
internal data class CallResult(
    val statusCode: Int?,
    val bytes: Long,
    val error: String?,
)

/** One line in the on-screen log. */
internal data class LogLine(
    val tab: SampleTab,
    val scenario: Scenario,
    val url: String,
    val result: CallResult,
    val durationMs: Long,
) {
    val isError: Boolean get() = result.error != null || (result.statusCode ?: 0) >= 400

    val summary: String
        get() {
            val outcome = result.error ?: "HTTP ${result.statusCode}"
            val size = if (result.bytes >= 0) " · ${result.bytes} B" else ""
            return "$outcome · ${durationMs}ms$size"
        }

    val path: String get() = url.removePrefix("https://").substringAfter('/', url)
}
