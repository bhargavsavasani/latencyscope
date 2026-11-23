package io.github.latencyscope.okhttp

import io.github.latencyscope.core.Clock
import io.github.latencyscope.core.LatencyScope
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Asynchronous calls hand events between the caller's thread and OkHttp's
 * dispatcher threads, which is where a non-volatile field would silently
 * produce a half-populated record.
 */
class AsyncCallTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        LatencyScope.reset()
        LatencyScope.setClockForTesting(RealClock())
        LatencyScope.initForTesting()
        client = OkHttpClient.Builder().installLatencyScope().build()
    }

    @After
    fun tearDown() {
        LatencyScope.reset()
        server.shutdown()
    }

    @Test
    fun enqueuedCallsAreFullyRecordedAcrossThreads() {
        val calls = 16
        repeat(calls) { server.enqueue(MockResponse().setBody("ok")) }
        LatencyScope.start()
        val latch = CountDownLatch(calls)

        repeat(calls) { i ->
            client.newCall(Request.Builder().url(server.url("/items/$i")).build())
                .enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) = latch.countDown()
                    override fun onResponse(call: Call, response: Response) {
                        response.use { it.body?.string() }
                        latch.countDown()
                    }
                })
        }
        assertTrue("async calls did not finish", latch.await(30, TimeUnit.SECONDS))

        val records = LatencyScope.stop()!!.records
        assertEquals(calls, records.size)
        records.forEach { record ->
            assertEquals(200, record.statusCode)
            assertEquals("/items/{id}", record.route)
            assertNotNull("thread name missing on $record", record.threadName)
            assertNotNull("server phase missing on $record", record.phases.serverMs)
            assertTrue("byte count missing on $record", record.responseBytes >= 0)
        }
    }

    private class RealClock : Clock {
        override fun elapsedMs(): Long = System.nanoTime() / 1_000_000
        override fun epochMs(): Long = System.currentTimeMillis()
    }
}
