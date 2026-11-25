package io.github.latencyscope.core

/**
 * Turns a raw [LatencySession] into a [LatencyReport].
 *
 * Percentiles are computed exactly, by sorting. An approximation (t-digest,
 * HDR histogram) buys nothing here: the record set is bounded by
 * [LatencyConfig.maxRecords], so an exact sort is both cheap and unarguable,
 * and aggregation happens once at stop() rather than on the hot path.
 */
public object Aggregator {

    @JvmStatic
    @JvmOverloads
    public fun aggregate(
        session: LatencySession,
        ratingScale: LatencyRatingScale = LatencyRatingScale(),
        generatedAtEpochMs: Long = System.currentTimeMillis(),
    ): LatencyReport {
        val records = session.records

        val routes = records
            .groupBy { Triple(it.route, it.method, it.host) }
            .map { (key, group) ->
                RouteStats(
                    route = key.first,
                    method = key.second,
                    host = key.third,
                    sources = group.mapTo(LinkedHashSet()) { it.source },
                    stats = statsOf(group),
                )
            }
            .sortedByDescending { it.stats.totalMs }

        val hosts = records
            .groupBy { it.host }
            .map { (host, group) -> HostStats(host, statsOf(group)) }
            .sortedByDescending { it.stats.totalMs }

        // Only calls whose transport could be read: ACCESS_NETWORK_STATE is
        // optional, so an app without it records null here and gets no split
        // rather than a bogus "unknown" bucket competing with the real ones.
        val networks = records
            .groupBy { it.networkType }
            .mapNotNull { (type, group) ->
                type?.let { NetworkStats(it, statsOf(group)) }
            }
            .sortedByDescending { it.stats.count }

        val errors = records
            .filter { it.isError }
            .groupBy { it.errorKey ?: "Unknown" }
            .map { (key, group) ->
                ErrorGroup(
                    key = key,
                    count = group.size,
                    routes = group.map { it.route }.distinct().sorted(),
                    sampleMessage = group.firstNotNullOfOrNull { it.error },
                )
            }
            .sortedByDescending { it.count }

        return LatencyReport(
            session = session,
            overall = statsOf(records),
            routes = routes,
            hosts = hosts,
            networks = networks,
            errors = errors,
            ratingScale = ratingScale,
            generatedAtEpochMs = generatedAtEpochMs,
        )
    }

    @JvmStatic
    public fun statsOf(records: List<ApiCallRecord>): Stats {
        if (records.isEmpty()) return Stats.EMPTY

        val durations = LongArray(records.size) { records[it].totalMs }
        durations.sort()

        var total = 0L
        var successes = 0
        var requestBytes = 0L
        var responseBytes = 0L
        for (record in records) {
            total += record.totalMs
            if (record.isSuccess) successes++
            if (record.requestBytes > 0) requestBytes += record.requestBytes
            if (record.responseBytes > 0) responseBytes += record.responseBytes
        }

        return Stats(
            count = records.size,
            successCount = successes,
            errorCount = records.size - successes,
            avgMs = total.toDouble() / records.size,
            minMs = durations.first(),
            maxMs = durations.last(),
            p50Ms = percentile(durations, 50.0),
            p75Ms = percentile(durations, 75.0),
            p90Ms = percentile(durations, 90.0),
            p95Ms = percentile(durations, 95.0),
            p99Ms = percentile(durations, 99.0),
            totalMs = total,
            requestBytes = requestBytes,
            responseBytes = responseBytes,
            phaseAverages = averagePhases(records),
        )
    }

    /**
     * Nearest-rank percentile over an ascending array.
     *
     * Nearest-rank always returns a value that actually occurred, which is the
     * right choice for a latency report: an interpolated "p95 = 412.7 ms" that
     * no request ever took invites arguments about the method instead of about
     * the latency.
     */
    @JvmStatic
    public fun percentile(sortedAscending: LongArray, percentile: Double): Long {
        require(percentile > 0.0 && percentile <= 100.0) {
            "percentile must be in (0, 100], was $percentile"
        }
        if (sortedAscending.isEmpty()) return 0
        val rank = Math.ceil(percentile / 100.0 * sortedAscending.size).toInt()
        return sortedAscending[(rank - 1).coerceIn(0, sortedAscending.size - 1)]
    }

    /** Mean of each phase across the calls that actually reported it. */
    private fun averagePhases(records: List<ApiCallRecord>): Phases {
        if (records.isEmpty()) return Phases.EMPTY

        var queueSum = 0L; var queueN = 0
        var dnsSum = 0L; var dnsN = 0
        var connectSum = 0L; var connectN = 0
        var tlsSum = 0L; var tlsN = 0
        var reqHeadSum = 0L; var reqHeadN = 0
        var reqBodySum = 0L; var reqBodyN = 0
        var serverSum = 0L; var serverN = 0
        var respHeadSum = 0L; var respHeadN = 0
        var respBodySum = 0L; var respBodyN = 0

        for (record in records) {
            val p = record.phases
            p.queueMs?.let { queueSum += it; queueN++ }
            p.dnsMs?.let { dnsSum += it; dnsN++ }
            p.connectMs?.let { connectSum += it; connectN++ }
            p.tlsMs?.let { tlsSum += it; tlsN++ }
            p.requestHeadersMs?.let { reqHeadSum += it; reqHeadN++ }
            p.requestBodyMs?.let { reqBodySum += it; reqBodyN++ }
            p.serverMs?.let { serverSum += it; serverN++ }
            p.responseHeadersMs?.let { respHeadSum += it; respHeadN++ }
            p.responseBodyMs?.let { respBodySum += it; respBodyN++ }
        }

        return Phases(
            queueMs = mean(queueSum, queueN),
            dnsMs = mean(dnsSum, dnsN),
            connectMs = mean(connectSum, connectN),
            tlsMs = mean(tlsSum, tlsN),
            requestHeadersMs = mean(reqHeadSum, reqHeadN),
            requestBodyMs = mean(reqBodySum, reqBodyN),
            serverMs = mean(serverSum, serverN),
            responseHeadersMs = mean(respHeadSum, respHeadN),
            responseBodyMs = mean(respBodySum, respBodyN),
        )
    }

    private fun mean(sum: Long, n: Int): Long? = if (n == 0) null else sum / n
}
