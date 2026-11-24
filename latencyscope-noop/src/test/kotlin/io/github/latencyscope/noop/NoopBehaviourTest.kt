package io.github.latencyscope.noop

import com.android.volley.toolbox.HurlStack
import io.github.latencyscope.core.ApiCallRecord
import io.github.latencyscope.core.LatencyConfig
import io.github.latencyscope.core.LatencyScope
import io.github.latencyscope.core.Phases
import io.github.latencyscope.core.RawCall
import io.github.latencyscope.core.Source
import io.github.latencyscope.okhttp.LatencyEventListenerFactory
import io.github.latencyscope.volley.LatencyVolley
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The no-op's behavioural contract.
 *
 * `apiCheck` proves the *shape* matches the real modules. These prove the
 * other half: that the shape is backed by nothing, and that the few things
 * which must still work — running a tracked block, forwarding to a delegate
 * listener — actually do.
 */
class NoopBehaviourTest {

    @Test
    fun neverRecords() {
        LatencyScope.initForTesting(LatencyConfig())
        LatencyScope.start("a flow")

        assertFalse("a no-op must never claim to be recording", LatencyScope.isRecording())
        assertNull(LatencyScope.stop())
        assertNull(LatencyScope.currentSession())
        assertNull(LatencyScope.lastSession())
        assertNull(LatencyScope.report())
    }

    @Test
    fun initIsNeverRequiredAndNeverRecords() {
        assertFalse(LatencyScope.isInitialized())
        LatencyScope.mark("still nothing")
        LatencyScope.record(record())
        LatencyScope.submit(rawCall())

        assertNull(LatencyScope.report())
    }

    /** The measurement is optional; the caller's work is not. */
    @Test
    fun trackStillRunsTheBlockAndReturnsItsValue() {
        var ran = false
        val result = LatencyScope.track("https://api.example.com/users") {
            ran = true
            "payload"
        }

        assertTrue(ran)
        assertEquals("payload", result)
    }

    @Test
    fun trackLetsTheBlocksFailurePropagate() {
        val thrown = runCatching {
            LatencyScope.track<Unit>("https://api.example.com/users") { error("boom") }
        }.exceptionOrNull()

        assertNotNull("swallowing the caller's exception would change behaviour", thrown)
        assertEquals("boom", thrown?.message)
    }

    @Test
    fun aHandleFromStartStopsNothing() {
        val handle = LatencyScope.start("a flow")

        assertNull(handle.stop())
    }

    /**
     * A client built with the no-op must behave exactly as one built without
     * LatencyScope at all, including still driving a listener the app installed
     * itself.
     */
    @Test
    fun okHttpListenerStillForwardsToTheAppsOwnListener() {
        val seen = mutableListOf<String>()
        val appListener = object : EventListener() {
            override fun callStart(call: Call) {
                seen += "callStart"
            }

            override fun callFailed(call: Call, ioe: IOException) {
                seen += "callFailed"
            }
        }

        val listener = LatencyEventListenerFactory { appListener }
            .create(fakeCall())

        listener.callStart(fakeCall())
        listener.callFailed(fakeCall(), IOException("nope"))

        assertEquals(listOf("callStart", "callFailed"), seen)
    }

    /** No wrapper means no extra virtual call on Volley's request path. */
    @Test
    fun volleyStackIsTheDelegateItself() {
        val stack = HurlStack()

        assertSame(stack, LatencyVolley.stack(stack))
    }

    private fun fakeCall(): Call = OkHttpClient().newCall(
        Request.Builder().url("https://api.example.com/users").build(),
    )

    private fun rawCall(): RawCall = RawCall(
        source = Source.MANUAL,
        url = "https://api.example.com/users",
        method = "GET",
        statusCode = 200,
        startedAtEpochMs = 0,
        startedAtElapsedMs = 0,
        totalMs = 1,
    )

    private fun record(): ApiCallRecord = ApiCallRecord(
        id = "1",
        source = Source.MANUAL,
        url = "https://api.example.com/users",
        route = "/users",
        host = "api.example.com",
        method = "GET",
        statusCode = 200,
        startedAtEpochMs = 0,
        startedAtElapsedMs = 0,
        totalMs = 1,
        phases = Phases.EMPTY,
    )
}
