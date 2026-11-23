package io.github.latencyscope.ktor

import io.github.latencyscope.core.Clock
import io.github.latencyscope.core.LatencyScope
import io.github.latencyscope.core.Source
import io.github.latencyscope.okhttp.installLatencyScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Exercises the plugin against engines that really open sockets. */
class KtorRealEngineTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        LatencyScope.reset()
        LatencyScope.setClockForTesting(RealClock())
        LatencyScope.initForTesting()
    }

    @After
    fun tearDown() {
        LatencyScope.reset()
        server.shutdown()
    }

    @Test
    fun cioEngineCallIsRecordedWithRealTiming() = runBlocking {
        server.enqueue(
            MockResponse().setBody("slow").setHeadersDelay(300, TimeUnit.MILLISECONDS),
        )
        val client = HttpClient(CIO) { install(LatencyScopeKtorPlugin) }
        LatencyScope.start()

        val body = client.get(server.url("/slow").toString()).bodyAsText()

        assertEquals("slow", body)
        val record = LatencyScope.stop()!!.records.single()
        assertEquals(Source.KTOR, record.source)
        assertEquals(200, record.statusCode)
        assertTrue("totalMs was ${record.totalMs}", record.totalMs >= 250)
        assertNotNull(record.protocol)
        client.close()
    }

    @Test
    fun ktorOnOkHttpWithDeferToEngineRecordsExactlyOnceWithFullPhases() = runBlocking {
        server.enqueue(MockResponse().setBody("hello"))
        val okHttpClient = OkHttpClient.Builder().installLatencyScope().build()
        val client = HttpClient(OkHttp) {
            engine { preconfigured = okHttpClient }
            install(LatencyScopeKtorPlugin) { deferToEngine = true }
        }
        LatencyScope.start()

        client.get(server.url("/users/42").toString()).bodyAsText()

        // Both layers see this call. deferToEngine hands it to the one that can
        // report a phase breakdown, instead of logging it twice.
        val records = LatencyScope.stop()!!.records
        assertEquals(1, records.size)
        assertEquals(Source.OKHTTP, records.single().source)
        assertNotNull(records.single().phases.serverMs)
        client.close()
    }

    @Test
    fun ktorOnOkHttpWithoutDeferToEngineRecordsBothLayers() = runBlocking {
        server.enqueue(MockResponse().setBody("hello"))
        val okHttpClient = OkHttpClient.Builder().installLatencyScope().build()
        val client = HttpClient(OkHttp) {
            engine { preconfigured = okHttpClient }
            install(LatencyScopeKtorPlugin)
        }
        LatencyScope.start()

        client.get(server.url("/users/42").toString()).bodyAsText()

        // Documents the duplicate the flag exists to prevent: visible in the
        // report as two rows from two sources, never silently merged.
        val records = LatencyScope.stop()!!.records
        assertEquals(2, records.size)
        assertEquals(setOf(Source.KTOR, Source.OKHTTP), records.map { it.source }.toSet())
        client.close()
    }

    private class RealClock : Clock {
        override fun elapsedMs(): Long = System.nanoTime() / 1_000_000
        override fun epochMs(): Long = System.currentTimeMillis()
    }
}
