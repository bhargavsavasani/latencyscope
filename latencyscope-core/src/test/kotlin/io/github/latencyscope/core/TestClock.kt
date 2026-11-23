package io.github.latencyscope.core

/** Deterministic clock: time only moves when a test moves it. */
class TestClock(
    private var elapsed: Long = 1_000,
    private var epoch: Long = 1_700_000_000_000,
) : Clock {
    override fun elapsedMs(): Long = elapsed
    override fun epochMs(): Long = epoch

    fun advance(ms: Long) {
        elapsed += ms
        epoch += ms
    }
}

fun record(
    route: String = "/a",
    method: String = "GET",
    host: String = "api.example.com",
    totalMs: Long = 100,
    statusCode: Int? = 200,
    error: String? = null,
    startedAtElapsedMs: Long = 0,
    phases: Phases = Phases.EMPTY,
    requestBytes: Long = -1,
    responseBytes: Long = -1,
    source: Source = Source.OKHTTP,
): ApiCallRecord = ApiCallRecord(
    id = "id-$route-$startedAtElapsedMs-$totalMs",
    source = source,
    url = "https://$host$route",
    route = route,
    host = host,
    method = method,
    statusCode = statusCode,
    startedAtEpochMs = 0,
    startedAtElapsedMs = startedAtElapsedMs,
    totalMs = totalMs,
    phases = phases,
    requestBytes = requestBytes,
    responseBytes = responseBytes,
    error = error,
)
