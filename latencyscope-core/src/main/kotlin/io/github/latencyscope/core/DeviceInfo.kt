package io.github.latencyscope.core

/** Device and app identity captured once, so a shared report is self-explaining. */
public data class DeviceInfo(
    public val manufacturer: String? = null,
    public val model: String? = null,
    public val androidVersion: String? = null,
    public val sdkInt: Int = 0,
    public val appId: String? = null,
    public val appVersionName: String? = null,
    public val appVersionCode: Long = 0,
    public val locale: String? = null,
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
