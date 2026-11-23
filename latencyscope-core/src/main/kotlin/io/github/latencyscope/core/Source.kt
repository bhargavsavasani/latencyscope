package io.github.latencyscope.core

/** Which HTTP stack produced a record. */
public enum class Source {
    OKHTTP,
    KTOR,
    VOLLEY,
    URL_CONNECTION,
    MANUAL,
    ;

    public val displayName: String
        get() = when (this) {
            OKHTTP -> "OkHttp"
            KTOR -> "Ktor"
            VOLLEY -> "Volley"
            URL_CONNECTION -> "HttpURLConnection"
            MANUAL -> "Manual"
        }

    /**
     * Whether this stack can report a per-phase breakdown. Only OkHttp exposes
     * every connection phase; the rest report coarser timings and the report
     * must not pretend otherwise.
     */
    public val hasFullPhaseBreakdown: Boolean
        get() = this == OKHTTP
}
