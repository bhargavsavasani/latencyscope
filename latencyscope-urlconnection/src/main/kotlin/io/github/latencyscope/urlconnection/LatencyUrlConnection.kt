package io.github.latencyscope.urlconnection

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection

/**
 * Entry point for instrumenting `HttpURLConnection`.
 *
 * ```kotlin
 * val connection = LatencyUrlConnection.open(URL("https://api.example.com/users"))
 * connection.inputStream.use { ... }
 * ```
 *
 * There is deliberately no global install. `URL.setURLStreamHandlerFactory` is
 * the only JVM-wide hook, it can be set exactly once per process, and claiming
 * it breaks OkHttp and anything else that wants it. An explicit wrapper costs
 * one line at the call site and cannot sabotage the rest of the app.
 */
public object LatencyUrlConnection {

    /** Opens [url] and returns an instrumented connection. */
    @JvmStatic
    public fun open(url: URL): HttpURLConnection = wrap(url.openConnection())

    /** Wraps an already-opened connection. Non-HTTP connections pass through. */
    @JvmStatic
    public fun wrap(connection: URLConnection): HttpURLConnection = when (connection) {
        is LatencyHttpURLConnection -> connection
        is HttpURLConnection -> LatencyHttpURLConnection(connection)
        else -> throw IllegalArgumentException(
            "LatencyScope can only instrument HttpURLConnection, got ${connection.javaClass.name}",
        )
    }
}

/** Kotlin sugar for [LatencyUrlConnection.open]. */
public fun URL.openInstrumentedConnection(): HttpURLConnection = LatencyUrlConnection.open(this)
