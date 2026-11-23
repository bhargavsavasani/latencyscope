package io.github.latencyscope.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RedactorTest {

    private val strict = Redactor(captureQueryParams = false)
    private val permissive = Redactor(captureQueryParams = true)

    @Test
    fun masksEveryQueryValueByDefault() {
        assertEquals(
            "https://api.example.com/search?q=***&page=***",
            strict.redactUrl("https://api.example.com/search?q=shoes&page=2"),
        )
    }

    @Test
    fun keepsQueryValuesWhenOptedIn() {
        assertEquals(
            "https://api.example.com/search?q=shoes&page=2",
            permissive.redactUrl("https://api.example.com/search?q=shoes&page=2"),
        )
    }

    @Test
    fun masksSecretsEvenWhenQueryCaptureIsOn() {
        assertEquals(
            "https://api.example.com/me?access_token=***&page=2",
            permissive.redactUrl("https://api.example.com/me?access_token=abc123&page=2"),
        )
    }

    @Test
    fun stripsUserInfo() {
        assertEquals(
            "https://api.example.com/private",
            strict.redactUrl("https://user:hunter2@api.example.com/private"),
        )
    }

    @Test
    fun dropsFragment() {
        assertEquals(
            "https://api.example.com/docs",
            strict.redactUrl("https://api.example.com/docs#section-token"),
        )
    }

    @Test
    fun leavesPlainUrlsAlone() {
        assertEquals(
            "https://api.example.com/users/42",
            strict.redactUrl("https://api.example.com/users/42"),
        )
    }

    @Test
    fun handlesValuelessAndEmptyQueries() {
        assertEquals("https://x.com/a?flag", strict.redactUrl("https://x.com/a?flag"))
        assertEquals("https://x.com/a", strict.redactUrl("https://x.com/a?"))
    }
}
