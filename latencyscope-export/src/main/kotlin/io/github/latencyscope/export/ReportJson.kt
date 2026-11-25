package io.github.latencyscope.export

import io.github.latencyscope.core.ApiCallRecord
import io.github.latencyscope.core.LatencyReport
import io.github.latencyscope.core.Phases
import io.github.latencyscope.core.Stats

/**
 * Serializes a [LatencyReport].
 *
 * The same document is written to `report.json` and embedded in the HTML, so
 * the page and the machine-readable export can never disagree about a number.
 */
internal object ReportJson {

    fun encode(report: LatencyReport): String = JsonWriter().obj {
        field("schemaVersion", SCHEMA_VERSION)
        field("generatedAtEpochMs", report.generatedAtEpochMs)
        objectField("library") {
            field("name", report.session.device.libraryVersion.let { "LatencyScope" })
            field("version", report.session.device.libraryVersion)
        }
        objectField("ratingScale") {
            field("excellentUnderMs", report.ratingScale.excellentUnderMs)
            field("goodUnderMs", report.ratingScale.goodUnderMs)
            field("acceptableUnderMs", report.ratingScale.acceptableUnderMs)
            field("slowUnderMs", report.ratingScale.slowUnderMs)
        }
        objectField("session") { writeSession(report) }
        field("verdict", LatencyFormat.rating(report, report.overall.p95Ms).label)
        objectField("overall") { writeStats(report.overall) }
        field("throughputBytesPerSecond", report.throughputBytesPerSecond)
        arrayField("routes") {
            report.routes.forEach { route ->
                obj {
                    field("route", route.route)
                    field("method", route.method)
                    field("host", route.host)
                    arrayField("sources") { route.sources.forEach { value(it.displayName) } }
                    field("fullPhases", route.sources.any { it.hasFullPhaseBreakdown })
                    field("timeShare", route.timeShare(report.overall.totalMs))
                    objectField("stats") { writeStats(route.stats) }
                }
            }
        }
        arrayField("hosts") {
            report.hosts.forEach { host ->
                obj {
                    field("host", host.host)
                    objectField("stats") { writeStats(host.stats) }
                }
            }
        }
        arrayField("networks") {
            report.networks.forEach { network ->
                obj {
                    field("networkType", network.networkType)
                    objectField("stats") { writeStats(network.stats) }
                }
            }
        }
        arrayField("errors") {
            report.errors.forEach { group ->
                obj {
                    field("key", group.key)
                    field("count", group.count)
                    field("sample", group.sampleMessage)
                    arrayField("routes") { group.routes.forEach { value(it) } }
                }
            }
        }
        arrayField("calls") { report.session.records.forEach { obj { writeCall(it) } } }
    }.toString()

    private fun JsonWriter.writeSession(report: LatencyReport) {
        val session = report.session
        field("id", session.id)
        field("name", session.name)
        field("startedAtEpochMs", session.startedAtEpochMs)
        field("endedAtEpochMs", session.endedAtEpochMs)
        field("durationMs", session.durationMs)
        field("droppedCount", session.droppedCount)
        objectField("device") {
            val device = session.device
            field("manufacturer", device.manufacturer)
            field("model", device.model)
            field("androidVersion", device.androidVersion)
            field("sdkInt", device.sdkInt)
            field("appName", device.appName)
            field("appId", device.appId)
            field("appVersionName", device.appVersionName)
            field("appVersionCode", device.appVersionCode)
            field("locale", device.locale)
            field("networkTypeAtStart", device.networkTypeAtStart)
            field("linkDownstreamKbps", device.linkDownstreamKbps)
            field("linkUpstreamKbps", device.linkUpstreamKbps)
        }
        arrayField("markers") {
            session.markers.forEach { marker ->
                obj {
                    field("label", marker.label)
                    field("atMs", marker.atElapsedMs)
                }
            }
        }
    }

    private fun JsonWriter.writeStats(stats: Stats) {
        field("count", stats.count)
        field("successCount", stats.successCount)
        field("errorCount", stats.errorCount)
        field("errorRate", stats.errorRate)
        field("avgMs", stats.avgMs)
        field("minMs", stats.minMs)
        field("maxMs", stats.maxMs)
        field("p50Ms", stats.p50Ms)
        field("p75Ms", stats.p75Ms)
        field("p90Ms", stats.p90Ms)
        field("p95Ms", stats.p95Ms)
        field("p99Ms", stats.p99Ms)
        field("totalMs", stats.totalMs)
        field("requestBytes", stats.requestBytes)
        field("responseBytes", stats.responseBytes)
        objectField("phases") { writePhases(stats.phaseAverages) }
    }

    private fun JsonWriter.writePhases(phases: Phases) {
        field("queueMs", phases.queueMs)
        field("dnsMs", phases.dnsMs)
        field("connectMs", phases.connectMs)
        field("tlsMs", phases.tlsMs)
        field("requestHeadersMs", phases.requestHeadersMs)
        field("requestBodyMs", phases.requestBodyMs)
        field("serverMs", phases.serverMs)
        field("responseHeadersMs", phases.responseHeadersMs)
        field("responseBodyMs", phases.responseBodyMs)
    }

    private fun JsonWriter.writeCall(record: ApiCallRecord) {
        field("id", record.id)
        field("source", record.source.displayName)
        field("url", record.url)
        field("route", record.route)
        field("host", record.host)
        field("method", record.method)
        field("statusCode", record.statusCode)
        field("startMs", record.startedAtElapsedMs)
        field("totalMs", record.totalMs)
        field("requestBytes", record.requestBytes)
        field("responseBytes", record.responseBytes)
        field("protocol", record.protocol)
        field("fromCache", record.fromCache)
        field("retryCount", record.retryCount)
        field("thread", record.threadName)
        field("error", record.error)
        field("networkType", record.networkType)
        field("throughputBytesPerSecond", record.throughputBytesPerSecond)
        field("success", record.isSuccess)
        objectField("phases") { writePhases(record.phases) }
    }

    private const val SCHEMA_VERSION = 1
}
