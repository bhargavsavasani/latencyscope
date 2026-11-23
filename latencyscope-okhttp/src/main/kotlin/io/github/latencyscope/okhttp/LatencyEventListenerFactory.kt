package io.github.latencyscope.okhttp

import okhttp3.Call
import okhttp3.EventListener

/**
 * Creates one [LatencyCallListener] per call.
 *
 * @param delegate an existing factory to keep running. OkHttp allows a single
 *   event listener, so installing LatencyScope would otherwise silently
 *   uninstall whatever the app already had — Chucker, a tracing SDK, logging.
 *   Pass it here and both receive every event.
 */
public class LatencyEventListenerFactory @JvmOverloads constructor(
    private val delegate: EventListener.Factory? = null,
) : EventListener.Factory {

    override fun create(call: Call): EventListener =
        LatencyCallListener(delegate?.create(call))
}
