package io.github.latencyscope.okhttp

import okhttp3.EventListener
import okhttp3.OkHttpClient

/**
 * Installs LatencyScope on an [OkHttpClient.Builder].
 *
 * ```kotlin
 * val client = OkHttpClient.Builder()
 *     .installLatencyScope()
 *     .build()
 * ```
 *
 * OkHttp's builder does not expose the listener factory it already holds, so
 * if the app installs its own, hand it over:
 *
 * ```kotlin
 * .installLatencyScope(existingFactory)
 * ```
 *
 * Safe to leave installed in release builds: when no session is recording,
 * every callback does a volatile read and returns.
 */
@JvmOverloads
public fun OkHttpClient.Builder.installLatencyScope(
    delegate: EventListener.Factory? = null,
): OkHttpClient.Builder = eventListenerFactory(LatencyEventListenerFactory(delegate))

/** Wraps a single existing listener rather than a factory. */
public fun OkHttpClient.Builder.installLatencyScope(
    delegate: EventListener,
): OkHttpClient.Builder = eventListenerFactory(
    LatencyEventListenerFactory(EventListener.Factory { delegate }),
)

/** Java entry point for the extensions above. */
public object LatencyOkHttp {

    @JvmStatic
    @JvmOverloads
    public fun install(
        builder: OkHttpClient.Builder,
        delegate: EventListener.Factory? = null,
    ): OkHttpClient.Builder = builder.installLatencyScope(delegate)

    /** A factory to pass to `OkHttpClient.Builder.eventListenerFactory(...)`. */
    @JvmStatic
    @JvmOverloads
    public fun factory(delegate: EventListener.Factory? = null): EventListener.Factory =
        LatencyEventListenerFactory(delegate)
}
