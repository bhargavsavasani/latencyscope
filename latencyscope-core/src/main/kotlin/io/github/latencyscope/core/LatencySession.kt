package io.github.latencyscope.core

/**
 * Everything captured between [LatencyScope.start] and [LatencyScope.stop].
 *
 * @property droppedCount calls discarded because the ring buffer was full.
 *   Surfaced in every report so readers know the data is partial.
 */
public data class LatencySession(
    public val id: String,
    public val name: String,
    public val startedAtEpochMs: Long,
    public val endedAtEpochMs: Long?,
    public val durationMs: Long,
    public val device: DeviceInfo,
    public val records: List<ApiCallRecord>,
    public val markers: List<Marker> = emptyList(),
    public val droppedCount: Long = 0,
) {
    public val isComplete: Boolean
        get() = endedAtEpochMs != null

    public val isTruncated: Boolean
        get() = droppedCount > 0
}
