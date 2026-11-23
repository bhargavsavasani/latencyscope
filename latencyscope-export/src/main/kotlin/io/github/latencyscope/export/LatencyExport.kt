package io.github.latencyscope.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.latencyscope.core.LatencyReport
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Turns a [LatencyReport] into files a human or a spreadsheet can read.
 *
 * ```kotlin
 * val report = LatencyScope.report(LatencyScope.stop()) ?: return
 * LatencyExport.share(context, report)          // write everything and share
 * ```
 *
 * All four renderers run off the same aggregated report, so the HTML page, the
 * CSVs and the JSON can never disagree about a number.
 */
public object LatencyExport {

    /** A self-contained HTML page: no network requests, no external assets. */
    @JvmStatic
    public fun toHtml(report: LatencyReport): String = HtmlReportRenderer.render(report)

    /** One row per call, with every phase as its own column. */
    @JvmStatic
    public fun toCsvCalls(report: LatencyReport): String = CsvWriter.calls(report)

    /** One row per route: counts, percentiles and average phase times. */
    @JvmStatic
    public fun toCsvSummary(report: LatencyReport): String = CsvWriter.summary(report)

    /** The whole report as JSON — the same document embedded in the HTML. */
    @JvmStatic
    public fun toJson(report: LatencyReport): String = ReportJson.encode(report)

    /**
     * Writes all four files into the app's cache and returns them.
     *
     * Cache rather than external storage: no permission is involved, the
     * files are shareable through the bundled [FileProvider], and the system
     * can reclaim them. Files land in `cacheDir/latencyscope/<session>/`.
     */
    @JvmStatic
    @JvmOverloads
    public fun writeAll(
        context: Context,
        report: LatencyReport,
        directory: File = defaultDirectory(context, report),
    ): ExportedFiles {
        directory.mkdirs()
        val stem = fileStem(report)

        val html = File(directory, "$stem.html").apply { writeText(toHtml(report)) }
        val summary = File(directory, "$stem-summary.csv").apply { writeText(toCsvSummary(report)) }
        val calls = File(directory, "$stem-calls.csv").apply { writeText(toCsvCalls(report)) }
        val json = File(directory, "$stem.json").apply { writeText(toJson(report)) }

        return ExportedFiles(html = html, summaryCsv = summary, callsCsv = calls, json = json)
    }

    /**
     * Writes the report and opens the system share sheet with every file
     * attached.
     *
     * @param chooserTitle title for the chooser, defaulting to the session name.
     */
    @JvmStatic
    @JvmOverloads
    public fun share(
        context: Context,
        report: LatencyReport,
        chooserTitle: String = "Share latency report",
    ) {
        val files = writeAll(context, report)
        val uris = ArrayList(files.all.map { file -> uriFor(context, file) })

        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, report.session.name + " — latency report")
            putExtra(Intent.EXTRA_TEXT, summaryLine(report))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(intent, chooserTitle).apply {
            // The chooser is usually launched from a non-Activity context in a
            // debug menu; without this it throws rather than opening.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    /** A shareable `content://` URI for a file written by [writeAll]. */
    @JvmStatic
    public fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, authority(context), file)

    /** The bundled provider's authority, derived from the consumer's app id. */
    @JvmStatic
    public fun authority(context: Context): String =
        context.packageName + ".latencyscope.fileprovider"

    @JvmStatic
    public fun defaultDirectory(context: Context, report: LatencyReport): File =
        File(File(context.cacheDir, "latencyscope"), report.session.id)

    private fun summaryLine(report: LatencyReport): String {
        val overall = report.overall
        return buildString {
            append(report.session.name).append(": ")
            append(overall.count).append(" calls, ")
            append(overall.errorCount).append(" failed, ")
            append("p50 ").append(HtmlReportRenderer.formatMs(overall.p50Ms))
            append(", p95 ").append(HtmlReportRenderer.formatMs(overall.p95Ms))
            report.slowestRoute?.let { append(". Slowest: ").append(it.key) }
        }
    }

    private fun fileStem(report: LatencyReport): String {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            .format(Date(report.session.startedAtEpochMs))
        return "latency-" + slug(report.session.name) + "-" + timestamp
    }

    /** Session names are user-supplied, so they are not trusted as filenames. */
    private fun slug(name: String): String {
        val cleaned = name.lowercase(Locale.US)
            .map { char -> if (char.isLetterOrDigit()) char else '-' }
            .joinToString("")
            .trim('-')
            .replace(Regex("-+"), "-")
        return if (cleaned.isEmpty()) "session" else cleaned.take(MAX_SLUG_LENGTH).trim('-')
    }

    private const val MAX_SLUG_LENGTH = 40
}

/** The files produced by [LatencyExport.writeAll]. */
public data class ExportedFiles(
    public val html: File,
    public val summaryCsv: File,
    public val callsCsv: File,
    public val json: File,
) {
    public val all: List<File> get() = listOf(html, summaryCsv, callsCsv, json)
}
