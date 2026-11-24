package io.github.latencyscope.export

import io.github.latencyscope.core.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvWriterTest {

    @Test
    fun summaryHasOneRowPerRoutePlusAHeader() {
        val csv = LatencyExport.toCsvSummary(
            report(
                records = listOf(
                    call(route = "/users/{id}", totalMs = 100),
                    call(route = "/users/{id}", totalMs = 300),
                    call(route = "/orders", totalMs = 50),
                ),
            ),
        )
        val rows = csv.trim().split("\r\n")

        assertEquals(3, rows.size)
        assertTrue(rows[0].startsWith("method,route,host,sources,count"))
        assertTrue(rows.any { it.startsWith("GET,/users/{id}") })
    }

    @Test
    fun callsCsvHasOneRowPerCall() {
        val csv = LatencyExport.toCsvCalls(
            report(records = List(5) { call(startMs = it * 10L) }),
        )

        assertEquals(6, csv.trim().split("\r\n").size)
    }

    @Test
    fun usesCrlfLineEndingsPerRfc4180() {
        val csv = LatencyExport.toCsvSummary(report())

        assertTrue(csv.endsWith("\r\n"))
    }

    @Test
    fun quotesCommasQuotesAndNewlines() {
        val csv = LatencyExport.toCsvCalls(
            report(
                records = listOf(
                    call(
                        url = "https://api.example.com/search?q=a,b",
                        error = "broke: said \"no\"\nthen gave up",
                    ),
                ),
            ),
        )

        assertTrue("comma not quoted", csv.contains("\"https://api.example.com/search?q=a,b\""))
        // A literal quote is doubled, per RFC 4180.
        assertTrue("quote not doubled", csv.contains("\"\"no\"\""))
    }

    @Test
    fun unknownByteCountsAreBlankNotZero() {
        val csv = LatencyExport.toCsvCalls(
            report(records = listOf(call(requestBytes = -1, responseBytes = 512))),
        )
        val row = csv.trim().split("\r\n")[1]
        val header = csv.trim().split("\r\n")[0].split(",")
        val cells = row.split(",")

        // A blank says "this stack could not tell us"; a 0 would be averaged in.
        assertEquals("", cells[header.indexOf("request_bytes")])
        assertEquals("512", cells[header.indexOf("response_bytes")])
    }

    @Test
    fun phaseColumnsAreBlankWhenTheStackCannotReportThem() {
        val csv = LatencyExport.toCsvCalls(
            report(records = listOf(call(source = Source.VOLLEY))),
        )
        val header = csv.trim().split("\r\n")[0].split(",")
        val cells = csv.trim().split("\r\n")[1].split(",")

        assertEquals("", cells[header.indexOf("dns_ms")])
        assertEquals("", cells[header.indexOf("server_ms")])
    }

    @Test
    fun phaseAveragesAppearInTheSummary() {
        val csv = LatencyExport.toCsvSummary(
            report(records = listOf(call(phases = fullPhases(server = 90)))),
        )
        val header = csv.trim().split("\r\n")[0].split(",")
        val cells = csv.trim().split("\r\n")[1].split(",")

        assertEquals("90", cells[header.indexOf("avg_server_ms")])
        // Request headers (1) + body (3) are summed into one column.
        assertEquals("4", cells[header.indexOf("avg_request_ms")])
    }

    /**
     * These files are opened in Excel, where a leading `=` means "run this".
     */
    @Test
    fun formulaLikeCellsCannotExecuteInASpreadsheet() {
        val csv = LatencyExport.toCsvCalls(
            report(records = listOf(call(error = "=cmd|'/c calc'!A1"))),
        )
        val header = csv.trim().split("\r\n")[0].split(",")
        val cells = splitRow(csv.trim().split("\r\n")[1])

        assertEquals("'=cmd|'/c calc'!A1", cells[header.indexOf("error")])
    }

    @Test
    fun negativeNumbersStayNumbers() {
        val csv = LatencyExport.toCsvCalls(
            report(records = listOf(call(startMs = -40))),
        )
        val header = csv.trim().split("\r\n")[0].split(",")
        val cells = splitRow(csv.trim().split("\r\n")[1])

        assertEquals("-40", cells[header.indexOf("start_ms")])
    }

    /** Naive split is fine here: the fixtures hold no quoted commas. */
    private fun splitRow(row: String): List<String> = row.split(",")

    @Test
    fun emptyReportStillWritesAHeader() {
        val csv = LatencyExport.toCsvSummary(report(records = emptyList()))

        assertEquals(1, csv.trim().split("\r\n").size)
    }
}
