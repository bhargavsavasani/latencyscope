@file:Suppress("UNUSED_PARAMETER", "unused", "TooManyFunctions")

package io.github.latencyscope.urlconnection

import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.security.Permission

/**
 * No-op mirror of [LatencyUrlConnection].
 *
 * [wrap] returns the caller's own connection untouched. The real module can
 * only measure by interposing on every method; with nothing to measure, the
 * right no-op is to get out of the way entirely rather than to insert a
 * delegating object that forwards several dozen calls for no reason.
 *
 * The rejection of a non-HTTP connection is kept: a caller who passes one has
 * a bug, and it should surface in both build types.
 */
public object LatencyUrlConnection {

    @JvmStatic
    public fun open(url: URL): HttpURLConnection = wrap(url.openConnection())

    @JvmStatic
    public fun wrap(connection: URLConnection): HttpURLConnection = when (connection) {
        is HttpURLConnection -> connection
        else -> throw IllegalArgumentException(
            "LatencyScope can only instrument HttpURLConnection, got ${connection.javaClass.name}",
        )
    }
}

/** Mirror of the real `URL.openInstrumentedConnection()`. */
public fun URL.openInstrumentedConnection(): HttpURLConnection = LatencyUrlConnection.open(this)

/**
 * No-op mirror of [LatencyHttpURLConnection].
 *
 * Present so a consumer's release code can still name the type — the real
 * module returns it from [LatencyUrlConnection.wrap], so it can appear in a
 * variable's declared type or a cast. Its constructor is internal and this
 * module's [LatencyUrlConnection.wrap] never calls it, so in practice R8 drops
 * the whole class. Every method delegates, so it stays correct if it ever is
 * constructed.
 */
public class LatencyHttpURLConnection internal constructor(
    private val delegate: HttpURLConnection,
) : HttpURLConnection(delegate.url) {

    override fun connect() {
        delegate.connect()
    }

    override fun disconnect() {
        delegate.disconnect()
    }

    override fun usingProxy(): Boolean = delegate.usingProxy()

    override fun getOutputStream(): OutputStream = delegate.outputStream

    override fun getInputStream(): InputStream = delegate.inputStream

    override fun getErrorStream(): InputStream? = delegate.errorStream

    override fun getResponseCode(): Int = delegate.responseCode

    override fun getResponseMessage(): String? = delegate.responseMessage

    override fun getHeaderField(name: String?): String? = delegate.getHeaderField(name)

    override fun getHeaderField(n: Int): String? = delegate.getHeaderField(n)

    override fun getHeaderFieldKey(n: Int): String? = delegate.getHeaderFieldKey(n)

    override fun getHeaderFields(): MutableMap<String, MutableList<String>> = delegate.headerFields

    override fun getContentLength(): Int = delegate.contentLength

    override fun getContentType(): String? = delegate.contentType

    override fun getContentEncoding(): String? = delegate.contentEncoding

    override fun getDate(): Long = delegate.date

    override fun getExpiration(): Long = delegate.expiration

    override fun getLastModified(): Long = delegate.lastModified

    override fun getContent(): Any? = delegate.content

    @Suppress("UNCHECKED_CAST")
    override fun getContent(classes: Array<out Class<*>>?): Any? =
        delegate.getContent(classes as Array<Class<*>>?)

    override fun setRequestMethod(method: String) {
        delegate.requestMethod = method
    }

    override fun getRequestMethod(): String = delegate.requestMethod

    override fun setInstanceFollowRedirects(followRedirects: Boolean) {
        delegate.instanceFollowRedirects = followRedirects
    }

    override fun getInstanceFollowRedirects(): Boolean = delegate.instanceFollowRedirects

    override fun setRequestProperty(key: String?, value: String?) {
        delegate.setRequestProperty(key, value)
    }

    override fun addRequestProperty(key: String?, value: String?) {
        delegate.addRequestProperty(key, value)
    }

    override fun getRequestProperty(key: String?): String? = delegate.getRequestProperty(key)

    override fun getRequestProperties(): MutableMap<String, MutableList<String>> =
        delegate.requestProperties

    override fun setConnectTimeout(timeout: Int) {
        delegate.connectTimeout = timeout
    }

    override fun getConnectTimeout(): Int = delegate.connectTimeout

    override fun setReadTimeout(timeout: Int) {
        delegate.readTimeout = timeout
    }

    override fun getReadTimeout(): Int = delegate.readTimeout

    override fun setDoInput(doinput: Boolean) {
        delegate.doInput = doinput
    }

    override fun getDoInput(): Boolean = delegate.doInput

    override fun setDoOutput(dooutput: Boolean) {
        delegate.doOutput = dooutput
    }

    override fun getDoOutput(): Boolean = delegate.doOutput

    override fun setUseCaches(usecaches: Boolean) {
        delegate.useCaches = usecaches
    }

    override fun getUseCaches(): Boolean = delegate.useCaches

    override fun setAllowUserInteraction(allowuserinteraction: Boolean) {
        delegate.allowUserInteraction = allowuserinteraction
    }

    override fun getAllowUserInteraction(): Boolean = delegate.allowUserInteraction

    override fun setIfModifiedSince(ifmodifiedsince: Long) {
        delegate.ifModifiedSince = ifmodifiedsince
    }

    override fun getIfModifiedSince(): Long = delegate.ifModifiedSince

    override fun setDefaultUseCaches(defaultusecaches: Boolean) {
        delegate.defaultUseCaches = defaultusecaches
    }

    override fun getDefaultUseCaches(): Boolean = delegate.defaultUseCaches

    override fun setChunkedStreamingMode(chunklen: Int) {
        delegate.setChunkedStreamingMode(chunklen)
    }

    override fun setFixedLengthStreamingMode(contentLength: Int) {
        delegate.setFixedLengthStreamingMode(contentLength)
    }

    override fun setFixedLengthStreamingMode(contentLength: Long) {
        delegate.setFixedLengthStreamingMode(contentLength)
    }

    override fun getPermission(): Permission? = delegate.permission

    override fun getURL(): URL = delegate.url

    override fun toString(): String = delegate.toString()
}
