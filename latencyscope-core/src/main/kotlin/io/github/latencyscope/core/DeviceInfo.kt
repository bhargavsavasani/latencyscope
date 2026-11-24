package io.github.latencyscope.core

/** Device and app identity captured once, so a shared report is self-explaining. */
public data class DeviceInfo(
    public val manufacturer: String? = null,
    public val model: String? = null,
    public val androidVersion: String? = null,
    public val sdkInt: Int = 0,
    /** The app's user-visible label, which is rarely the same as [appId]. */
    public val appName: String? = null,
    public val appId: String? = null,
    public val appVersionName: String? = null,
    public val appVersionCode: Long = 0,
    public val locale: String? = null,
    /**
     * The transport in use when the session started, if readable.
     *
     * A session-level snapshot for context. Individual calls carry their own
     * [ApiCallRecord.networkType], which is what the report's per-network
     * split is built from — a session can start on wifi and finish on
     * cellular.
     */
    public val networkTypeAtStart: String? = null,
    /**
     * The downstream link speed the OS advertises, in kbps, or null.
     *
     * This is the radio's *typical* capability, not a measurement, and it is
     * routinely wrong by an order of magnitude — the report labels it as an
     * estimate for that reason. For what the app actually achieved, use
     * [LatencyReport.throughputBytesPerSecond], which is derived from bytes
     * this library watched move.
     */
    public val linkDownstreamKbps: Int? = null,
    /** The upstream equivalent of [linkDownstreamKbps], with the same caveat. */
    public val linkUpstreamKbps: Int? = null,
    public val libraryVersion: String = BuildInfo.VERSION,
) {
    public companion object {
        public val UNKNOWN: DeviceInfo = DeviceInfo()
    }
}

/** Build-time constants. Kept in code so the module needs no BuildConfig. */
public object BuildInfo {
    public const val VERSION: String = "0.1.0-SNAPSHOT"
    public const val NAME: String = "LatencyScope"
}
