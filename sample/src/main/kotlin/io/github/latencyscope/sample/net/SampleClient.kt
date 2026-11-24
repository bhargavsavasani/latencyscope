package io.github.latencyscope.sample.net

import io.github.latencyscope.sample.CallResult
import io.github.latencyscope.sample.SampleTab

/**
 * One HTTP stack, instrumented the way its own LatencyScope module documents.
 *
 * Each implementation does the *minimum* a real app does — build a client once,
 * issue a GET, drain the body — because anything extra would show up in the
 * report and be mistaken for library overhead.
 */
internal interface SampleClient {

    val tab: SampleTab

    /**
     * Issues a GET and drains the response.
     *
     * Never throws for a network failure: a failed call is a result the report
     * is meant to contain, so it comes back as [CallResult.error] rather than
     * as an exception that would stop a burst halfway through.
     */
    suspend fun get(url: String, timeoutMs: Long): CallResult

    fun close() {}
}
