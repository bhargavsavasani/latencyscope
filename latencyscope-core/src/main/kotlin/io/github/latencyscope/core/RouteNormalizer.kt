package io.github.latencyscope.core

/**
 * Collapses concrete URLs into aggregatable routes.
 *
 * `/users/42/orders/9001` and `/users/7/orders/3` must land in the same bucket
 * or the report degenerates into one row per call.
 */
public fun interface RouteNormalizer {
    /** @param path the URL path, always starting with `/`. */
    public fun normalize(path: String): String
}

/**
 * Heuristic normalizer used unless the consumer supplies their own.
 *
 * Custom [rules] are applied first, in order, and win over the heuristics —
 * that is the escape hatch for routes the heuristics cannot know about, such
 * as `/v1/products/blue-widget` where the slug is really an id.
 */
public class DefaultRouteNormalizer(
    private val rules: List<Rule> = emptyList(),
) : RouteNormalizer {

    /** A caller-supplied override: every match of [pattern] becomes [replacement]. */
    public data class Rule(
        public val pattern: Regex,
        public val replacement: String,
    )

    override fun normalize(path: String): String {
        if (path.isEmpty()) return "/"

        var working = path
        for (rule in rules) {
            working = working.replace(rule.pattern, rule.replacement)
        }

        val normalized = working
            .split('/')
            .joinToString("/") { segment -> normalizeSegment(segment) }

        return when {
            normalized.isEmpty() -> "/"
            !normalized.startsWith('/') -> "/$normalized"
            else -> normalized
        }
    }

    private fun normalizeSegment(segment: String): String {
        if (segment.isEmpty()) return segment
        // Already placeholder-ised by a custom rule.
        if (segment.startsWith('{') && segment.endsWith('}')) return segment

        // Keep file-like segments readable: normalize the stem, keep the suffix.
        val dot = segment.lastIndexOf('.')
        if (dot > 0 && dot < segment.length - 1) {
            val stem = segment.substring(0, dot)
            val ext = segment.substring(dot)
            val replaced = placeholderFor(stem)
            if (replaced != null) return replaced + ext
            return segment
        }

        return placeholderFor(segment) ?: segment
    }

    private fun placeholderFor(segment: String): String? = when {
        NUMERIC.matches(segment) -> "{id}"
        UUID.matches(segment) -> "{uuid}"
        HEX.matches(segment) && segment.length >= HEX_MIN_LENGTH -> "{hash}"
        isOpaqueToken(segment) -> "{token}"
        else -> null
    }

    /**
     * Long mixed-case/base64-ish blobs are ids in disguise (JWTs, cursors,
     * signed urls). Requiring both a digit and a non-trivial length keeps real
     * words like `notifications` or `recommendations` out.
     */
    private fun isOpaqueToken(segment: String): Boolean {
        if (segment.length < TOKEN_MIN_LENGTH) return false
        if (!TOKEN_CHARS.matches(segment)) return false
        if (segment.none { it.isDigit() }) return false
        // A dictionary-ish word with a couple of digits is still a word.
        val digitRatio = segment.count { it.isDigit() }.toDouble() / segment.length
        return digitRatio >= TOKEN_MIN_DIGIT_RATIO || segment.any { it.isUpperCase() }
    }

    public companion object {
        private val NUMERIC = Regex("^\\d+$")
        private val UUID = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        )
        private val HEX = Regex("^[0-9a-fA-F]+$")
        private val TOKEN_CHARS = Regex("^[A-Za-z0-9_~.=-]+$")

        private const val HEX_MIN_LENGTH = 16
        private const val TOKEN_MIN_LENGTH = 24
        private const val TOKEN_MIN_DIGIT_RATIO = 0.15
    }
}

/** Normalizer that leaves paths untouched. Useful in tests and for tiny APIs. */
public object IdentityRouteNormalizer : RouteNormalizer {
    override fun normalize(path: String): String = path.ifEmpty { "/" }
}
