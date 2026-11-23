package io.github.latencyscope.export

import io.github.latencyscope.core.Aggregator
import io.github.latencyscope.core.ApiCallRecord
import io.github.latencyscope.core.DeviceInfo
import io.github.latencyscope.core.LatencyReport
import io.github.latencyscope.core.LatencySession
import io.github.latencyscope.core.Marker
import io.github.latencyscope.core.Phases
import io.github.latencyscope.core.Source

internal fun call(
    route: String = "/users/{id}",
    method: String = "GET",
    host: String = "api.example.com",
    url: String = "https://$host$route",
    totalMs: Long = 120,
    statusCode: Int? = 200,
    error: String? = null,
    startMs: Long = 0,
    phases: Phases = Phases.EMPTY,
    requestBytes: Long = -1,
    responseBytes: Long = -1,
    source: Source = Source.OKHTTP,
    id: String = "call-$route-$startMs-$totalMs",
): ApiCallRecord = ApiCallRecord(
    id = id,
    source = source,
    url = url,
    route = route,
    host = host,
    method = method,
    statusCode = statusCode,
    startedAtEpochMs = 1_700_000_000_000 + startMs,
    startedAtElapsedMs = startMs,
    totalMs = totalMs,
    phases = phases,
    requestBytes = requestBytes,
    responseBytes = responseBytes,
    protocol = "http/1.1",
    error = error,
)

internal fun fullPhases(server: Long = 90): Phases = Phases(
    queueMs = 2,
    dnsMs = 8,
    connectMs = 12,
    tlsMs = 20,
    requestHeadersMs = 1,
    requestBodyMs = 3,
    serverMs = server,
    responseHeadersMs = 1,
    responseBodyMs = 14,
)

internal fun session(
    name: String = "checkout flow",
    records: List<ApiCallRecord>,
    markers: List<Marker> = emptyList(),
    droppedCount: Long = 0,
    durationMs: Long = 5_000,
): LatencySession = LatencySession(
    id = "11111111-2222-3333-4444-555555555555",
    name = name,
    startedAtEpochMs = 1_700_000_000_000,
    endedAtEpochMs = 1_700_000_000_000 + durationMs,
    durationMs = durationMs,
    device = DeviceInfo(
        manufacturer = "Google",
        model = "Pixel 8",
        androidVersion = "14",
        sdkInt = 34,
        appId = "com.example.shop",
        appVersionName = "2.4.1",
        appVersionCode = 241,
        locale = "en_IN",
    ),
    records = records,
    markers = markers,
    droppedCount = droppedCount,
)

internal fun report(
    records: List<ApiCallRecord> = listOf(call()),
    name: String = "checkout flow",
    markers: List<Marker> = emptyList(),
    droppedCount: Long = 0,
): LatencyReport = Aggregator.aggregate(
    session = session(name = name, records = records, markers = markers, droppedCount = droppedCount),
    warnThresholdMs = 300,
    slowThresholdMs = 1_000,
    generatedAtEpochMs = 1_700_000_005_000,
)
