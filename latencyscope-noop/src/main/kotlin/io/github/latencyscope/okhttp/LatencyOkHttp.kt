@file:Suppress("UNUSED_PARAMETER", "unused", "TooManyFunctions")

package io.github.latencyscope.okhttp

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

/*
 * No-op mirror of latencyscope-okhttp.
 *
 * `installLatencyScope()` is the call most likely to sit in a shared source
 * set — it belongs with the rest of the OkHttp wiring, not behind a build-type
 * fork — so it has to exist in release. Here it installs a listener that
 * forwards to whatever the app already had and measures nothing, which leaves
 * the client exactly as it would have been without LatencyScope at all.
 */

/**
 * Mirror of the real [LatencyCallListener].
 *
 * Every callback is still overridden so a wrapped [delegate] keeps receiving
 * its events; no timestamps are taken and no record is produced.
 */
public class LatencyCallListener internal constructor(
    private val delegate: EventListener?,
) : EventListener() {

    override fun callStart(call: Call) {
        delegate?.callStart(call)
    }

    override fun callEnd(call: Call) {
        delegate?.callEnd(call)
    }

    override fun callFailed(call: Call, ioe: IOException) {
        delegate?.callFailed(call, ioe)
    }

    override fun canceled(call: Call) {
        delegate?.canceled(call)
    }

    override fun dnsStart(call: Call, domainName: String) {
        delegate?.dnsStart(call, domainName)
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        delegate?.dnsEnd(call, domainName, inetAddressList)
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        delegate?.connectStart(call, inetSocketAddress, proxy)
    }

    override fun secureConnectStart(call: Call) {
        delegate?.secureConnectStart(call)
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        delegate?.secureConnectEnd(call, handshake)
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
    ) {
        delegate?.connectEnd(call, inetSocketAddress, proxy, protocol)
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) {
        delegate?.connectFailed(call, inetSocketAddress, proxy, protocol, ioe)
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        delegate?.connectionAcquired(call, connection)
    }

    override fun connectionReleased(call: Call, connection: Connection) {
        delegate?.connectionReleased(call, connection)
    }

    override fun requestHeadersStart(call: Call) {
        delegate?.requestHeadersStart(call)
    }

    override fun requestHeadersEnd(call: Call, request: Request) {
        delegate?.requestHeadersEnd(call, request)
    }

    override fun requestBodyStart(call: Call) {
        delegate?.requestBodyStart(call)
    }

    override fun requestBodyEnd(call: Call, byteCount: Long) {
        delegate?.requestBodyEnd(call, byteCount)
    }

    override fun requestFailed(call: Call, ioe: IOException) {
        delegate?.requestFailed(call, ioe)
    }

    override fun responseHeadersStart(call: Call) {
        delegate?.responseHeadersStart(call)
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        delegate?.responseHeadersEnd(call, response)
    }

    override fun responseBodyStart(call: Call) {
        delegate?.responseBodyStart(call)
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        delegate?.responseBodyEnd(call, byteCount)
    }

    override fun responseFailed(call: Call, ioe: IOException) {
        delegate?.responseFailed(call, ioe)
    }

    override fun cacheHit(call: Call, response: Response) {
        delegate?.cacheHit(call, response)
    }

    override fun cacheConditionalHit(call: Call, cachedResponse: Response) {
        delegate?.cacheConditionalHit(call, cachedResponse)
    }

    override fun cacheMiss(call: Call) {
        delegate?.cacheMiss(call)
    }

    override fun satisfactionFailure(call: Call, response: Response) {
        delegate?.satisfactionFailure(call, response)
    }

    override fun proxySelectStart(call: Call, url: HttpUrl) {
        delegate?.proxySelectStart(call, url)
    }

    override fun proxySelectEnd(call: Call, url: HttpUrl, proxies: List<Proxy>) {
        delegate?.proxySelectEnd(call, url, proxies)
    }
}

/** Mirror of the real [LatencyEventListenerFactory]. */
public class LatencyEventListenerFactory @JvmOverloads constructor(
    private val delegate: EventListener.Factory? = null,
) : EventListener.Factory {
    override fun create(call: Call): EventListener = LatencyCallListener(delegate?.create(call))
}

/** Mirror of the real `installLatencyScope`. Installs a listener that measures nothing. */
@JvmOverloads
public fun OkHttpClient.Builder.installLatencyScope(
    delegate: EventListener.Factory? = null,
): OkHttpClient.Builder = eventListenerFactory(LatencyEventListenerFactory(delegate))

/** Mirror of the single-listener overload. */
public fun OkHttpClient.Builder.installLatencyScope(
    delegate: EventListener,
): OkHttpClient.Builder = eventListenerFactory(
    LatencyEventListenerFactory(EventListener.Factory { delegate }),
)

/** Mirror of the real [LatencyOkHttp]. */
public object LatencyOkHttp {

    @JvmStatic
    @JvmOverloads
    public fun install(
        builder: OkHttpClient.Builder,
        delegate: EventListener.Factory? = null,
    ): OkHttpClient.Builder = builder.installLatencyScope(delegate)

    @JvmStatic
    @JvmOverloads
    public fun factory(delegate: EventListener.Factory? = null): EventListener.Factory =
        LatencyEventListenerFactory(delegate)
}
