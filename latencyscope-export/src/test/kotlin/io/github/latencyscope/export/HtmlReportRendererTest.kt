package io.github.latencyscope.export

import io.github.latencyscope.core.Marker
import io.github.latencyscope.core.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlReportRendererTest {

    @Test
    fun rendersASelfContainedDocument() {
        val html = LatencyExport.toHtml(report())

        assertTrue(html.startsWith("<!doctype html>"))
        assertTrue(html.trimEnd().endsWith("</html>"))
        assertTrue(html.contains("<style>"))
        assertTrue(html.contains("<script>"))
    }

    @Test
    fun makesNoExternalRequests() {
        val html = LatencyExport.toHtml(report(records = listOf(call(phases = fullPhases()))))

        // The whole point of this file is that it opens offline, from a phone's
        // Downloads folder, with no CDN and no font fetch.
        listOf("http://", "cdn.", "<link", "@import", "src=\"http").forEach { needle ->
            assertFalse("report should not reference $needle", html.contains(needle))
        }
        // The one allowed occurrence of https is inside recorded data.
        assertFalse(html.contains("https://cdn"))
    }

    @Test
    fun showsHeadlineNumbers() {
        val html = LatencyExport.toHtml(
            report(
                records = listOf(
                    call(route = "/users/{id}", totalMs = 100),
                    call(route = "/users/{id}", totalMs = 500),
                    call(route = "/orders", totalMs = 2_000, statusCode = 503),
                ),
            ),
        )

        assertTrue(html.contains("Summary"))
        assertTrue(html.contains("p50"))
        // The label alone is jargon; the tile has to say what it means.
        assertTrue(html.contains("half of all calls were faster"))
        assertTrue(html.contains("/users/{id}"))
        assertTrue(html.contains("/orders"))
        assertTrue(html.contains("checkout flow"))
    }

    @Test
    fun marksSlowCallsWithMoreThanColourAlone() {
        val html = LatencyExport.toHtml(report(records = listOf(call(totalMs = 5_000))))

        // A colour-blind reader, or a printed page, must still see the warning.
        assertTrue(html.contains("lat-slow"))
        assertTrue(html.contains(".lat-slow::before"))
    }

    @Test
    fun phaseSectionIsRenderedWhenAStackReportedPhases() {
        val html = LatencyExport.toHtml(report(records = listOf(call(phases = fullPhases(server = 90)))))

        assertTrue(html.contains("Where the time went"))
        assertTrue(html.contains("class=\"stack\""))
        assertTrue(html.contains("Server"))
        // Direct labels accompany the bar, because three of the five hues sit
        // below 3:1 contrast on the light surface.
        assertTrue(html.contains("phase-values"))
        assertTrue(html.contains("class=\"legend\""))
    }

    @Test
    fun phaseSectionExplainsItselfWhenNoStackCanReportPhases() {
        val html = LatencyExport.toHtml(report(records = listOf(call(source = Source.VOLLEY))))

        assertTrue(html.contains("No phase breakdown available"))
        assertFalse(html.contains("class=\"stack\""))
    }

    @Test
    fun timelineIncludesMarkers() {
        val html = LatencyExport.toHtml(
            report(
                records = listOf(call(startMs = 0), call(startMs = 1_200)),
                markers = listOf(Marker("cart opened", 1_700_000_001_000, 1_000)),
            ),
        )

        assertTrue(html.contains("wf-marker"))
        assertTrue(html.contains("cart opened"))
        assertEquals(2, Regex("class=\"wf-bar").findAll(html).count())
    }

    @Test
    fun failedCallsAreDistinguishedInTheTimeline() {
        val html = LatencyExport.toHtml(
            report(records = listOf(call(statusCode = 500), call(statusCode = 200))),
        )

        assertTrue(html.contains("wf-bar err"))
    }

    @Test
    fun truncationIsStatedPlainly() {
        val html = LatencyExport.toHtml(report(droppedCount = 4_382))

        assertTrue(html.contains("Partial data"))
        assertTrue(html.contains("4382"))
    }

    @Test
    fun histogramAppearsOnlyWithEnoughCalls() {
        val sparse = LatencyExport.toHtml(report(records = List(3) { call(startMs = it * 10L) }))
        assertTrue(sparse.contains("Needs at least"))

        val dense = LatencyExport.toHtml(
            report(records = List(20) { call(startMs = it * 10L, totalMs = 50L + it * 10) }),
        )
        assertTrue(dense.contains("class=\"hist\""))
    }

    @Test
    fun errorsAreGroupedAndListed() {
        val html = LatencyExport.toHtml(
            report(
                records = listOf(
                    call(route = "/a", statusCode = 500),
                    call(route = "/b", statusCode = 500),
                    call(route = "/c", statusCode = null, error = "SocketTimeoutException: timed out"),
                ),
            ),
        )

        assertTrue(html.contains("HTTP 500"))
        assertTrue(html.contains("SocketTimeoutException"))
    }

    @Test
    fun noFailuresSaysSoRatherThanShowingAnEmptyTable() {
        val html = LatencyExport.toHtml(report())

        assertTrue(html.contains("No failed calls in this session"))
    }

    @Test
    fun emptySessionRendersWithoutCrashing() {
        val html = LatencyExport.toHtml(report(records = emptyList()))

        assertTrue(html.contains("No calls were recorded"))
        assertTrue(html.trimEnd().endsWith("</html>"))
    }

    // ------------------------------------------------------------- escaping

    @Test
    fun hostileSessionNameCannotInjectMarkup() {
        val html = LatencyExport.toHtml(report(name = "<img src=x onerror=alert(1)>"))

        assertFalse(html.contains("<img src=x"))
        assertTrue(html.contains("&lt;img src=x onerror=alert(1)&gt;"))
    }

    @Test
    fun hostileUrlCannotCloseTheDataScriptElement() {
        val hostile = "https://api.example.com/p</script><script>alert(1)</script>"
        val html = LatencyExport.toHtml(report(records = listOf(call(url = hostile))))

        // Exactly two script elements: the JSON data block and the behaviour
        // script. A third would mean recorded data broke out.
        assertEquals(2, Regex("<script").findAll(html).count())
        assertEquals(2, Regex("</script>").findAll(html).count())
        assertFalse(html.contains("alert(1)</script>"))
    }

    @Test
    fun hostileRouteCannotBreakOutOfAnAttribute() {
        val html = LatencyExport.toHtml(
            report(records = listOf(call(route = "/x\" onmouseover=\"alert(1)"))),
        )

        assertFalse(html.contains("onmouseover=\"alert(1)\""))
        assertTrue(html.contains("&quot; onmouseover=&quot;"))
    }

    /**
     * A `data-tip` value is read with `getAttribute` and assigned to
     * `innerHTML`, so it is decoded twice. Escaping once would leave a live
     * tag after the first decode, which the second would then execute.
     */
    @Test
    fun hostileRouteCannotSurviveTheTooltipsSecondDecode() {
        val html = LatencyExport.toHtml(
            report(records = listOf(call(route = "/<img src=x onerror=alert(1)>"))),
        )

        val tip = tipValue(html, "wf-bar")
        assertFalse(decodeEntities(tip).contains("<img src=x"))
        assertTrue(tip.contains("&amp;lt;img src=x"))
    }

    @Test
    fun hostileMarkerLabelCannotSurviveTheTooltipsSecondDecode() {
        val hostile = "<img src=x onerror=alert(1)>"
        val html = LatencyExport.toHtml(
            report(records = listOf(call()), markers = listOf(Marker(hostile, 0L, 10L))),
        )

        val tip = tipValue(html, "wf-marker")
        assertFalse(decodeEntities(tip).contains("<img src=x"))
        assertTrue(tip.contains("&amp;lt;img src=x"))
    }

    /** The raw `data-tip` attribute of the first element carrying [cssClass]. */
    private fun tipValue(html: String, cssClass: String): String {
        val match = Regex("class=\"[^\"]*$cssClass[^\"]*\"[^>]*data-tip=\"([^\"]*)\"").find(html)
            ?: Regex("class=\"$cssClass\"[^>]*data-tip=\"([^\"]*)\"").find(html)
        assertTrue("no data-tip found for .$cssClass", match != null)
        return match!!.groupValues[1]
    }

    /** What the browser's attribute parser hands back to `innerHTML`. */
    private fun decodeEntities(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")

    // ----------------------------------------------------------- formatting

    @Test
    fun formatsDurationsForHumans() {
        assertEquals("0 ms", HtmlReportRenderer.formatMs(0))
        assertEquals("999 ms", HtmlReportRenderer.formatMs(999))
        assertEquals("1 s", HtmlReportRenderer.formatMs(1_000))
        assertEquals("1.5 s", HtmlReportRenderer.formatMs(1_500))
        assertEquals("1m 5s", HtmlReportRenderer.formatMs(65_000))
    }

    @Test
    fun formatsBytesForHumans() {
        assertEquals("0 B", HtmlReportRenderer.formatBytes(0))
        assertEquals("512 B", HtmlReportRenderer.formatBytes(512))
        assertEquals("1 kB", HtmlReportRenderer.formatBytes(1_024))
        assertEquals("1 MB", HtmlReportRenderer.formatBytes(1_048_576))
    }

    @Test
    fun formatsSmallPercentagesWithoutClaimingZero() {
        assertEquals("0%", HtmlReportRenderer.formatPercent(0.0))
        assertEquals("<1%", HtmlReportRenderer.formatPercent(0.004))
        assertEquals("50%", HtmlReportRenderer.formatPercent(0.5))
    }

    @Test
    fun escapesEveryDangerousCharacter() {
        assertEquals(
            "&amp;&lt;&gt;&quot;&#39;",
            HtmlReportRenderer.esc("&<>\"'"),
        )
        assertEquals("plain text", HtmlReportRenderer.esc("plain text"))
    }

    /**
     * A report gets forwarded — to a backend team, into a ticket, at a
     * manager. Every term it uses has to explain itself, because a number
     * nobody can interpret is a number nobody acts on.
     */
    @Test
    fun everyTechnicalTermIsExplainedInTheReport() {
        val html = LatencyExport.toHtml(report(records = listOf(call(totalMs = 100))))

        listOf("p50", "p75", "p90", "p95", "p99").forEach { percentile ->
            assertTrue("$percentile is never explained", html.contains("<b>$percentile</b>"))
        }
        assertTrue(html.contains("1 in 20 was slower"))
        assertTrue(html.contains("1 in 100 was slower"))

        // Why percentiles at all, rather than an average.
        assertTrue(html.contains("average"))

        // The phase names come straight from PhaseBreakdown, so the glossary
        // cannot drift from the charts.
        listOf("Wait", "Connect", "Request", "Server", "Response").forEach { phase ->
            assertTrue("the $phase phase is never explained", html.contains("<b>$phase</b>"))
        }

        listOf("Route", "Failed / error rate", "Measured download", "By network").forEach { term ->
            assertTrue("$term is never explained", html.contains(term))
        }
    }

    /** Hovering a percentile column should say what it means. */
    @Test
    fun percentileColumnHeadersCarryTheirOwnExplanation() {
        val html = LatencyExport.toHtml(report(records = listOf(call(totalMs = 100))))

        assertTrue(html.contains("data-key=\"p95\" data-tip="))
    }
}
