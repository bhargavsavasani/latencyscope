package io.github.latencyscope.export

import io.github.latencyscope.core.Marker
import io.github.latencyscope.core.Phases
import io.github.latencyscope.core.Source
import java.io.File
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Writes a realistic report to `build/reports/latencyscope-sample.html`.
 *
 * This is a development aid as much as a test: open that file in a browser to
 * see what a consumer will actually get. A renderer that only ever runs
 * against three-row fixtures hides layout problems that appear the moment a
 * real session has sixty routes and a long tail.
 */
class SampleReportWriterTest {

    @Test
    fun writesABrowsableSampleReport() {
        val random = Random(seed = 42)
        val records = buildList {
            var clock = 0L

            fun emit(
                route: String,
                method: String = "GET",
                host: String = "api.shop.example.com",
                base: Long,
                jitter: Long,
                count: Int,
                status: Int? = 200,
                source: Source = Source.OKHTTP,
                error: String? = null,
                pooled: Boolean = true,
            ) {
                repeat(count) { index ->
                    val total = base + random.nextLong(0, jitter + 1)
                    val server = (total * SERVER_SHARE).toLong()
                    add(
                        call(
                            id = route + "-" + method + "-" + index,
                            route = route,
                            method = method,
                            host = host,
                            url = "https://" + host + route.replace("{id}", (100 + index).toString()),
                            totalMs = total,
                            statusCode = status,
                            error = error,
                            startMs = clock,
                            source = source,
                            requestBytes = if (method == "GET") -1 else 180L + index,
                            responseBytes = 400L + random.nextLong(0, 9_000),
                            phases = if (source != Source.OKHTTP) {
                                Phases.EMPTY
                            } else {
                                Phases(
                                    queueMs = random.nextLong(0, 4),
                                    dnsMs = if (pooled) null else random.nextLong(5, 30),
                                    connectMs = if (pooled) null else random.nextLong(10, 40),
                                    tlsMs = if (pooled) null else random.nextLong(20, 70),
                                    requestHeadersMs = 1,
                                    requestBodyMs = if (method == "GET") null else 2,
                                    serverMs = server,
                                    responseHeadersMs = 1,
                                    responseBodyMs = (total - server - 2).coerceAtLeast(1),
                                )
                            },
                        ),
                    )
                    clock += random.nextLong(10, 260)
                }
            }

            emit("/v1/config", base = 90, jitter = 40, count = 1, pooled = false)
            emit("/v1/products", base = 140, jitter = 90, count = 14)
            emit("/v1/products/{id}", base = 110, jitter = 420, count = 22)
            emit("/v1/products/{id}/reviews", base = 260, jitter = 900, count = 9)
            emit("/v1/cart", method = "POST", base = 320, jitter = 180, count = 6)
            emit("/v1/cart/{id}", method = "DELETE", base = 180, jitter = 60, count = 3)
            emit("/v1/checkout", method = "POST", base = 1_400, jitter = 1_600, count = 4)
            emit("/v1/orders", base = 240, jitter = 120, count = 5)
            emit("/v1/inventory", base = 200, jitter = 80, count = 3, status = 503)
            emit(
                "/v1/recommendations",
                base = 2_100,
                jitter = 400,
                count = 2,
                status = null,
                error = "SocketTimeoutException: timeout",
            )
            emit("/track/event", host = "metrics.example.com", base = 70, jitter = 40, count = 8, source = Source.KTOR)
            emit("/img/{id}.jpg", host = "cdn.example.com", base = 60, jitter = 300, count = 11, source = Source.VOLLEY)
        }

        val sample = report(
            records = records,
            name = "Browse and checkout",
            markers = listOf(
                Marker("app launched", 1_700_000_000_100, 100),
                Marker("product opened", 1_700_000_003_400, 3_400),
                Marker("checkout tapped", 1_700_000_008_900, 8_900),
            ),
        )

        val output = File("build/reports").apply { mkdirs() }
        val html = File(output, "latencyscope-sample.html")
        html.writeText(LatencyExport.toHtml(sample))
        File(output, "latencyscope-sample-summary.csv").writeText(LatencyExport.toCsvSummary(sample))
        File(output, "latencyscope-sample-calls.csv").writeText(LatencyExport.toCsvCalls(sample))

        assertTrue(html.length() > MIN_SAMPLE_BYTES)
        // A report of this size must still be small enough to attach to a
        // ticket or a chat message without anyone thinking about it.
        assertTrue("sample report is " + html.length() + " bytes", html.length() < MAX_SAMPLE_BYTES)
    }

    private companion object {
        const val SERVER_SHARE = 0.72
        const val MIN_SAMPLE_BYTES = 20_000
        const val MAX_SAMPLE_BYTES = 600_000
    }
}
