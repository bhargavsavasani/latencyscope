package io.github.latencyscope.core

import java.net.URI

/**
 * Host/path split that never throws.
 *
 * Deliberately uses [URI] rather than `android.net.Uri` so the same code runs
 * in JVM unit tests, and falls back to string slicing for URLs the parser
 * rejects — a malformed URL must still produce a record, not an exception on
 * the caller's network thread.
 */
internal data class UrlParts(
    val host: String,
    val path: String,
) {
    companion object {
        private const val UNKNOWN_HOST = "unknown"

        fun parse(url: String): UrlParts {
            try {
                val uri = URI(url)
                val host = uri.host
                val path = uri.rawPath
                if (!host.isNullOrEmpty()) {
                    return UrlParts(host, path.orEmpty().ifEmpty { "/" })
                }
            } catch (_: Exception) {
                // Fall through to manual parsing below.
            }
            return parseManually(url)
        }

        private fun parseManually(url: String): UrlParts {
            val schemeEnd = url.indexOf("://")
            if (schemeEnd < 0) {
                val path = url.substringBefore('?').ifEmpty { "/" }
                return UrlParts(UNKNOWN_HOST, if (path.startsWith('/')) path else "/$path")
            }
            val afterScheme = url.substring(schemeEnd + 3)
            val authority = afterScheme.substringBefore('/').substringBefore('?')
            val host = authority.substringAfterLast('@').substringBefore(':')
            val rest = afterScheme.removePrefix(authority).substringBefore('?')
            return UrlParts(
                host = host.ifEmpty { UNKNOWN_HOST },
                path = rest.ifEmpty { "/" },
            )
        }
    }
}
