package io.github.latencyscope.export

import io.github.latencyscope.core.LatencyReport
import io.github.latencyscope.core.Phases

/**
 * Number and label formatting shared by the HTML report and the in-app UIs.
 *
 * It lives here, in one place, so a duration never reads as `1.5 s` on one
 * surface and `1500 ms` on another.
 */
public object LatencyFormat {

    @JvmStatic
    public fun duration(ms: Long): String = when {
        ms < 0 -> "-" + duration(-ms)
        ms < MILLIS_PER_SECOND -> "$ms ms"
        ms < MILLIS_PER_MINUTE -> trim(ms / MILLIS_PER_SECOND.toDouble()) + " s"
        else -> (ms / MILLIS_PER_MINUTE).toString() + "m " +
            ((ms % MILLIS_PER_MINUTE) / MILLIS_PER_SECOND) + "s"
    }

    @JvmStatic
    public fun bytes(bytes: Long): String = when {
        bytes <= 0 -> "0 B"
        bytes < BYTES_PER_KB -> "$bytes B"
        bytes < BYTES_PER_MB -> trim(bytes / BYTES_PER_KB.toDouble()) + " kB"
        else -> trim(bytes / BYTES_PER_MB.toDouble()) + " MB"
    }

    /** `-1` means the stack could not report a size; say so rather than "0 B". */
    @JvmStatic
    public fun bytesOrUnknown(bytes: Long): String =
        if (bytes < 0) "not reported" else bytes(bytes)

    @JvmStatic
    public fun percent(ratio: Double): String {
        if (ratio <= 0.0) return "0%"
        val value = ratio * 100
        return if (value < 1) "<1%" else trim(value) + "%"
    }

    /** Which latency band a duration falls into, for colour *and* for wording. */
    @JvmStatic
    public fun band(report: LatencyReport, ms: Long): LatencyBand = when {
        ms >= report.slowThresholdMs -> LatencyBand.SLOW
        ms >= report.warnThresholdMs -> LatencyBand.WARN
        else -> LatencyBand.FAST
    }

    private fun trim(value: Double): String {
        val rounded = Math.round(value * 10) / 10.0
        return if (rounded == Math.floor(rounded)) rounded.toLong().toString() else rounded.toString()
    }

    private const val MILLIS_PER_SECOND = 1_000L
    private const val MILLIS_PER_MINUTE = 60_000L
    private const val BYTES_PER_KB = 1_024L
    private const val BYTES_PER_MB = 1_048_576L
}

/** Latency bands, carrying a label so a reader never depends on colour alone. */
public enum class LatencyBand(public val label: String) {
    FAST("fast"),
    WARN("borderline"),
    SLOW("slow"),
}

/**
 * One segment of the phase bar.
 *
 * The nine measured phases collapse into five chronological buckets. Nine
 * categorical colours cannot be kept distinguishable for a colour-blind
 * reader; five can. Every raw phase is still available on the record itself
 * and in the CSV export.
 */
public data class PhaseSlice(
    public val label: String,
    public val description: String,
    /** Index into the renderer's fixed colour order; never reassigned. */
    public val slot: Int,
    public val ms: Long,
) {
    public fun share(total: Long): Double = if (total <= 0) 0.0 else ms.toDouble() / total
}

/** Collapses [Phases] into the five buckets the bars are drawn from. */
public object PhaseBreakdown {

    @JvmStatic
    public fun of(phases: Phases): List<PhaseSlice> = listOf(
        PhaseSlice(
            label = "Wait",
            description = "Queued before any work started — connection pool or dispatcher backlog.",
            slot = 0,
            ms = phases.queueMs ?: 0L,
        ),
        PhaseSlice(
            label = "Connect",
            description = "DNS, TCP handshake and TLS. Absent on a reused connection.",
            slot = 1,
            ms = (phases.dnsMs ?: 0L) + (phases.connectMs ?: 0L) + (phases.tlsMs ?: 0L),
        ),
        PhaseSlice(
            label = "Request",
            description = "Sending request headers and body.",
            slot = 2,
            ms = (phases.requestHeadersMs ?: 0L) + (phases.requestBodyMs ?: 0L),
        ),
        PhaseSlice(
            label = "Server",
            description = "Request fully sent, waiting for the first response byte — backend think time.",
            slot = 3,
            ms = phases.serverMs ?: 0L,
        ),
        PhaseSlice(
            label = "Response",
            description = "Receiving response headers and body.",
            slot = 4,
            ms = (phases.responseHeadersMs ?: 0L) + (phases.responseBodyMs ?: 0L),
        ),
    )

    /** Only the buckets that actually took time, for drawing. */
    @JvmStatic
    public fun nonEmpty(phases: Phases): List<PhaseSlice> = of(phases).filter { it.ms > 0 }

    @JvmStatic
    public fun total(phases: Phases): Long = of(phases).sumOf { it.ms }
}
