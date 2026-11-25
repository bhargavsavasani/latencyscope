package io.github.latencyscope.ui.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithContentDescription
import io.github.latencyscope.core.Aggregator
import io.github.latencyscope.core.ApiCallRecord
import io.github.latencyscope.core.DeviceInfo
import io.github.latencyscope.core.LatencyReport
import io.github.latencyscope.core.LatencySession
import io.github.latencyscope.core.Phases
import io.github.latencyscope.core.Source
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// A tall window so the lazy list composes the rows under the summary tiles;
// the default Robolectric screen cuts off below the filter field.
@Config(sdk = [34], qualifiers = "w480dp-h2400dp")
class LatencyReportScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun showsTheSessionAndItsHeadlineNumbers() {
        compose.setContent {
            LatencyReportScreen(report(records = listOf(call(totalMs = 150))))
        }

        compose.onNodeWithText("checkout flow").assertIsDisplayed()
        compose.onNodeWithText("p50").assertIsDisplayed()
        // p50, p95 and p99 are all this one call's duration.
        assertTrue(compose.onAllNodesWithText("150 ms").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun tabsCarryTheirCountsAndSwitchTheList() {
        compose.setContent {
            LatencyReportScreen(
                report(
                    records = listOf(
                        call(route = "/users/{id}"),
                        call(route = "/users/{id}", startMs = 10),
                        call(route = "/orders", startMs = 20),
                    ),
                ),
            )
        }

        compose.onNodeWithText("Routes 2").assertIsDisplayed()
        compose.onNodeWithText("Calls 3").performClick()

        // Three calls across two routes: the call list repeats /users/{id}.
        assertTrue(compose.onAllNodesWithText("/users/{id}").fetchSemanticsNodes().size >= 2)
    }

    @Test
    fun filteringNarrowsTheList() {
        compose.setContent {
            LatencyReportScreen(
                report(records = listOf(call(route = "/users/{id}"), call(route = "/orders", startMs = 5))),
            )
        }

        compose.onNodeWithContentDescription("Filter routes").performTextInput("orders")

        compose.onNodeWithText("/orders").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("/users/{id}").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun expandingARouteRevealsItsPhaseBreakdown() {
        compose.setContent {
            LatencyReportScreen(report(records = listOf(call(phases = fullPhases(server = 90)))))
        }

        compose.onNode(hasText("/users/{id}", substring = true)).performClick()

        // Labels and durations, not colour alone — three of the five hues are
        // below 3:1 contrast on a light surface.
        compose.onNodeWithText("Server").assertIsDisplayed()
        compose.onNodeWithText("Average time per phase").assertIsDisplayed()
        compose.onNodeWithContentDescription("Server 90 ms").assertIsDisplayed()
    }

    @Test
    fun aRouteWithoutPhaseDataSaysWhyRatherThanShowingAnEmptyBar() {
        compose.setContent {
            LatencyReportScreen(report(records = listOf(call(source = Source.VOLLEY))))
        }

        compose.onNode(hasText("/users/{id}", substring = true)).performClick()

        compose.onNode(hasText("reports total time only", substring = true)).assertIsDisplayed()
    }

    @Test
    fun slowLatenciesAreMarkedWithMoreThanColour() {
        compose.setContent {
            LatencyReportScreen(report(records = listOf(call(totalMs = 4_000))))
        }

        compose.onNodeWithText("! 4 s").assertIsDisplayed()
        compose.onNodeWithContentDescription("4 s, slow").assertIsDisplayed()
    }

    @Test
    fun truncationIsStatedPlainly() {
        compose.setContent { LatencyReportScreen(report(droppedCount = 4_382)) }

        compose.onNodeWithText("Partial data").assertIsDisplayed()
        compose.onNode(hasText("4382", substring = true)).assertIsDisplayed()
    }

    @Test
    fun failuresTabGroupsErrors() {
        compose.setContent {
            LatencyReportScreen(
                report(
                    records = listOf(
                        call(route = "/a", statusCode = 500),
                        call(route = "/b", statusCode = 500, startMs = 5),
                    ),
                ),
            )
        }

        compose.onNodeWithText("Failures 2").performClick()

        compose.onNodeWithText("HTTP 500").assertIsDisplayed()
        compose.onNodeWithText("2 calls").assertIsDisplayed()
    }

    @Test
    fun shareIsOfferedOnlyWhenAHandlerIsGiven() {
        var shared = false
        compose.setContent {
            LatencyReportScreen(report(), onShare = { shared = true })
        }

        compose.onNodeWithContentDescription("Share report").performClick()

        assertTrue(shared)
    }

    @Test
    fun anEmptyFilterResultSaysSo() {
        compose.setContent { LatencyReportScreen(report()) }

        compose.onNodeWithContentDescription("Filter routes").performTextInput("nothing here")

        compose.onNodeWithText("No routes match this filter.").assertIsDisplayed()
    }

    // ----------------------------------------------------------- fixtures

    private fun call(
        route: String = "/users/{id}",
        method: String = "GET",
        totalMs: Long = 120,
        statusCode: Int? = 200,
        startMs: Long = 0,
        phases: Phases = Phases.EMPTY,
        source: Source = Source.OKHTTP,
    ) = ApiCallRecord(
        id = "$method-$route-$startMs-$totalMs",
        source = source,
        url = "https://api.example.com$route",
        route = route,
        host = "api.example.com",
        method = method,
        statusCode = statusCode,
        startedAtEpochMs = 1_700_000_000_000 + startMs,
        startedAtElapsedMs = startMs,
        totalMs = totalMs,
        phases = phases,
    )

    private fun fullPhases(server: Long = 90) = Phases(
        queueMs = 2,
        dnsMs = 8,
        connectMs = 12,
        tlsMs = 20,
        requestHeadersMs = 1,
        serverMs = server,
        responseHeadersMs = 1,
        responseBodyMs = 14,
    )

    private fun report(
        records: List<ApiCallRecord> = listOf(call()),
        droppedCount: Long = 0,
    ): LatencyReport = Aggregator.aggregate(
        session = LatencySession(
            id = "session-1",
            name = "checkout flow",
            startedAtEpochMs = 1_700_000_000_000,
            endedAtEpochMs = 1_700_000_005_000,
            durationMs = 5_000,
            device = DeviceInfo(manufacturer = "Google", model = "Pixel 8", sdkInt = 34),
            records = records,
            droppedCount = droppedCount,
        ),
        generatedAtEpochMs = 1_700_000_005_000,
    )
}
