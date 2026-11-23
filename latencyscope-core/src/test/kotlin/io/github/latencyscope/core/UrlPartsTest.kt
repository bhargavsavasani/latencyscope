package io.github.latencyscope.core

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlPartsTest {

    @Test
    fun parsesStandardUrls() {
        val parts = UrlParts.parse("https://api.example.com/v1/users/42?x=1")
        assertEquals("api.example.com", parts.host)
        assertEquals("/v1/users/42", parts.path)
    }

    @Test
    fun parsesUrlWithPortAndNoPath() {
        val parts = UrlParts.parse("http://localhost:8080")
        assertEquals("localhost", parts.host)
        assertEquals("/", parts.path)
    }

    @Test
    fun malformedUrlStillYieldsARecordableResult() {
        // A URL we cannot parse must never blow up on the caller's network thread.
        val parts = UrlParts.parse("https://api example.com/a b/c")
        assertEquals("api example.com", parts.host)
        assertEquals("/a b/c", parts.path)
    }

    @Test
    fun relativeUrlFallsBackToUnknownHost() {
        val parts = UrlParts.parse("/v1/ping")
        assertEquals("unknown", parts.host)
        assertEquals("/v1/ping", parts.path)
    }
}
