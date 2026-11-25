package io.github.latencyscope.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LatencyExportFilesTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * There is exactly one place reports go:
     * `Android/data/<app>/files/report/<session name>/`.
     *
     * External app storage, so the file can be reached with `adb pull`, over
     * USB or from the Files app, with no permission. Internal cache would be
     * invisible from a desktop and the system may delete it at any moment —
     * the wrong promise for a file someone is about to attach to a ticket.
     */
    @Test
    fun writesAllFourFilesIntoTheExternalReportDirectory() {
        val files = LatencyExport.writeAll(
            context,
            report(name = "checkout flow", records = listOf(call())),
        )

        assertTrue(files.html.exists())
        assertTrue(files.summaryCsv.exists())
        assertTrue(files.callsCsv.exists())
        assertTrue(files.json.exists())
        assertEquals(4, files.all.size)

        val expected = File(
            File(context.getExternalFilesDir(null), "report"),
            "checkout-flow",
        )
        files.all.forEach { file ->
            assertTrue("${file.name} is empty", file.length() > 0)
            assertEquals(
                "every file belongs in the session's own folder",
                expected.absolutePath,
                file.parentFile?.absolutePath,
            )
        }
        assertTrue("never the cache", files.html.absolutePath.contains("/cache/").not())
    }

    /**
     * Running the same flow twice must not throw the first report away. The
     * folder is shared; the files inside are already stamped with the time the
     * session started.
     */
    @Test
    fun aSecondSessionWithTheSameNameKeepsBothReports() {
        val first = LatencyExport.writeAll(context, report(name = "checkout", startedAtEpochMs = 1_700_000_000_000))
        val second = LatencyExport.writeAll(context, report(name = "checkout", startedAtEpochMs = 1_700_000_060_000))

        assertEquals(first.html.parentFile, second.html.parentFile)
        assertTrue("the two runs must not share a filename", first.html.name != second.html.name)
        assertTrue(first.html.exists())
        assertTrue(second.html.exists())
    }

    @Test
    fun fileNamesAreDerivedSafelyFromTheSessionName() {
        val files = LatencyExport.writeAll(
            context,
            report(name = "Checkout / flow #2: \"fast\"?"),
        )

        // A session name is user input and must not steer the path.
        assertTrue(files.html.name, files.html.name.startsWith("latency-checkout-flow-2-fast-"))
        assertTrue(files.html.name.endsWith(".html"))
        listOf("/", "\\", "\"", "?", ":").forEach { bad ->
            assertTrue("name contains $bad", !files.html.name.contains(bad))
        }
    }

    @Test
    fun anEmptySessionNameStillProducesAUsableFileName() {
        val files = LatencyExport.writeAll(context, report(name = "///"))

        assertTrue(files.html.name, files.html.name.startsWith("latency-session-"))
    }

    @Test
    fun writtenFilesMatchTheInMemoryRenderers() {
        val report = report(records = listOf(call(phases = fullPhases())))
        val files = LatencyExport.writeAll(context, report)

        assertEquals(LatencyExport.toHtml(report), files.html.readText())
        assertEquals(LatencyExport.toCsvSummary(report), files.summaryCsv.readText())
        assertEquals(LatencyExport.toCsvCalls(report), files.callsCsv.readText())
        assertEquals(LatencyExport.toJson(report), files.json.readText())
    }

    @Test
    fun theProviderAuthorityIsNamespacedToTheConsumerApp() {
        val authority = LatencyExport.authority(context)

        assertTrue(authority.endsWith(".latencyscope.fileprovider"))
        assertTrue(authority.startsWith(context.packageName))
    }

    @Test
    fun writtenFilesAreShareableThroughTheBundledProvider() {
        val files = LatencyExport.writeAll(context, report())

        val uri = LatencyExport.uriFor(context, files.html)

        assertEquals("content", uri.scheme)
        assertEquals(LatencyExport.authority(context), uri.authority)
    }

    /**
     * Every written path goes to logcat.
     *
     * The report lives on the device and the person who wants it is at a
     * desktop. Printing only the directory, or only the HTML, leaves them
     * reconstructing filenames by hand — and the CSVs and JSON are the ones
     * that get opened in a spreadsheet or a script.
     */
    @Test
    fun everyWrittenPathIsPrintedToLogcat() {
        ShadowLog.clear()

        val files = LatencyExport.writeAll(context, report(name = "checkout"))

        val logged = ShadowLog.getLogsForTag("LatencyScope").joinToString("\n") { it.msg }
        files.all.forEach { file ->
            assertTrue("${file.name} was never logged:\n$logged", logged.contains(file.absolutePath))
        }
        assertTrue("the folder itself is worth having", logged.contains(files.html.parentFile!!.absolutePath))
        assertTrue("say how to fetch it", logged.contains("adb pull"))
        assertTrue("name the session being reported", logged.contains("checkout"))
    }
}
