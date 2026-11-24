package io.github.latencyscope.core

/**
 * Strips credentials out of URLs before they are stored.
 *
 * Reports get shared — attached to tickets, dropped in chat, mailed to a
 * backend team. Anything secret must be gone before it reaches a record, not
 * filtered at render time, because the in-memory session is itself shareable.
 */
internal class Redactor(
    private val captureQueryParams: Boolean,
    private val sensitiveQueryKeys: Set<String> = DEFAULT_SENSITIVE_QUERY_KEYS,
) {

    /**
     * Returns [url] with userinfo removed, the fragment dropped and query
     * values masked unless the consumer explicitly opted into keeping them.
     */
    fun redactUrl(url: String): String {
        val noFragment = url.substringBefore('#')
        val withoutUserInfo = stripUserInfo(noFragment)

        val queryStart = withoutUserInfo.indexOf('?')
        if (queryStart < 0) return withoutUserInfo

        val base = withoutUserInfo.substring(0, queryStart)
        val query = withoutUserInfo.substring(queryStart + 1)
        if (query.isEmpty()) return base

        val redactedQuery = query
            .split('&')
            .joinToString("&") { param -> redactParam(param) }

        return "$base?$redactedQuery"
    }

    private fun redactParam(param: String): String {
        val eq = param.indexOf('=')
        if (eq < 0) return param
        val key = param.substring(0, eq)
        if (!captureQueryParams || key.lowercase() in sensitiveQueryKeys) {
            return "$key=$MASK"
        }
        return param
    }

    /** `https://user:pass@host/x` -> `https://host/x`. */
    private fun stripUserInfo(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        val authorityStart = schemeEnd + 3
        val authorityEnd = url.indexOfFirst(authorityStart) { it == '/' || it == '?' }
        val authority = url.substring(authorityStart, authorityEnd)
        val at = authority.lastIndexOf('@')
        if (at < 0) return url
        return url.substring(0, authorityStart) + authority.substring(at + 1) +
            url.substring(authorityEnd)
    }

    private inline fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
        for (i in from until length) {
            if (predicate(this[i])) return i
        }
        return length
    }

    public companion object {
        public const val MASK: String = "***"

        // No header allowlist here on purpose: no module records headers, and
        // a published `ALWAYS_SENSITIVE_HEADERS` that nothing consults reads as
        // a promise the library does not keep. It comes back with the feature.

        val DEFAULT_SENSITIVE_QUERY_KEYS: Set<String> = setOf(
            "token",
            "access_token",
            "refresh_token",
            "id_token",
            "api_key",
            "apikey",
            "key",
            "secret",
            "password",
            "pwd",
            "signature",
            "sig",
            "auth",
            "session",
            "code",
        )
    }
}
