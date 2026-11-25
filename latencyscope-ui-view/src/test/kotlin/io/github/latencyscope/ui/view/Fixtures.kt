package io.github.latencyscope.ui.view

import io.github.latencyscope.core.Aggregator
import io.github.latencyscope.core.ApiCallRecord
import io.github.latencyscope.core.DeviceInfo
import io.github.latencyscope.core.LatencyReport
import io.github.latencyscope.core.LatencySession
import io.github.latencyscope.core.Phases
import io.github.latencyscope.core.Source

internal fun call(
    route: String = "/users/{id}",
    method: String = "GET",
    totalMs: Long = 120,
    statusCode: Int? = 200,
    error: String? = null,
    startMs: Long = 0,
    phases: Phases = Phases.EMPTY,
    source: Source = Source.OKHTTP,
    id: String = "$method-$route-$startMs-$totalMs",
): ApiCallRecord = ApiCallRecord(
    id = id,
    source = source,
    url = "https://api.example.com$route",
    route = route,
    host = "api.example.com",
    method = method,
    statusCode = statusCode,
    startedAtEpochMs = 1_700_000_000_000 + startMs,
    startedAtElapsedMs = startMs,
    totalMs = totalMs,
    phases = phases,
    error = error,
)

internal fun fullPhases(server: Long = 90): Phases = Phases(
    queueMs = 2,
    dnsMs = 8,
    connectMs = 12,
    tlsMs = 20,
    requestHeadersMs = 1,
    serverMs = server,
    responseHeadersMs = 1,
    responseBodyMs = 14,
)

internal fun report(
    records: List<ApiCallRecord> = listOf(call()),
    name: String = "checkout flow",
    droppedCount: Long = 0,
): LatencyReport = Aggregator.aggregate(
    session = LatencySession(
        id = "session-1",
        name = name,
        startedAtEpochMs = 1_700_000_000_000,
        endedAtEpochMs = 1_700_000_005_000,
        durationMs = 5_000,
        device = DeviceInfo(manufacturer = "Google", model = "Pixel 8", sdkInt = 34),
        records = records,
        droppedCount = droppedCount,
    ),
    generatedAtEpochMs = 1_700_000_005_000,
)
