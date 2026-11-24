@file:Suppress("UNUSED_PARAMETER", "unused")

package io.github.latencyscope.export

import android.content.Context
import android.net.Uri
import io.github.latencyscope.core.LatencyReport
import io.github.latencyscope.core.Phases
import java.io.File

/*
 * No-op mirror of latencyscope-export.
 *
 * The real module renders HTML, two CSVs and a JSON document. None of that
 * runs here: there is no session to export, so every renderer returns an empty
 * string and nothing touches the disk. What matters is that a release build
 * which calls `shareAsync` from a debug menu still compiles and still does
 * something defensible — in this case, nothing at all.
 */

/** Mirror of the real [LatencyExport]. Writes nothing and shares nothing. */
public object LatencyExport {

    @JvmStatic
    public fun toHtml(report: LatencyReport): String = ""

    @JvmStatic
    public fun toCsvCalls(report: LatencyReport): String = ""

    @JvmStatic
    public fun toCsvSummary(report: LatencyReport): String = ""

    @JvmStatic
    public fun toJson(report: LatencyReport): String = ""

    /**
     * Returns paths inside the directory the real module would have used,
     * without creating them.
     *
     * Returning descriptors for files that do not exist is the honest shape
     * here: the signature promises an [ExportedFiles], and a caller that goes
     * on to read one gets a plain "no such file" rather than a surprise null.
     */
    @JvmStatic
    @JvmOverloads
    public fun writeAll(
        context: Context,
        report: LatencyReport,
        directory: File = defaultDirectory(context, report),
    ): ExportedFiles = ExportedFiles(
        html = File(directory, "report.html"),
        summaryCsv = File(directory, "report-summary.csv"),
        callsCsv = File(directory, "report-calls.csv"),
        json = File(directory, "report.json"),
    )

    @JvmStatic
    @JvmOverloads
    public fun share(
        context: Context,
        report: LatencyReport,
        chooserTitle: String = "Share latency report",
    ) {
        // No files to share, and no chooser worth opening onto nothing.
    }

    @JvmStatic
    @JvmOverloads
    public fun shareAsync(
        context: Context,
        report: LatencyReport,
        chooserTitle: String = "Share latency report",
        onError: (Throwable) -> Unit = { throw it },
    ) {
        // Nothing to render, so nothing can fail: onError is never called.
    }

    @JvmStatic
    public fun uriFor(context: Context, file: File): Uri = Uri.EMPTY

    @JvmStatic
    public fun authority(context: Context): String =
        context.packageName + ".latencyscope.fileprovider"

    @JvmStatic
    public fun defaultDirectory(context: Context, report: LatencyReport): File =
        File(File(context.cacheDir, "latencyscope"), report.session.id)
}

/** Mirror of the real [ExportedFiles]. */
public data class ExportedFiles(
    public val html: File,
    public val summaryCsv: File,
    public val callsCsv: File,
    public val json: File,
) {
    public val all: List<File> get() = listOf(html, summaryCsv, callsCsv, json)
}

/**
 * Mirror of the real [LatencyFormat].
 *
 * Formatting is kept real. It is a handful of string operations with no
 * session behind it, and an app that formats a duration of its own through
 * this helper should get the same text in both build types.
 */
public object LatencyFormat {

    @JvmStatic
    public fun duration(ms: Long): String = when {
        ms < MILLIS_PER_SECOND -> "$ms ms"
        else -> (ms / MILLIS_PER_SECOND).toString() + " s"
    }

    @JvmStatic
    public fun bytes(bytes: Long): String = "$bytes B"

    @JvmStatic
    public fun bytesOrUnknown(bytes: Long): String = if (bytes < 0) "—" else bytes(bytes)

    @JvmStatic
    public fun percent(ratio: Double): String = "0%"

    @JvmStatic
    public fun band(report: LatencyReport, ms: Long): LatencyBand = LatencyBand.FAST

    private const val MILLIS_PER_SECOND = 1_000L
}

/** Mirror of the real [LatencyBand]. */
public enum class LatencyBand(public val label: String) {
    FAST("fast"),
    WARN("borderline"),
    SLOW("slow"),
}

/** Mirror of the real [PhaseSlice]. */
public data class PhaseSlice(
    public val label: String,
    public val description: String,
    public val slot: Int,
    public val ms: Long,
) {
    public fun share(total: Long): Double = 0.0
}

/** Mirror of the real [PhaseBreakdown]. No phases were ever measured. */
public object PhaseBreakdown {

    @JvmStatic
    public fun of(phases: Phases): List<PhaseSlice> = emptyList()

    @JvmStatic
    public fun nonEmpty(phases: Phases): List<PhaseSlice> = emptyList()

    @JvmStatic
    public fun total(phases: Phases): Long = 0
}
