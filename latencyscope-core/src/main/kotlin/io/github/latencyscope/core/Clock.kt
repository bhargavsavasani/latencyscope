package io.github.latencyscope.core

import android.os.SystemClock

/**
 * Indirection over time so tests can produce deterministic sessions.
 *
 * Durations use [elapsedMs] (monotonic) and never wall-clock, which jumps when
 * the user or NTP changes the device time mid-session. Wall-clock is recorded
 * separately, only so the report can print a human-readable timestamp.
 */
public interface Clock {
    /** Monotonic milliseconds since boot. Only differences are meaningful. */
    public fun elapsedMs(): Long

    /** Wall-clock milliseconds since the epoch, for display. */
    public fun epochMs(): Long

    public companion object {
        public val SYSTEM: Clock = object : Clock {
            override fun elapsedMs(): Long = SystemClock.elapsedRealtime()
            override fun epochMs(): Long = System.currentTimeMillis()
        }
    }
}
