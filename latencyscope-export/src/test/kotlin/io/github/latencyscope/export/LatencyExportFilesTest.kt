package io.github.latencyscope.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LatencyExportFilesTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun writesAllFourFilesIntoTheAppCache() {
        val files = LatencyExport.writeAll(context, report(records = listOf(call())))

        assertTrue(files.html.exists())
        assertTrue(files.summaryCsv.exists())
        assertTrue(files.callsCsv.exists())
        assertTrue(files.json.exists())
        assertEquals(4, files.all.size)
        files.all.forEach { file ->
            assertTrue("${file.name} is empty", file.length() > 0)
            // Cache, not external storage: no permission, and the system can
            // reclaim it.
            assertTrue(file.absolutePath.contains("cache"))
            assertTrue(file.absolutePath.contains("latencyscope"))
        }
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
}
