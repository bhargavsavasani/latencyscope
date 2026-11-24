package io.github.latencyscope.sample.net

import io.github.latencyscope.sample.CallResult
import io.github.latencyscope.sample.SampleTab
import io.github.latencyscope.urlconnection.LatencyUrlConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * HttpURLConnection — the lowest-level of the four, and the one with the
 * fewest phases: the wrapper can see connect, first byte and body, and nothing
 * underneath them.
 */
internal class UrlConnectionSampleClient : SampleClient {

    override val tab: SampleTab get() = SampleTab.URL_CONNECTION

    override suspend fun get(url: String, timeoutMs: Long): CallResult =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            runCatching {
                connection = LatencyUrlConnection.open(URL(url)).apply {
                    requestMethod = "GET"
                    connectTimeout = timeoutMs.toInt()
                    readTimeout = timeoutMs.toInt()
                }
                val open = checkNotNull(connection)
                val status = open.responseCode
                // The error stream, not the input stream, carries the body of a
                // 4xx/5xx — reading the wrong one throws and would turn a
                // recorded failing status into a recorded transport error.
                val stream = if (status >= HttpURLConnection.HTTP_BAD_REQUEST) {
                    open.errorStream
                } else {
                    open.inputStream
                }
                val bytes = stream?.use { it.readBytes().size.toLong() } ?: 0L
                CallResult(statusCode = status, bytes = bytes, error = null)
            }.getOrElse { failure ->
                CallResult(statusCode = null, bytes = -1, error = failure.describeForLog())
            }.also {
                // Closing the stream above is what offers the socket back to
                // the keep-alive pool; this just releases our handle on it, and
                // matters most on the paths where no stream was ever opened.
                connection?.disconnect()
            }
        }
}
