package io.github.latencyscope.export

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportJsonTest {

    @Test
    fun producesParseableJsonWithTheExpectedShape() {
        val json = JSONObject(
            LatencyExport.toJson(
                report(
                    records = listOf(
                        call(route = "/users/{id}", totalMs = 100, phases = fullPhases()),
                        call(route = "/orders", totalMs = 300, statusCode = 500),
                    ),
                ),
            ),
        )

        assertEquals(1, json.getInt("schemaVersion"))
        assertEquals("checkout flow", json.getJSONObject("session").getString("name"))
        assertEquals(2, json.getJSONObject("overall").getInt("count"))
        assertEquals(2, json.getJSONArray("routes").length())
        assertEquals(2, json.getJSONArray("calls").length())
        assertEquals(300, json.getJSONObject("thresholds").getInt("warnMs"))
    }

    @Test
    fun absentPhasesAreNullNotZero() {
        val json = JSONObject(LatencyExport.toJson(report(records = listOf(call(source = io.github.latencyscope.core.Source.KTOR)))))
        val phases = json.getJSONArray("calls").getJSONObject(0).getJSONObject("phases")

        // "Not measured" and "took no time" are different facts and the export
        // must not collapse them.
        assertTrue(phases.isNull("dnsMs"))
        assertTrue(phases.isNull("serverMs"))
    }

    @Test
    fun deviceAndMarkersSurvive() {
        val json = JSONObject(
            LatencyExport.toJson(
                report(
                    markers = listOf(
                        io.github.latencyscope.core.Marker("cart opened", 1_700_000_001_000, 1_000),
                    ),
                ),
            ),
        )
        val session = json.getJSONObject("session")

        assertEquals("Pixel 8", session.getJSONObject("device").getString("model"))
        assertEquals("cart opened", session.getJSONArray("markers").getJSONObject(0).getString("label"))
        assertEquals(1_000, session.getJSONArray("markers").getJSONObject(0).getLong("atMs"))
    }

    @Test
    fun scriptClosingTagInAUrlCannotEscapeTheJson() {
        val hostile = "https://api.example.com/x</script><img src=x onerror=alert(1)>"
        val json = LatencyExport.toJson(report(records = listOf(call(url = hostile))))

        // The raw sequence must not appear, or embedding this in the HTML
        // report would end the script element early.
        assertFalse(json.contains("</script>"))
        assertTrue(json.contains("\\u003c"))
        // ...and a parser still reads back the original string.
        val parsed = JSONObject(json).getJSONArray("calls").getJSONObject(0).getString("url")
        assertEquals(hostile, parsed)
    }

    @Test
    fun lineSeparatorsAreEscapedForJavaScript() {
        val json = LatencyExport.toJson(
            report(records = listOf(call(error = "broke here"))),
        )

        // Legal in JSON, a syntax error in JavaScript source.
        assertFalse(json.contains(" "))
        assertTrue(json.contains("\\u2028"))
    }

    @Test
    fun quotesAndControlCharactersRoundTrip() {
        val nasty = "he said \"hi\"\n\tand\\left"
        val parsed = JSONObject(LatencyExport.toJson(report(records = listOf(call(error = nasty)))))
            .getJSONArray("calls").getJSONObject(0).getString("error")

        assertEquals(nasty, parsed)
    }

    @Test
    fun emptySessionStillProducesValidJson() {
        val json = JSONObject(LatencyExport.toJson(report(records = emptyList())))

        assertEquals(0, json.getJSONObject("overall").getInt("count"))
        assertEquals(0, json.getJSONArray("routes").length())
        assertNull(json.optJSONObject("missing"))
    }
}
