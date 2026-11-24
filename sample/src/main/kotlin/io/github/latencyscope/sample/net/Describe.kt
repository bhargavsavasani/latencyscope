package io.github.latencyscope.sample.net

/**
 * A short label for a failure.
 *
 * The library has its own `Throwable.describe()`, but it lives in
 * `latencyscope-core`, and the sample deliberately formats its own log so that
 * what you read on screen is the app's view, not the library's.
 */
internal fun Throwable.describeForLog(): String {
    val name = this::class.java.simpleName.ifEmpty { this::class.java.name }
    val detail = message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""
    return (name + detail).take(MAX_LOGGED_ERROR_CHARS)
}

private const val MAX_LOGGED_ERROR_CHARS = 120
