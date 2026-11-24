@file:Suppress("UNUSED_PARAMETER", "unused")

package io.github.latencyscope.core

import android.content.Context

/**
 * No-op mirror of the real [LatencyScope].
 *
 * Nothing is recorded, nothing is retained, and no session ever exists. The
 * accessors return the same "nothing here" answers the real facade gives
 * before [start] is called, so a release build takes the branch an app would
 * already have written for "not recording" rather than meeting a surprise.
 *
 * [track] is the one method that must still do something: it runs the block
 * and returns its result, because the caller's work is not optional just
 * because the measurement is.
 */
public object LatencyScope {

    @JvmStatic
    @JvmOverloads
    public fun init(context: Context, config: LatencyConfig = LatencyConfig()) {
        // No recorder to install.
    }

    @JvmStatic
    public fun setClockForTesting(clock: Clock) {
        // No clock to override.
    }

    @JvmStatic
    @JvmOverloads
    public fun initForTesting(config: LatencyConfig = LatencyConfig()) {
        // No recorder to install.
    }

    @JvmStatic
    @JvmOverloads
    public fun start(name: String? = null): SessionHandle =
        SessionHandle(id = "", name = name.orEmpty(), startedAtEpochMs = 0)

    @JvmStatic
    public fun stop(): LatencySession? = null

    @JvmStatic
    public fun stopSession(handle: SessionHandle): LatencySession? = null

    @JvmStatic
    public fun isRecording(): Boolean = false

    @JvmStatic
    public fun isInitialized(): Boolean = false

    @JvmStatic
    public fun currentSession(): LatencySession? = null

    @JvmStatic
    public fun lastSession(): LatencySession? = null

    @JvmStatic
    public fun clear() {
        // Nothing was stored.
    }

    @JvmStatic
    public fun reset() {
        // Nothing was stored.
    }

    @JvmStatic
    public fun config(): LatencyConfig = LatencyConfig()

    @JvmStatic
    public fun submit(call: RawCall) {
        // The instrumentation modules' no-op mirrors never call this, but a
        // consumer's own bridge code might; discarding is the whole point.
    }

    @JvmStatic
    public fun record(record: ApiCallRecord) {
        // Discarded.
    }

    /** Runs [block] and returns its result. Timing is dropped, the work is not. */
    @JvmStatic
    @JvmOverloads
    public fun <T> track(
        url: String,
        method: String = "GET",
        block: () -> T,
    ): T = block()

    @JvmStatic
    public fun mark(label: String) {
        // No timeline to mark.
    }

    @JvmStatic
    @JvmOverloads
    public fun report(session: LatencySession? = null): LatencyReport? = null

    @JvmStatic
    public fun elapsedMs(): Long = 0

    @JvmStatic
    public fun epochMs(): Long = 0

    /** Mirror of the real [SessionListener]. Never called: nothing ever starts. */
    public fun interface SessionListener {
        public fun onSessionChanged(session: LatencySession?)
    }

    @JvmStatic
    public fun addSessionListener(listener: SessionListener) {
        // No session will ever change.
    }

    @JvmStatic
    public fun removeSessionListener(listener: SessionListener) {
        // Nothing was registered.
    }

    @JvmStatic
    public fun recordedCount(): Int = 0

    @JvmStatic
    public fun errorCount(): Int = 0
}

/** Mirror of the real `Throwable.describe()`. */
public fun Throwable.describe(): String = ""
