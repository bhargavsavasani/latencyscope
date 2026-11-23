package io.github.latencyscope.core

/**
 * A user-flow landmark dropped onto the session timeline, e.g. "opened cart".
 *
 * Markers are what turn a flat list of calls into something a reader can
 * reason about: they show which screen or action triggered which burst.
 */
public data class Marker(
    public val label: String,
    public val atEpochMs: Long,
    public val atElapsedMs: Long,
)
