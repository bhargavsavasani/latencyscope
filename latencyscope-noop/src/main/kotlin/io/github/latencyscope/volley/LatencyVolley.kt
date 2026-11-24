@file:Suppress("UNUSED_PARAMETER", "unused")

package io.github.latencyscope.volley

import android.content.Context
import com.android.volley.Request
import com.android.volley.RequestQueue
import com.android.volley.toolbox.BaseHttpStack
import com.android.volley.toolbox.HttpResponse
import com.android.volley.toolbox.HurlStack
import com.android.volley.toolbox.Volley
import java.io.IOException

/**
 * No-op mirror of [LatencyHttpStack].
 *
 * Passes every request straight to [delegate]. The wrapper object still exists
 * because a consumer's release code may name the type, but it does not time,
 * measure or allocate anything on the way through.
 */
public class LatencyHttpStack(
    private val delegate: BaseHttpStack,
) : BaseHttpStack() {

    @Throws(IOException::class)
    override fun executeRequest(
        request: Request<*>,
        additionalHeaders: MutableMap<String, String>?,
    ): HttpResponse = delegate.executeRequest(request, additionalHeaders)
}

/**
 * No-op mirror of [LatencyVolley].
 *
 * [stack] hands back the delegate itself rather than wrapping it: with nothing
 * to record, a wrapper would only add a virtual call to every request.
 */
public object LatencyVolley {

    @JvmStatic
    @JvmOverloads
    public fun stack(delegate: BaseHttpStack = HurlStack()): BaseHttpStack = delegate

    @JvmStatic
    @JvmOverloads
    public fun newRequestQueue(
        context: Context,
        delegate: BaseHttpStack = HurlStack(),
    ): RequestQueue = Volley.newRequestQueue(context, delegate)
}
