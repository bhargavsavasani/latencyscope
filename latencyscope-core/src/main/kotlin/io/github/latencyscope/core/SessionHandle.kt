package io.github.latencyscope.core

/**
 * Handle to the session returned by [LatencyScope.start].
 *
 * Lets a caller stop the exact session they started — useful in tests and in
 * instrumented runs where a stray `stop()` elsewhere would otherwise silently
 * end the wrong recording.
 */
public class SessionHandle internal constructor(
    public val id: String,
    public val name: String,
    public val startedAtEpochMs: Long,
) {
    /** Stops this session, or returns null if a different session is active. */
    public fun stop(): LatencySession? = LatencyScope.stopSession(this)

    override fun toString(): String = "SessionHandle(id=$id, name=$name)"
}
