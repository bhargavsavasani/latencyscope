package io.github.latencyscope.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultRouteNormalizerTest {

    private val normalizer = DefaultRouteNormalizer()

    @Test
    fun collapsesNumericIds() {
        assertEquals("/users/{id}", normalizer.normalize("/users/42"))
        assertEquals("/users/{id}/orders/{id}", normalizer.normalize("/users/42/orders/9001"))
    }

    @Test
    fun collapsesUuids() {
        assertEquals(
            "/files/{uuid}",
            normalizer.normalize("/files/3f2504e0-4f89-11d3-9a0c-0305e82c3301"),
        )
    }

    @Test
    fun collapsesLongHexHashes() {
        assertEquals("/blobs/{hash}", normalizer.normalize("/blobs/9f86d081884c7d65"))
    }

    @Test
    fun shortHexStaysAWord() {
        // "cafe" and "beef" are hex, but they are far more likely to be real paths.
        assertEquals("/cafe", normalizer.normalize("/cafe"))
        assertEquals("/beef", normalizer.normalize("/beef"))
    }

    @Test
    fun collapsesOpaqueTokens() {
        assertEquals(
            "/sessions/{token}",
            normalizer.normalize("/sessions/eyJhbGciOiJIUzI1NiJ9abc123XYZ"),
        )
    }

    @Test
    fun keepsRealWords() {
        assertEquals("/api/v1/users", normalizer.normalize("/api/v1/users"))
        assertEquals("/notifications", normalizer.normalize("/notifications"))
        assertEquals("/recommendations/trending", normalizer.normalize("/recommendations/trending"))
        assertEquals("/v2/user-preferences", normalizer.normalize("/v2/user-preferences"))
    }

    @Test
    fun keepsFileExtensions() {
        assertEquals("/images/{id}.jpg", normalizer.normalize("/images/12345.jpg"))
        assertEquals("/static/app.js", normalizer.normalize("/static/app.js"))
    }

    @Test
    fun handlesEdgeCases() {
        assertEquals("/", normalizer.normalize(""))
        assertEquals("/", normalizer.normalize("/"))
        assertEquals("/users/{id}/", normalizer.normalize("/users/7/"))
    }

    @Test
    fun customRulesWinOverHeuristics() {
        val custom = DefaultRouteNormalizer(
            rules = listOf(
                DefaultRouteNormalizer.Rule(
                    Regex("/products/[a-z-]+"),
                    "/products/{slug}",
                ),
            ),
        )
        assertEquals("/products/{slug}", custom.normalize("/products/blue-widget"))
        // Heuristics still run for everything the custom rule did not match.
        assertEquals("/users/{id}", custom.normalize("/users/42"))
    }

    @Test
    fun identityNormalizerLeavesPathsAlone() {
        assertEquals("/users/42", IdentityRouteNormalizer.normalize("/users/42"))
        assertEquals("/", IdentityRouteNormalizer.normalize(""))
    }
}
