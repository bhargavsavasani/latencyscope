package io.github.latencyscope.volley

import android.content.Context
import com.android.volley.RequestQueue
import com.android.volley.toolbox.BaseHttpStack
import com.android.volley.toolbox.HurlStack
import com.android.volley.toolbox.Volley

/** Convenience entry points for wiring LatencyScope into Volley. */
public object LatencyVolley {

    /** Wraps [delegate] so every request it executes is recorded. */
    @JvmStatic
    @JvmOverloads
    public fun stack(delegate: BaseHttpStack = HurlStack()): BaseHttpStack =
        LatencyHttpStack(delegate)

    /**
     * A [RequestQueue] that records every request.
     *
     * Equivalent to `Volley.newRequestQueue(context, LatencyHttpStack(...))`,
     * which is still the right call if you already build your own queue.
     */
    @JvmStatic
    @JvmOverloads
    public fun newRequestQueue(
        context: Context,
        delegate: BaseHttpStack = HurlStack(),
    ): RequestQueue = Volley.newRequestQueue(context, stack(delegate))
}
